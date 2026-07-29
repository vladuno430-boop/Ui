package com.voxelforge.core.mesh

import com.voxelforge.core.block.BlockFace
import com.voxelforge.core.block.Blocks
import com.voxelforge.core.world.WorldConstants.MAX_LIGHT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Проверки жадного мешера.
 *
 * Главный из них — [все грани смотрят наружу]. Вывернутая грань не падает
 * и не выдаёт исключения: она просто отбрасывается отсечением задних граней,
 * и в мире появляется дыра, видимая лишь под определённым углом. Найти такое
 * по симптомам почти невозможно, а проверить арифметически — тривиально,
 * поэтому тест окупается многократно.
 */
class GreedyMesherTest {

    /**
     * Собирает снимок секции вручную, без мира: мешер зависит только
     * от интерфейса данных, что и позволяет тестировать его изолированно.
     */
    private fun snapshot(fill: (x: Int, y: Int, z: Int) -> Int): SectionSnapshot {
        val s = SectionSnapshot()
        s.hasContent = true
        java.util.Arrays.fill(s.light, (MAX_LIGHT shl 4).toByte())
        for (y in -1..16) {
            for (z in -1..16) {
                for (x in -1..16) {
                    s.blocks[SectionSnapshot.index(x, y, z)] = fill(x, y, z).toByte()
                }
            }
        }
        return s
    }

    private fun mesh(snapshot: SectionSnapshot): SectionMesh {
        val out = SectionMesh()
        GreedyMesher().build(snapshot, out)
        return out
    }

    /** Разбирает буфер на четвёрки вершин с распакованными полями. */
    private data class Quad(
        val positions: Array<Triple<Int, Int, Int>>,
        val face: Int,
        val ao: IntArray
    )

    private fun quadsOf(buffer: MeshBuffer): List<Quad> {
        val result = ArrayList<Quad>()
        for (q in 0 until buffer.vertexCount / 4) {
            val positions = Array(4) { i ->
                val w0 = buffer.vertices[(q * 4 + i) * 2]
                Triple(
                    VertexFormat.unpackX(w0),
                    VertexFormat.unpackY(w0),
                    VertexFormat.unpackZ(w0)
                )
            }
            val face = VertexFormat.unpackFace(buffer.vertices[q * 4 * 2])
            val ao = IntArray(4) { i ->
                VertexFormat.unpackAo(buffer.vertices[(q * 4 + i) * 2])
            }
            result.add(Quad(positions, face, ao))
        }
        return result
    }

    @Test
    fun `сплошная секция даёт ровно шесть граней`() {
        // Куб камня 16³ в пустоте: жадный алгоритм обязан свести каждую
        // сторону к одному прямоугольнику 16×16. Если граней окажется 1536,
        // объединение не работает вовсе; если 6 — работает идеально.
        val s = snapshot { x, y, z ->
            if (x in 0..15 && y in 0..15 && z in 0..15) Blocks.STONE else Blocks.AIR
        }
        val m = mesh(s)
        assertEquals(6, m.opaque.quadCount, "Ожидалось 6 объединённых граней")
        assertTrue(m.cutout.isEmpty && m.translucent.isEmpty)
    }

    @Test
    fun `все грани смотрят наружу`() {
        // Строим рельеф со ступеньками, чтобы задействовать все шесть
        // направлений и объединение прямоугольников разного размера.
        val s = snapshot { x, y, z ->
            if (x !in 0..15 || y !in 0..15 || z !in 0..15) {
                Blocks.AIR
            } else if (y < 4 + (x / 4) + (z / 8)) {
                Blocks.STONE
            } else {
                Blocks.AIR
            }
        }
        val m = mesh(s)
        assertTrue(m.opaque.quadCount > 0, "Меш пуст — проверять нечего")

        for (quad in quadsOf(m.opaque)) {
            val expected = BlockFace.byIndex(quad.face)

            // Нормаль по правилу правой руки из двух рёбер квада.
            val (x0, y0, z0) = quad.positions[0]
            val (x1, y1, z1) = quad.positions[1]
            val (x2, y2, z2) = quad.positions[2]

            val e1x = x1 - x0; val e1y = y1 - y0; val e1z = z1 - z0
            val e2x = x2 - x0; val e2y = y2 - y0; val e2z = z2 - z0

            val nx = e1y * e2z - e1z * e2y
            val ny = e1z * e2x - e1x * e2z
            val nz = e1x * e2y - e1y * e2x

            // Знак каждой компоненты обязан совпасть с направлением грани.
            assertEquals(
                expected.dx.sign(), nx.sign(),
                "Грань $expected: X-компонента нормали $nx"
            )
            assertEquals(
                expected.dy.sign(), ny.sign(),
                "Грань $expected: Y-компонента нормали $ny"
            )
            assertEquals(
                expected.dz.sign(), nz.sign(),
                "Грань $expected: Z-компонента нормали $nz"
            )
        }
    }

    private fun Int.sign(): Int = if (this > 0) 1 else if (this < 0) -1 else 0

    @Test
    fun `скрытые грани не строятся`() {
        // Секция целиком в толще камня: снаружи тоже камень, значит
        // ни одна грань не видна и меш обязан быть пустым.
        val s = snapshot { _, _, _ -> Blocks.STONE }
        val m = mesh(s)
        assertTrue(m.isEmpty, "Построено ${m.totalQuads} невидимых граней")
    }

    @Test
    fun `плоская поверхность объединяется в один прямоугольник`() {
        // Один слой камня на дне секции. Сверху — одна грань 16×16,
        // снизу — одна, по бокам — четыре полосы 16×1. Итого 6.
        val s = snapshot { x, y, z ->
            if (x in 0..15 && z in 0..15 && y == 0) Blocks.STONE else Blocks.AIR
        }
        val m = mesh(s)
        assertEquals(6, m.opaque.quadCount)
    }

    @Test
    fun `разная освещённость препятствует объединению`() {
        // Освещение входит в дескриптор грани, поэтому две половины
        // площадки с разным светом объединиться не могут. Если бы могли,
        // тень «размазалась» бы по всей площадке.
        val s = snapshot { x, y, z ->
            if (x in 0..15 && z in 0..15 && y == 0) Blocks.STONE else Blocks.AIR
        }
        // Гасим свет над правой половиной.
        for (z in 0..15) {
            for (x in 8..15) {
                s.light[SectionSnapshot.index(x, 1, z)] = 0
            }
        }
        val m = mesh(s)
        val topQuads = quadsOf(m.opaque).filter { it.face == BlockFace.UP.index }
        assertEquals(2, topQuads.size, "Верхняя грань должна разделиться надвое")
    }

    @Test
    fun `Ambient Occlusion темнеет во внутреннем углу`() {
        // Ступенька: рядом с вертикальной стенкой угол верхней грани
        // обязан получить пониженное значение AO. Иначе затенение
        // не работает и мир выглядит плоским.
        val s = snapshot { x, y, z ->
            when {
                x !in 0..15 || y !in 0..15 || z !in 0..15 -> Blocks.AIR
                y == 0 -> Blocks.STONE            // пол
                x <= 7 && y <= 5 -> Blocks.STONE  // стенка слева
                else -> Blocks.AIR
            }
        }
        val m = mesh(s)
        val topQuads = quadsOf(m.opaque).filter { it.face == BlockFace.UP.index }

        val allAo = topQuads.flatMap { it.ao.toList() }
        assertTrue(allAo.any { it < 3 }, "Затенения нет вовсе — AO не работает")
        assertTrue(allAo.any { it == 3 }, "Затенено всё — AO пересвечен")
    }

    @Test
    fun `вода попадает в полупрозрачный проход`() {
        val s = snapshot { x, y, z ->
            if (x in 0..15 && z in 0..15 && y in 0..7) Blocks.WATER else Blocks.AIR
        }
        val m = mesh(s)
        assertTrue(m.opaque.isEmpty, "Вода не должна попадать в непрозрачный проход")
        assertTrue(!m.translucent.isEmpty, "Вода не построена")
    }

    @Test
    fun `поверхность воды опущена ниже полного блока`() {
        val s = snapshot { x, y, z ->
            if (x in 0..15 && z in 0..15 && y in 0..7) Blocks.WATER else Blocks.AIR
        }
        val m = mesh(s)
        val top = quadsOf(m.translucent).first { it.face == BlockFace.UP.index }
        val y = top.positions[0].second
        // Верх восьмого блока — 8 × 16 = 128 шестнадцатых; ожидаем на 2 ниже.
        assertEquals(126, y, "Поверхность воды не опущена")
    }

    @Test
    fun `трава строится крестиком и видна с обеих сторон`() {
        val s = snapshot { x, y, z ->
            when {
                x !in 0..15 || y !in 0..15 || z !in 0..15 -> Blocks.AIR
                y == 0 -> Blocks.GRASS_BLOCK
                y == 1 && x == 8 && z == 8 -> Blocks.TALL_GRASS
                else -> Blocks.AIR
            }
        }
        val m = mesh(s)
        // Две плоскости, каждая с обеих сторон — четыре четырёхугольника.
        assertEquals(4, m.cutout.quadCount)
    }

    @Test
    fun `смыкающаяся листва не строит внутренних граней`() {
        // Сплошной куб листвы 8³. Внутренние грани между одинаковыми
        // блоками листвы отсекаются, поэтому остаются только внешние 6.
        val s = snapshot { x, y, z ->
            if (x in 4..11 && y in 4..11 && z in 4..11) Blocks.LEAVES else Blocks.AIR
        }
        val m = mesh(s)
        assertEquals(6, m.cutout.quadCount, "Внутренние грани листвы не отсечены")
    }

    @Test
    fun `объединение действительно сокращает геометрию`() {
        // Сравниваем с теоретическим числом граней без объединения.
        val s = snapshot { x, y, z ->
            if (x !in 0..15 || y !in 0..15 || z !in 0..15) Blocks.AIR
            else if (y < 8) Blocks.STONE else Blocks.AIR
        }
        val m = mesh(s)
        // Без объединения: верх 256 + низ 0 (снизу камень каймы… здесь воздух,
        // поэтому 256) + четыре стороны по 16×8 = 512. Итого 1024.
        val naive = 256 + 256 + 4 * 16 * 8
        assertTrue(
            m.opaque.quadCount * 8 < naive,
            "Объединение неэффективно: ${m.opaque.quadCount} против $naive без него"
        )
    }
}
