package com.voxelforge.core.world

import com.voxelforge.core.light.LightEngine
import com.voxelforge.core.mesh.GreedyMesher
import com.voxelforge.core.mesh.SectionMesh
import com.voxelforge.core.mesh.SectionSnapshot
import com.voxelforge.core.util.ObjectPool
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max

/**
 * Приёмник готовых мешей. Реализуется рендером.
 *
 * Отдельный интерфейс нужен, чтобы :core не знал об OpenGL: менеджер чанков
 * лишь сообщает «вот геометрия секции», а что с ней делать — загрузить
 * в буфер GPU, посчитать статистику или сохранить в файл — решает подписчик.
 */
interface MeshConsumer {
    /** Меш секции готов. Буфер действителен только на время вызова. */
    fun onMeshReady(mesh: SectionMesh)

    /** Секция больше не нужна — связанные ресурсы GPU можно освободить. */
    fun onSectionRemoved(chunkX: Int, sectionY: Int, chunkZ: Int)
}

/**
 * Конвейер загрузки, освещения, мешинга и выгрузки чанков.
 *
 * ## Разделение обязанностей между потоками
 *
 * Это ключевое решение всего класса. Есть два принципиально разных типа
 * работы, и они разнесены по разным потокам по строгому правилу:
 *
 * **Потоки-воркеры выполняют только чистые вычисления.** Генерация работает
 * с чанком, который ещё никому не виден; мешинг — со *снимком* секции.
 * Ни один воркер не читает и не пишет опубликованное состояние мира.
 * Поэтому синхронизация между ними не нужна вовсе: нет разделяемых данных —
 * нет и гонок.
 *
 * **Игровой поток владеет миром единолично.** Публикация чанков, расчёт
 * освещения (он по своей природе затрагивает соседние чанки), снятие
 * снимков, изменение блоков игроком — всё это происходит только здесь.
 *
 * Альтернатива — разделяемый мир под блокировками — на воксельном движке
 * работает плохо: чтения вокселей идут миллионами, и любая синхронизация
 * на этом пути обходится дороже, чем вся экономия от многопоточности.
 * Копирование снимка (12 КБ на секцию) оказывается дешевле.
 *
 * ## Бюджет кадра
 *
 * Освещение и снятие снимков идут в игровом потоке и потому обязаны
 * укладываться в бюджет: при 60 кадрах в секунду на кадр всего 16,6 мс,
 * и львиная доля нужна отрисовке. Менеджер отслеживает время и прекращает
 * работу, исчерпав отведённые миллисекунды, перенося остаток на следующий
 * кадр. Именно это, а не скорость самих алгоритмов, удерживает плавность
 * при полёте над незагруженной местностью: лучше догружать мир на кадр
 * дольше, чем уронить частоту вдвое.
 *
 * @param world           хранилище чанков
 * @param renderDistance  радиус загрузки в чанках
 * @param workerCount     число потоков-воркеров; 0 означает «по числу ядер»
 */
class ChunkManager(
    @JvmField val world: World,
    renderDistance: Int = DEFAULT_RENDER_DISTANCE,
    workerCount: Int = 0
) {

    /** Радиус загрузки в чанках. Меняется в настройках графики на лету. */
    @Volatile
    var renderDistance: Int = renderDistance
        set(value) {
            field = value.coerceIn(MIN_RENDER_DISTANCE, MAX_RENDER_DISTANCE)
        }

    private val lightEngine = LightEngine(world)

    /**
     * Пул воркеров. Число потоков на единицу меньше числа ядер: одно ядро
     * оставлено игровому потоку. Занять все ядра — типичная ошибка, из-за
     * которой планировщик начинает вытеснять поток отрисовки, и кадры
     * «дёргаются» тем сильнее, чем быстрее идёт генерация.
     */
    private val executor: ExecutorService = run {
        val cores = Runtime.getRuntime().availableProcessors()
        val threads = if (workerCount > 0) workerCount else max(1, cores - 1)
        Executors.newFixedThreadPool(threads, WorkerThreadFactory())
    }

    /** Мешер у каждого воркера свой: он хранит рабочие массивы. */
    private val mesherLocal = ThreadLocal.withInitial { GreedyMesher() }

    // --- Очереди между потоками ---

    /** Сгенерированные, но ещё не опубликованные чанки. */
    private val generatedChunks = ConcurrentLinkedQueue<Chunk>()

    /** Построенные, но ещё не загруженные в GPU меши. */
    private val completedMeshes = ConcurrentLinkedQueue<SectionMesh>()

    /** Позиции чанков, для которых генерация уже запущена. */
    private val inFlight = HashSet<Long>()

    /** Секции, ожидающие снятия снимка и мешинга. */
    private val pendingMeshes = LinkedHashSet<Long>()

    /** Чанки, ожидающие расчёта освещения. */
    private val pendingLighting = ArrayList<Chunk>()

    // --- Пулы объектов ---

    private val snapshotPool = ObjectPool(
        maxSize = SNAPSHOT_POOL_SIZE,
        factory = { SectionSnapshot() },
        reset = { it.reset() }
    )

    private val meshPool = ObjectPool(
        maxSize = MESH_POOL_SIZE,
        factory = { SectionMesh() },
        reset = { it.clear(); it.trim() }
    )

    // --- Статистика для отладочной панели ---

    @JvmField val statsGenerated = AtomicInteger(0)
    @JvmField val statsMeshed = AtomicInteger(0)
    var statsPendingGeneration = 0
        private set
    var statsPendingMesh = 0
        private set

    @Volatile
    private var shuttingDown = false

    /**
     * Счётчик поколений секций. Инкрементируется при каждом изменении
     * секции и записывается в меш. Если к моменту готовности меша поколение
     * секции ушло вперёд, значит игрок успел сломать ещё один блок и меш
     * устарел — его выбрасывают, не рисуя кадр с несуществующей геометрией.
     */
    private val generations = HashMap<Long, Int>()

    // ------------------------------------------------------------------
    // Основной цикл
    // ------------------------------------------------------------------

    /**
     * Один шаг конвейера. Вызывается из игрового потока каждый кадр.
     *
     * @param playerX, playerZ позиция игрока в мировых координатах
     * @param budgetNanos      сколько времени разрешено потратить в этом кадре
     */
    fun update(playerX: Double, playerZ: Double, budgetNanos: Long = DEFAULT_BUDGET_NANOS) {
        if (shuttingDown) return
        val deadline = System.nanoTime() + budgetNanos

        val centre = ChunkPos.ofBlock(playerX, playerZ)

        /*
         * Порядок шагов подчинён одному правилу: **пул воркеров не должен
         * простаивать**. Он — единственный параллельный ресурс, и каждая
         * миллисекунда его простоя не возвращается.
         *
         * Поэтому постановка задач идёт первой и имеет собственный небольшой бюджет,
         * не зависящий от общего. Изначально этот шаг стоял последним, и на
         * практике получалось следующее: расчёт освещения выбирал бюджет
         * кадра целиком, до постановки задач управление не доходило, пул
         * опустошался и вся генерация вставала. Симптом — мир подгружается
         * рывками, хотя процессор загружен на четверть.
         */
        requestMissingChunks(centre, System.nanoTime() + REQUEST_BUDGET_NANOS)
        publishGeneratedChunks(deadline)
        processLighting(deadline)
        dispatchMeshJobs(centre, deadline)
        unloadDistantChunks(centre)

        statsPendingGeneration = inFlight.size
        statsPendingMesh = pendingMeshes.size
    }

    /**
     * Переносит готовые чанки из очереди воркеров в мир.
     *
     * Здесь же соседям сообщается, что у них появился новый сосед: пока его
     * не было, грани на общей границе не строились (снимок считал незагруженный
     * чанк непрозрачным). Без пометки соседей на перестройку между чанками
     * остались бы невидимые швы.
     */
    private fun publishGeneratedChunks(deadline: Long) {
        while (System.nanoTime() < deadline) {
            val chunk = generatedChunks.poll() ?: break
            inFlight.remove(chunk.pos.packed)

            if (chunk.unloaded) {
                // Игрок успел уйти, и чанк уже не нужен.
                world.recycleChunk(chunk)
                continue
            }

            world.publishChunk(chunk)
            pendingLighting.add(chunk)
            statsGenerated.incrementAndGet()

            for (face in com.voxelforge.core.block.BlockFace.HORIZONTAL) {
                val neighbour = world.getChunk(chunk.pos.x + face.dx, chunk.pos.z + face.dz)
                    ?: continue
                if (neighbour.state == ChunkState.LIT || neighbour.state == ChunkState.READY) {
                    neighbour.markAllSectionsDirty()
                    enqueueChunkForMeshing(neighbour)
                }
            }
        }
    }

    /**
     * Считает освещение для чанков, дождавшихся публикации.
     *
     * Освещение выполняется здесь, а не в воркере, по единственной причине:
     * волна света переходит границу чанка и пишет в соседей. В воркере это
     * означало бы одновременную запись в опубликованные данные из нескольких
     * потоков — то есть настоящую гонку, а не безобидную.
     */
    private fun processLighting(deadline: Long) {
        var index = 0
        while (index < pendingLighting.size && System.nanoTime() < deadline) {
            val chunk = pendingLighting[index]
            if (chunk.unloaded) {
                pendingLighting.removeAt(index)
                continue
            }

            lightEngine.computeInitialLight(chunk)
            lightEngine.relightBorders(chunk)
            chunk.state = ChunkState.LIT
            chunk.markAllSectionsDirty()
            enqueueChunkForMeshing(chunk)

            pendingLighting.removeAt(index)
        }
    }

    /**
     * Снимает снимки готовых секций и отправляет их воркерам на мешинг.
     *
     * Секция строится только когда все восемь горизонтальных соседей
     * загружены: иначе на границе получилась бы неверная геометрия,
     * которую пришлось бы тут же перестраивать — двойная работа при
     * каждом входе в новую область.
     */
    private fun dispatchMeshJobs(centre: ChunkPos, deadline: Long) {
        if (pendingMeshes.isEmpty()) return

        val iterator = pendingMeshes.iterator()
        var dispatched = 0
        while (iterator.hasNext() && dispatched < MAX_MESH_JOBS_PER_FRAME) {
            if (System.nanoTime() >= deadline) break

            val packed = iterator.next()
            val chunkX = SectionPos(packed).x
            val sectionY = SectionPos(packed).y
            val chunkZ = SectionPos(packed).z

            val chunk = world.getChunk(chunkX, chunkZ)
            if (chunk == null || chunk.unloaded) {
                iterator.remove()
                continue
            }
            if (!world.hasAllNeighbours(chunkX, chunkZ)) {
                // Соседи ещё в пути — вернёмся к этой секции позже.
                continue
            }

            val section = chunk.sections[sectionY]
            if (section == null || section.isEmpty) {
                // Пустая секция геометрии не даёт; сообщаем рендеру,
                // чтобы он освободил прежний буфер, если тот был.
                iterator.remove()
                chunk.clearSectionDirty(sectionY)
                removedSections.add(packed)
                continue
            }

            val snapshot = snapshotPool.obtain()
            if (!snapshot.capture(world, chunk, sectionY)) {
                snapshotPool.release(snapshot)
                iterator.remove()
                chunk.clearSectionDirty(sectionY)
                continue
            }

            val generation = generations.merge(packed, 1) { old, _ -> old + 1 } ?: 1
            iterator.remove()
            chunk.clearSectionDirty(sectionY)
            submitMeshJob(snapshot, chunkX, sectionY, chunkZ, generation)
            dispatched++
        }
    }

    private val removedSections = ArrayList<Long>()

    private fun submitMeshJob(
        snapshot: SectionSnapshot,
        chunkX: Int,
        sectionY: Int,
        chunkZ: Int,
        generation: Int
    ) {
        executor.execute {
            if (shuttingDown) {
                snapshotPool.release(snapshot)
                return@execute
            }
            val mesh = meshPool.obtain()
            mesh.chunkX = chunkX
            mesh.sectionY = sectionY
            mesh.chunkZ = chunkZ
            mesh.generation = generation
            try {
                mesherLocal.get().build(snapshot, mesh)
                completedMeshes.add(mesh)
                statsMeshed.incrementAndGet()
            } catch (t: Throwable) {
                // Сбой мешинга одной секции не должен ронять весь конвейер:
                // худшее последствие — одна недостроенная секция, которая
                // будет перестроена при следующем изменении.
                meshPool.release(mesh)
            } finally {
                snapshotPool.release(snapshot)
            }
        }
    }

    /**
     * Ставит в очередь генерацию недостающих чанков вокруг игрока.
     *
     * Обход идёт по расширяющемуся квадрату от центра, поэтому ближние
     * чанки загружаются первыми. Это заметно важнее, чем кажется: при
     * произвольном порядке игрок стоит в дыре, пока грузятся дальние чанки,
     * которых он даже не увидит.
     */
    private fun requestMissingChunks(centre: ChunkPos, deadline: Long) {
        if (inFlight.size >= MAX_CHUNKS_IN_FLIGHT) return
        val distance = renderDistance

        for (ring in 0..distance) {
            if (System.nanoTime() >= deadline) return
            if (inFlight.size >= MAX_CHUNKS_IN_FLIGHT) return

            // Обходим только периметр кольца: внутренние уже пройдены.
            var dz = -ring
            while (dz <= ring) {
                var dx = -ring
                while (dx <= ring) {
                    val onPerimeter = abs(dx) == ring || abs(dz) == ring
                    if (!onPerimeter) {
                        dx = if (dz == -ring || dz == ring) dx + 1 else ring
                        continue
                    }
                    val cx = centre.x + dx
                    val cz = centre.z + dz
                    val packed = ChunkPos.pack(cx, cz)
                    if (!world.isChunkLoaded(cx, cz) && !inFlight.contains(packed)) {
                        inFlight.add(packed)
                        submitGenerationJob(ChunkPos(cx, cz))
                        if (inFlight.size >= MAX_CHUNKS_IN_FLIGHT) return
                    }
                    dx++
                }
                dz++
            }
        }
    }

    private fun submitGenerationJob(pos: ChunkPos) {
        val chunk = world.allocateChunk(pos)
        chunk.state = ChunkState.GENERATING
        executor.execute {
            if (shuttingDown) return@execute
            try {
                world.generator.generate(chunk)
            } catch (t: Throwable) {
                // Пустой чанк лучше падения: игрок увидит дыру в мире,
                // но игра продолжит работать.
                chunk.state = ChunkState.TERRAIN
            }
            generatedChunks.add(chunk)
        }
    }

    /**
     * Выгружает чанки за пределами радиуса загрузки.
     *
     * Порог выгрузки на два чанка больше радиуса загрузки. Этот запас
     * (гистерезис) обязателен: без него игрок, стоящий ровно на границе,
     * заставлял бы один и тот же чанк грузиться и выгружаться на каждом
     * шаге вперёд-назад, и генерация работала бы вхолостую непрерывно.
     */
    private fun unloadDistantChunks(centre: ChunkPos) {
        val limit = renderDistance + UNLOAD_MARGIN
        var scanned = 0
        val toUnload = ArrayList<ChunkPos>()

        for (chunk in world.loadedChunks()) {
            if (++scanned > MAX_UNLOAD_SCAN) break
            if (chunk.pos.chebyshevDistance(centre) > limit) {
                toUnload.add(chunk.pos)
            }
        }

        for (pos in toUnload) {
            val chunk = world.unloadChunk(pos) ?: continue
            for (sectionY in 0 until WorldConstants.SECTIONS_PER_CHUNK) {
                val packed = SectionPos.pack(pos.x, sectionY, pos.z)
                pendingMeshes.remove(packed)
                generations.remove(packed)
                removedSections.add(packed)
            }
            world.recycleChunk(chunk)
        }
    }

    // ------------------------------------------------------------------
    // Взаимодействие с рендером
    // ------------------------------------------------------------------

    /**
     * Отдаёт готовые меши потребителю (потоку отрисовки).
     *
     * Ограничение по числу за кадр не прихоть: загрузка вершин в буфер GPU
     * может вызвать синхронизацию с драйвером, и десяток загрузок подряд
     * даёт заметный провал кадра. Лучше растянуть появление геометрии
     * на несколько кадров.
     */
    fun drainMeshes(consumer: MeshConsumer, maxUploads: Int = MAX_UPLOADS_PER_FRAME) {
        for (packed in removedSections) {
            val pos = SectionPos(packed)
            consumer.onSectionRemoved(pos.x, pos.y, pos.z)
        }
        removedSections.clear()

        var uploaded = 0
        while (uploaded < maxUploads) {
            val mesh = completedMeshes.poll() ?: break
            val packed = SectionPos.pack(mesh.chunkX, mesh.sectionY, mesh.chunkZ)
            val current = generations[packed]

            // Меш успел устареть: секция изменилась, пока он строился.
            if (current != null && current != mesh.generation) {
                meshPool.release(mesh)
                continue
            }

            if (mesh.isEmpty) {
                consumer.onSectionRemoved(mesh.chunkX, mesh.sectionY, mesh.chunkZ)
            } else {
                consumer.onMeshReady(mesh)
            }
            meshPool.release(mesh)
            uploaded++
        }
    }

    /** Ставит все грязные секции чанка в очередь мешинга. */
    private fun enqueueChunkForMeshing(chunk: Chunk) {
        var mask = chunk.dirtyMask
        while (mask != 0) {
            val sectionY = Integer.numberOfTrailingZeros(mask)
            mask = mask and (mask - 1)
            pendingMeshes.add(SectionPos.pack(chunk.pos.x, sectionY, chunk.pos.z))
        }
    }

    /**
     * Реакция на изменение блока игроком: пересчёт освещения и постановка
     * затронутых секций в очередь. Вызывается из игрового потока.
     */
    fun onBlockChanged(x: Int, y: Int, z: Int, oldId: Int, newId: Int) {
        lightEngine.onBlockChanged(x, y, z, oldId, newId)

        // Освещение могло затронуть соседние чанки — собираем все помеченные.
        val cx = x shr WorldConstants.SECTION_SHIFT
        val cz = z shr WorldConstants.SECTION_SHIFT
        for (dz in -1..1) {
            for (dx in -1..1) {
                val chunk = world.getChunk(cx + dx, cz + dz) ?: continue
                if (chunk.hasDirtySections) enqueueChunkForMeshing(chunk)
            }
        }
    }

    /** Принудительно перестроить всё — после смены настроек графики. */
    fun rebuildAll() {
        for (chunk in world.loadedChunks()) {
            chunk.markAllSectionsDirty()
            enqueueChunkForMeshing(chunk)
        }
    }

    /**
     * Ждёт, пока вокруг игрока не появится минимальный набор чанков.
     * Используется на экране загрузки: без этого игрок появляется в воздухе
     * над пустотой и падает сквозь ещё не сгенерированный мир.
     */
    fun waitForInitialChunks(playerX: Double, playerZ: Double, radius: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val centre = ChunkPos.ofBlock(playerX, playerZ)
        while (System.currentTimeMillis() < deadline) {
            update(playerX, playerZ, budgetNanos = 8_000_000L)
            var ready = true
            outer@ for (dz in -radius..radius) {
                for (dx in -radius..radius) {
                    val chunk = world.getChunk(centre.x + dx, centre.z + dz)
                    if (chunk == null || chunk.state == ChunkState.EMPTY ||
                        chunk.state == ChunkState.GENERATING
                    ) {
                        ready = false
                        break@outer
                    }
                }
            }
            if (ready) return true
            Thread.sleep(2)
        }
        return false
    }

    /**
     * Есть ли незавершённая работа в конвейере.
     *
     * Учитываются генерация, освещение и меши, ожидающие загрузки в GPU.
     * Очередь мешинга сознательно **не** учитывается: секции на самой кромке
     * загруженной области ждут появления соседей, которых при текущем радиусе
     * прорисовки не будет никогда, поэтому эта очередь никогда не пустеет.
     * Использовать её как признак завершения — значит ждать вечно.
     *
     * Применяется экраном загрузки и тестами конвейера.
     */
    fun hasPendingWork(): Boolean =
        inFlight.isNotEmpty() ||
            pendingLighting.isNotEmpty() ||
            generatedChunks.isNotEmpty() ||
            completedMeshes.isNotEmpty()

    fun shutdown() {
        shuttingDown = true
        executor.shutdown()
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        generatedChunks.clear()
        completedMeshes.clear()
        snapshotPool.clear()
        meshPool.clear()
    }

    /** Строка статистики для отладочной панели. */
    fun statsLine(): String =
        "чанков ${world.loadedChunkCount} | в очереди ген. $statsPendingGeneration | " +
            "мешей в очереди $statsPendingMesh | всего ${statsGenerated.get()}/${statsMeshed.get()}"

    /** Именованные потоки с пониженным приоритетом — чтобы не вытеснять отрисовку. */
    private class WorkerThreadFactory : ThreadFactory {
        private val counter = AtomicInteger(1)
        override fun newThread(r: Runnable): Thread {
            val t = Thread(r, "voxel-worker-${counter.getAndIncrement()}")
            t.isDaemon = true
            t.priority = Thread.NORM_PRIORITY - 1
            return t
        }
    }

    companion object {
        const val DEFAULT_RENDER_DISTANCE = 8
        const val MIN_RENDER_DISTANCE = 3
        const val MAX_RENDER_DISTANCE = 16

        /** Запас в чанках между радиусом загрузки и порогом выгрузки. */
        private const val UNLOAD_MARGIN = 2

        /** Бюджет игрового потока на конвейер: около четверти кадра при 60 к/с. */
        private const val DEFAULT_BUDGET_NANOS = 4_000_000L

        /**
         * Отдельный бюджет на постановку задач генерации. Шаг дёшев
         * (вставка в множество и передача задачи в пул), но обязан
         * выполняться каждый кадр, иначе воркеры остаются без работы.
         */
        private const val REQUEST_BUDGET_NANOS = 700_000L

        /**
         * Ограничение числа одновременно генерируемых чанков. Без него при
         * первом входе в мир в очередь разом улетают все 625 чанков, пул
         * забивается, и ближние чанки ждут за дальними.
         */
        private const val MAX_CHUNKS_IN_FLIGHT = 24

        private const val MAX_MESH_JOBS_PER_FRAME = 12
        private const val MAX_UPLOADS_PER_FRAME = 6
        private const val MAX_UNLOAD_SCAN = 2048

        private const val SNAPSHOT_POOL_SIZE = 32
        private const val MESH_POOL_SIZE = 48
    }
}
