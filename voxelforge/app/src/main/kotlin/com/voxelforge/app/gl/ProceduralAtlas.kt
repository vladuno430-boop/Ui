package com.voxelforge.app.gl

import com.voxelforge.core.block.Tiles
import com.voxelforge.core.noise.XorShiftRandom
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

/**
 * Процедурный генератор текстур блоков.
 *
 * ## Зачем рисовать текстуры кодом
 *
 * Требование задачи — не использовать чужие ресурсы. Но процедурная
 * генерация здесь не только формальность, у неё три практических следствия:
 *
 *  1. В репозитории нет ни одного бинарного файла изображений — весь
 *     внешний вид игры описан кодом, который читается и правится.
 *  2. Размер APK не растёт: 32 текстуры 16×16 заняли бы около 30 КБ
 *     в PNG, а генератор — несколько килобайт кода, который к тому же
 *     позволяет менять палитру одной строкой.
 *  3. Текстуры генерируются под конкретный размер тайла. Захотим 32×32 —
 *     достаточно изменить константу, и все текстуры станут вчетверо
 *     детальнее без перерисовки.
 *
 * ## Как достигается «воксельный» вид
 *
 * Каждый тайл — это базовый цвет плюс попиксельный шум небольшой амплитуды.
 * Ключевой момент: **шум берётся из детерминированного генератора, засеянного
 * номером тайла**, поэтому текстура одинакова при каждом запуске. Случайный
 * шум при каждом старте выглядел бы как «шевеление» текстур между сессиями.
 *
 * Поверх шума накладываются узнаваемые узоры: кладка у кирпича, годовые
 * кольца у среза бревна, полосы у досок, рамка у стекла. Именно узор,
 * а не цвет, делает блок опознаваемым с расстояния.
 *
 * Результат — плотный буфер RGBA для загрузки в GL_TEXTURE_2D_ARRAY
 * одним вызовом.
 */
object ProceduralAtlas {

    private const val SIZE = Tiles.TILE_SIZE
    private const val PIXELS_PER_TILE = SIZE * SIZE
    private const val BYTES_PER_PIXEL = 4

    /**
     * Строит буфер со всеми слоями текстурного массива.
     *
     * Порядок слоёв соответствует константам [Tiles]: слой N содержит
     * текстуру Tiles.N. Буфер прямой (direct), иначе драйверу GL пришлось бы
     * копировать его из кучи JVM при загрузке.
     */
    fun build(): ByteBuffer {
        val buffer = ByteBuffer
            .allocateDirect(Tiles.COUNT * PIXELS_PER_TILE * BYTES_PER_PIXEL)
            .order(ByteOrder.nativeOrder())

        val pixels = IntArray(PIXELS_PER_TILE)
        for (layer in 0 until Tiles.COUNT) {
            paintTile(layer, pixels)
            for (p in pixels) {
                // Порядок компонентов — RGBA, как ожидает GL_RGBA8.
                buffer.put(((p shr 16) and 0xFF).toByte())  // R
                buffer.put(((p shr 8) and 0xFF).toByte())   // G
                buffer.put((p and 0xFF).toByte())           // B
                buffer.put(((p ushr 24) and 0xFF).toByte()) // A
            }
        }
        buffer.position(0)
        return buffer
    }

    /** Рисует один тайл в переданный массив пикселей формата ARGB. */
    private fun paintTile(layer: Int, out: IntArray) {
        // Сид привязан к номеру слоя: текстура воспроизводима между запусками.
        val rng = XorShiftRandom(0x7E1A_0000L + layer)

        when (layer) {
            Tiles.STONE -> speckled(out, rng, 0x7A7A7A, 0.10f)
            Tiles.DIRT -> speckled(out, rng, 0x8B6141, 0.13f)
            Tiles.GRASS_TOP -> speckled(out, rng, 0x6EA24A, 0.14f)
            Tiles.GRASS_SIDE -> grassSide(out, rng)
            Tiles.COBBLESTONE -> cobblestone(out, rng)
            Tiles.SAND -> speckled(out, rng, 0xDBCB92, 0.09f)
            Tiles.GRAVEL -> gravel(out, rng)
            Tiles.LOG_SIDE -> logSide(out, rng)
            Tiles.LOG_TOP -> logTop(out, rng)
            Tiles.LEAVES -> leaves(out, rng)
            Tiles.GLASS -> glass(out)
            Tiles.WATER -> water(out, rng)
            Tiles.LAVA -> lava(out, rng)
            Tiles.TORCH -> torch(out)
            Tiles.PLANKS -> planks(out, rng)
            Tiles.BRICKS -> bricks(out, rng)
            Tiles.BEDROCK -> bedrock(out, rng)
            Tiles.SANDSTONE_TOP -> speckled(out, rng, 0xD6C48C, 0.06f)
            Tiles.SANDSTONE_SIDE -> sandstoneSide(out, rng)
            Tiles.SNOW -> speckled(out, rng, 0xF2F6FA, 0.045f)
            Tiles.ICE -> ice(out, rng)
            Tiles.CRAFTING_TOP -> craftingTop(out, rng)
            Tiles.CRAFTING_SIDE -> craftingSide(out, rng)
            Tiles.COAL_ORE -> ore(out, rng, 0x7A7A7A, 0x1E1E1E)
            Tiles.IRON_ORE -> ore(out, rng, 0x7A7A7A, 0xC9A187)
            Tiles.CACTUS_TOP -> speckled(out, rng, 0x4E7C36, 0.09f)
            Tiles.CACTUS_SIDE -> cactusSide(out, rng)
            Tiles.TALL_GRASS -> tallGrass(out, rng)
            Tiles.CLAY -> speckled(out, rng, 0xA0A6B0, 0.07f)
            Tiles.SNOW_SIDE -> snowSide(out, rng)
            Tiles.ICON_STICK -> stickIcon(out)
            Tiles.ICON_COAL -> coalIcon(out, rng)
            else -> speckled(out, rng, 0xFF00FF, 0f)   // заметная заглушка
        }
    }

    // ------------------------------------------------------------------
    // Базовые приёмы
    // ------------------------------------------------------------------

    /**
     * Базовый цвет с попиксельным разбросом яркости.
     *
     * Разброс — главный приём всей палитры. Плоская заливка выглядит
     * пластиковой: глаз распознаёт её как отсутствие материала. Даже
     * десятипроцентный шум превращает её в «камень» или «землю».
     */
    private fun speckled(out: IntArray, rng: XorShiftRandom, base: Int, amount: Float) {
        for (i in out.indices) {
            out[i] = shade(base, 1f + (rng.nextFloat() - 0.5f) * 2f * amount)
        }
    }

    /** Умножает яркость цвета, сохраняя оттенок. */
    private fun shade(color: Int, factor: Float): Int {
        val r = clamp255((((color shr 16) and 0xFF) * factor).toInt())
        val g = clamp255((((color shr 8) and 0xFF) * factor).toInt())
        val b = clamp255(((color and 0xFF) * factor).toInt())
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun clamp255(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    private fun index(x: Int, y: Int): Int = y * SIZE + x

    private fun fill(out: IntArray, color: Int) {
        java.util.Arrays.fill(out, color or (0xFF shl 24))
    }

    // ------------------------------------------------------------------
    // Конкретные материалы
    // ------------------------------------------------------------------

    /**
     * Бок травяного блока: земля снизу, дёрн сверху с рваной границей.
     * Рваная граница обязательна — прямая линия сразу выдаёт процедурность
     * и выглядит как ошибка отрисовки.
     */
    private fun grassSide(out: IntArray, rng: XorShiftRandom) {
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                out[index(x, y)] = shade(0x8B6141, 1f + (rng.nextFloat() - 0.5f) * 0.26f)
            }
        }
        for (x in 0 until SIZE) {
            val edge = 3 + rng.nextInt(3)
            for (y in 0 until edge) {
                out[index(x, y)] = shade(0x6EA24A, 1f + (rng.nextFloat() - 0.5f) * 0.28f)
            }
            // Отдельные травинки, свисающие ниже основной кромки.
            if (rng.chance(0.35f)) {
                out[index(x, edge)] = shade(0x5F8F40, 1f + (rng.nextFloat() - 0.5f) * 0.2f)
            }
        }
    }

    private fun snowSide(out: IntArray, rng: XorShiftRandom) {
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                out[index(x, y)] = shade(0x8B6141, 1f + (rng.nextFloat() - 0.5f) * 0.24f)
            }
        }
        for (x in 0 until SIZE) {
            val edge = 4 + rng.nextInt(3)
            for (y in 0 until edge) {
                out[index(x, y)] = shade(0xF2F6FA, 1f + (rng.nextFloat() - 0.5f) * 0.08f)
            }
        }
    }

    /** Булыжник: крупные округлые камни со швами. */
    private fun cobblestone(out: IntArray, rng: XorShiftRandom) {
        fill(out, 0x5B5B5B)
        // Разбиваем тайл на несколько «камней» разного тона.
        val cells = 4
        val cell = SIZE / cells
        for (cy in 0 until cells) {
            for (cx in 0 until cells) {
                val tone = 0.78f + rng.nextFloat() * 0.42f
                val ox = cx * cell + rng.nextInt(2)
                val oy = cy * cell + rng.nextInt(2)
                val w = cell - 1 + rng.nextInt(2)
                val h = cell - 1 + rng.nextInt(2)
                for (y in oy until min(oy + h, SIZE)) {
                    for (x in ox until min(ox + w, SIZE)) {
                        out[index(x, y)] = shade(0x8A8A8A, tone + (rng.nextFloat() - 0.5f) * 0.14f)
                    }
                }
            }
        }
    }

    /** Гравий: мелкие камешки разного тона, без выраженной структуры. */
    private fun gravel(out: IntArray, rng: XorShiftRandom) {
        for (i in out.indices) {
            val tone = if (rng.chance(0.28f)) 0.72f else 1.05f
            out[i] = shade(0x8A7F73, tone + (rng.nextFloat() - 0.5f) * 0.22f)
        }
    }

    /** Бок бревна: вертикальные волокна коры. */
    private fun logSide(out: IntArray, rng: XorShiftRandom) {
        for (x in 0 until SIZE) {
            val columnTone = 0.86f + rng.nextFloat() * 0.28f
            for (y in 0 until SIZE) {
                out[index(x, y)] = shade(0x6B4E2E, columnTone + (rng.nextFloat() - 0.5f) * 0.12f)
            }
        }
    }

    /** Срез бревна: годовые кольца от центра. */
    private fun logTop(out: IntArray, rng: XorShiftRandom) {
        val centre = (SIZE - 1) / 2f
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val dx = x - centre
                val dy = y - centre
                val r = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                // Кольца — периодическая функция расстояния до центра.
                val ring = if ((r.toInt() % 3) == 0) 0.82f else 1.04f
                out[index(x, y)] = shade(0xA9834E, ring + (rng.nextFloat() - 0.5f) * 0.1f)
            }
        }
    }

    /**
     * Листва: полупрозрачные просветы.
     *
     * Дырки принципиальны. Сплошная крона выглядит как зелёный куб; просветы
     * дают силуэт, который читается как листва даже без детализации. Именно
     * ради них листва рисуется в проходе с отбрасыванием пикселей по альфе.
     */
    private fun leaves(out: IntArray, rng: XorShiftRandom) {
        for (i in out.indices) {
            if (rng.chance(0.18f)) {
                out[i] = 0   // полностью прозрачный пиксель
            } else {
                val tone = 0.72f + rng.nextFloat() * 0.5f
                out[i] = shade(0x4C8A32, tone)
            }
        }
    }

    /** Стекло: почти прозрачное поле с бликом и рамкой. */
    private fun glass(out: IntArray) {
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val border = x == 0 || y == 0 || x == SIZE - 1 || y == SIZE - 1
                out[index(x, y)] = if (border) {
                    (0xC0 shl 24) or 0xD8E6F0
                } else {
                    (0x22 shl 24) or 0xCFE4F2
                }
            }
        }
        // Диагональный блик — без него стекло неотличимо от пустоты.
        for (i in 2 until 7) {
            out[index(i, i + 1)] = (0x80 shl 24) or 0xFFFFFF
            out[index(i + 1, i)] = (0x50 shl 24) or 0xFFFFFF
        }
    }

    /** Вода: полупрозрачная синева с лёгкой рябью. */
    private fun water(out: IntArray, rng: XorShiftRandom) {
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val ripple = Math.sin((x * 0.9 + y * 0.5)).toFloat() * 0.06f
                val c = shade(0x2C6CB5, 1f + ripple + (rng.nextFloat() - 0.5f) * 0.07f)
                out[index(x, y)] = (0xB0 shl 24) or (c and 0xFFFFFF)
            }
        }
    }

    /** Лава: раскалённые прожилки поверх тёмной корки. */
    private fun lava(out: IntArray, rng: XorShiftRandom) {
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val vein = Math.sin(x * 0.7 + Math.sin(y * 0.55) * 2.0).toFloat()
                val hot = vein > 0.35f
                val base = if (hot) 0xFFB23C else 0xD1521A
                out[index(x, y)] = shade(base, 0.85f + rng.nextFloat() * 0.35f)
            }
        }
    }

    /** Факел: деревянная ручка снизу и пламя сверху. */
    private fun torch(out: IntArray) {
        java.util.Arrays.fill(out, 0)
        val cx = SIZE / 2
        // Ручка занимает нижние две трети.
        for (y in 6 until SIZE) {
            for (x in cx - 1..cx) {
                out[index(x, y)] = shade(0x6B4E2E, if (x == cx) 1.0f else 0.8f)
            }
        }
        // Пламя: жёлтое ядро в оранжевой оболочке.
        for (y in 2 until 6) {
            for (x in cx - 2..cx + 1) {
                val core = (x == cx - 1 || x == cx) && y >= 3
                out[index(x, y)] = if (core) {
                    (0xFF shl 24) or 0xFFE066
                } else {
                    (0xFF shl 24) or 0xF08A24
                }
            }
        }
    }

    /** Доски: горизонтальные ламели с тёмными швами. */
    private fun planks(out: IntArray, rng: XorShiftRandom) {
        val plankHeight = 4
        for (y in 0 until SIZE) {
            val seam = y % plankHeight == 0
            val plankTone = 0.9f + ((y / plankHeight) % 3) * 0.07f
            for (x in 0 until SIZE) {
                out[index(x, y)] = if (seam) {
                    shade(0x6E5330, 0.72f)
                } else {
                    shade(0xB08248, plankTone + (rng.nextFloat() - 0.5f) * 0.1f)
                }
            }
        }
        // Вертикальные стыки между досками в ряду — иначе видны сплошные полосы.
        for (row in 0 until SIZE / plankHeight) {
            val jointX = rng.nextInt(SIZE)
            for (y in row * plankHeight + 1 until (row + 1) * plankHeight) {
                if (y < SIZE) out[index(jointX, y)] = shade(0x6E5330, 0.8f)
            }
        }
    }

    /** Кирпичная кладка со смещением рядов. */
    private fun bricks(out: IntArray, rng: XorShiftRandom) {
        val mortar = shade(0xC9BFB2, 1f)
        val brickHeight = 4
        val brickWidth = 8
        for (y in 0 until SIZE) {
            val row = y / brickHeight
            // Смещение каждого второго ряда на полкирпича — то, что делает
            // узор кладкой, а не сеткой.
            val offset = if (row % 2 == 0) 0 else brickWidth / 2
            for (x in 0 until SIZE) {
                val horizontalSeam = y % brickHeight == 0
                val verticalSeam = ((x + offset) % brickWidth) == 0
                out[index(x, y)] = if (horizontalSeam || verticalSeam) {
                    mortar
                } else {
                    shade(0x9C4B36, 0.9f + rng.nextFloat() * 0.22f)
                }
            }
        }
    }

    /** Коренная порода: резкий контраст, читается как «дальше хода нет». */
    private fun bedrock(out: IntArray, rng: XorShiftRandom) {
        for (i in out.indices) {
            val tone = if (rng.chance(0.4f)) 0.45f else 1.0f
            out[i] = shade(0x4A4A4A, tone + (rng.nextFloat() - 0.5f) * 0.3f)
        }
    }

    /** Бок песчаника: горизонтальная слоистость осадочной породы. */
    private fun sandstoneSide(out: IntArray, rng: XorShiftRandom) {
        for (y in 0 until SIZE) {
            val layerTone = 0.88f + ((y / 3) % 3) * 0.06f
            for (x in 0 until SIZE) {
                out[index(x, y)] = shade(0xD6C48C, layerTone + (rng.nextFloat() - 0.5f) * 0.06f)
            }
        }
        for (x in 0 until SIZE) {
            out[index(x, 4)] = shade(0xC0AE78, 0.94f)
            out[index(x, 11)] = shade(0xC0AE78, 0.94f)
        }
    }

    /** Лёд: голубоватый полупрозрачный с трещинами. */
    private fun ice(out: IntArray, rng: XorShiftRandom) {
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val c = shade(0xA8CFEA, 0.94f + rng.nextFloat() * 0.14f)
                out[index(x, y)] = (0xCC shl 24) or (c and 0xFFFFFF)
            }
        }
        // Пара трещин по диагонали.
        var cx = rng.nextInt(SIZE)
        for (y in 0 until SIZE) {
            out[index(cx.coerceIn(0, SIZE - 1), y)] = (0xE0 shl 24) or 0xD6EEFB
            cx += rng.nextInt(3) - 1
        }
    }

    /** Верх верстака: сетка инструментов. */
    private fun craftingTop(out: IntArray, rng: XorShiftRandom) {
        planks(out, rng)
        // Тёмная сетка 3×3 — сразу читаемый признак верстака.
        for (i in 0 until SIZE) {
            out[index(i, 5)] = shade(0x4A3620, 1f)
            out[index(i, 10)] = shade(0x4A3620, 1f)
            out[index(5, i)] = shade(0x4A3620, 1f)
            out[index(10, i)] = shade(0x4A3620, 1f)
        }
    }

    /** Бок верстака: доски с инструментальной панелью. */
    private fun craftingSide(out: IntArray, rng: XorShiftRandom) {
        planks(out, rng)
        for (x in 2 until SIZE - 2) {
            out[index(x, 4)] = shade(0x4A3620, 1f)
            out[index(x, 12)] = shade(0x4A3620, 1f)
        }
        for (y in 5 until 12) {
            out[index(3, y)] = shade(0x8A6A3A, 1f)
            out[index(SIZE - 4, y)] = shade(0x8A6A3A, 1f)
        }
    }

    /** Руда: вкрапления в каменной основе. */
    private fun ore(out: IntArray, rng: XorShiftRandom, stone: Int, oreColor: Int) {
        speckled(out, rng, stone, 0.1f)
        // Несколько компактных вкраплений вместо равномерной россыпи:
        // так руда читается как жила, а не как шум другого цвета.
        repeat(4) {
            val ox = 2 + rng.nextInt(SIZE - 5)
            val oy = 2 + rng.nextInt(SIZE - 5)
            val size = 2 + rng.nextInt(2)
            for (y in oy until min(oy + size, SIZE)) {
                for (x in ox until min(ox + size, SIZE)) {
                    if (rng.chance(0.82f)) {
                        out[index(x, y)] = shade(oreColor, 0.85f + rng.nextFloat() * 0.35f)
                    }
                }
            }
        }
    }

    /** Бок кактуса: вертикальные рёбра с колючками. */
    private fun cactusSide(out: IntArray, rng: XorShiftRandom) {
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                // Рёбра — периодическое изменение яркости по горизонтали.
                val rib = abs((x % 5) - 2) / 2f
                out[index(x, y)] = shade(0x3F6B2C, 0.82f + rib * 0.3f + (rng.nextFloat() - 0.5f) * 0.08f)
            }
        }
        repeat(6) {
            val x = rng.nextInt(SIZE)
            val y = rng.nextInt(SIZE)
            out[index(x, y)] = shade(0xD8DCC0, 1f)
        }
    }

    /** Кустик травы: несколько травинок на прозрачном фоне. */
    private fun tallGrass(out: IntArray, rng: XorShiftRandom) {
        java.util.Arrays.fill(out, 0)
        val blades = 7
        for (b in 0 until blades) {
            var x = 1 + rng.nextInt(SIZE - 2)
            val height = 7 + rng.nextInt(7)
            val tone = 0.8f + rng.nextFloat() * 0.4f
            for (i in 0 until height) {
                val y = SIZE - 1 - i
                if (y < 0) break
                if (x in 0 until SIZE) {
                    out[index(x, y)] = shade(0x5C9E3A, tone)
                }
                // Лёгкий изгиб травинки кверху.
                if (rng.chance(0.28f)) x += if (rng.chance(0.5f)) 1 else -1
            }
        }
    }

    /** Иконка палки для инвентаря. */
    private fun stickIcon(out: IntArray) {
        java.util.Arrays.fill(out, 0)
        for (i in 3 until SIZE - 3) {
            val x = i
            val y = SIZE - 1 - i
            if (x in 0 until SIZE && y in 0 until SIZE) {
                out[index(x, y)] = shade(0x8A6A3A, 1f)
                if (x + 1 < SIZE) out[index(x + 1, y)] = shade(0x6B4E2E, 1f)
            }
        }
    }

    /** Иконка угля для инвентаря. */
    private fun coalIcon(out: IntArray, rng: XorShiftRandom) {
        java.util.Arrays.fill(out, 0)
        val centre = SIZE / 2f
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val dx = x - centre + 0.5f
                val dy = y - centre + 0.5f
                if (dx * dx + dy * dy < 30f) {
                    out[index(x, y)] = shade(0x22242A, 0.7f + rng.nextFloat() * 0.7f)
                }
            }
        }
    }
}
