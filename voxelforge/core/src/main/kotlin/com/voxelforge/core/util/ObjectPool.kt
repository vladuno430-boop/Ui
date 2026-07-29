package com.voxelforge.core.util

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Потокобезопасный пул переиспользуемых объектов.
 *
 * Назначение: снять давление на сборщик мусора в подсистемах, которые
 * непрерывно создают и выбрасывают крупные объекты, — чанки, буферы мешей,
 * частицы. На Android это не микрооптимизация: пауза сборщика в 8 мс
 * означает пропущенный кадр, а при полёте над миром такие паузы идут пачками.
 *
 * Реализация намеренно простая — очередь без блокировок с ограничением
 * сверху. Верхняя граница обязательна: пул без неё при разовом всплеске
 * (быстрый полёт через сотни чанков) навсегда удержит пиковый объём памяти,
 * то есть превратится из оптимизации в утечку.
 *
 * @param maxSize максимум объектов, удерживаемых пулом
 * @param factory создание нового объекта, когда пул пуст
 * @param reset   приведение объекта в исходное состояние при возврате
 */
class ObjectPool<T : Any>(
    private val maxSize: Int,
    private val factory: () -> T,
    private val reset: (T) -> Unit = {}
) {

    private val items = ConcurrentLinkedQueue<T>()

    /**
     * Размер поддерживается отдельным счётчиком: `ConcurrentLinkedQueue.size()`
     * обходит всю очередь за O(n), а вызывается он на каждом возврате объекта.
     */
    private val count = AtomicInteger(0)

    private val created = AtomicInteger(0)
    private val reused = AtomicInteger(0)

    /** Берёт объект из пула либо создаёт новый. */
    fun obtain(): T {
        val item = items.poll()
        return if (item != null) {
            count.decrementAndGet()
            reused.incrementAndGet()
            item
        } else {
            created.incrementAndGet()
            factory()
        }
    }

    /**
     * Возвращает объект в пул. Если пул полон, объект просто отдаётся
     * сборщику мусора — это дешевле, чем неограниченный рост.
     */
    fun release(item: T) {
        if (count.get() >= maxSize) return
        reset(item)
        items.offer(item)
        count.incrementAndGet()
    }

    /** Освобождает всё содержимое — при выходе из мира. */
    fun clear() {
        items.clear()
        count.set(0)
    }

    val size: Int get() = count.get()

    /**
     * Доля повторных использований. Показатель ниже 0.8 означает, что пул
     * мал для текущей нагрузки и его стоит расширить; выводится в отладочной
     * панели производительности.
     */
    fun reuseRatio(): Float {
        val total = created.get() + reused.get()
        return if (total == 0) 0f else reused.get().toFloat() / total
    }

    override fun toString(): String =
        "ObjectPool(size=$size/$maxSize, created=${created.get()}, reused=${reused.get()})"
}
