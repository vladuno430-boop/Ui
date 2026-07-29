package com.voxelforge.app.gl

import android.opengl.GLES30
import com.voxelforge.core.block.Tiles

/**
 * Текстурный массив блоков (GL_TEXTURE_2D_ARRAY).
 *
 * ## Почему массив, а не атлас-сетка
 *
 * Это решение определяет всю схему мешинга, поэтому стоит объяснения.
 *
 * Жадный мешер объединяет соседние одинаковые грани в один прямоугольник
 * размером до 16×16 блоков. Такому прямоугольнику нужны текстурные
 * координаты, выходящие за пределы одного тайла: 16 повторов узора.
 * В атласе-сетке это невозможно — координата 1.5 попадёт на соседний тайл,
 * и на камне проступит кусок песка. Обходные приёмы (ручное размножение
 * геометрии, поля-разделители) либо сводят на нет весь выигрыш от
 * объединения, либо ломаются при мипмаппинге.
 *
 * В текстурном массиве номер слоя — **третья координата**, независимая от
 * первых двух. Режим GL_REPEAT работает внутри слоя штатно, координаты
 * могут быть любыми, а «протекание» соседних тайлов при уменьшении
 * физически невозможно: соседей в плоскости выборки просто нет.
 *
 * ## О фильтрации
 *
 * Увеличение — GL_NEAREST: воксельный стиль требует резких пикселей,
 * линейная фильтрация превратила бы текстуры в мыло.
 *
 * Уменьшение — GL_NEAREST_MIPMAP_LINEAR: без мип-уровней дальние блоки
 * дают жёсткий алиасинг («кипение» текстур при движении камеры), который
 * на маленьком экране особенно заметен. Ближайшая выборка внутри уровня
 * сохраняет пиксельность, а линейная интерполяция *между* уровнями убирает
 * видимую границу их смены.
 */
class TextureArray private constructor(val handle: Int) {

    /** Привязывает текстуру к указанному текстурному блоку. */
    fun bind(unit: Int = 0) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, handle)
    }

    fun dispose() {
        if (handle != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(handle), 0)
        }
    }

    companion object {

        /**
         * Создаёт текстурный массив и заполняет его процедурными текстурами.
         *
         * @param anisotropy запрошенный уровень анизотропной фильтрации;
         *                   1 означает «выключена». Расширение доступно
         *                   не на всех устройствах, поэтому его отсутствие
         *                   не считается ошибкой
         */
        fun createBlockAtlas(anisotropy: Float = 1f): TextureArray {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            val handle = ids[0]
            check(handle != 0) { "Не удалось создать текстуру" }

            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, handle)

            // Неизменяемое хранилище: драйвер сразу знает окончательный размер
            // и число уровней, поэтому может разместить текстуру оптимально.
            // Уровней ровно столько, сколько нужно, чтобы дойти до 1×1.
            val levels = mipLevelsFor(Tiles.TILE_SIZE)
            GLES30.glTexStorage3D(
                GLES30.GL_TEXTURE_2D_ARRAY,
                levels,
                GLES30.GL_RGBA8,
                Tiles.TILE_SIZE,
                Tiles.TILE_SIZE,
                Tiles.COUNT
            )

            val pixels = ProceduralAtlas.build()
            GLES30.glTexSubImage3D(
                GLES30.GL_TEXTURE_2D_ARRAY,
                0,
                0, 0, 0,
                Tiles.TILE_SIZE,
                Tiles.TILE_SIZE,
                Tiles.COUNT,
                GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE,
                pixels
            )

            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D_ARRAY)

            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D_ARRAY,
                GLES30.GL_TEXTURE_MAG_FILTER,
                GLES30.GL_NEAREST
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D_ARRAY,
                GLES30.GL_TEXTURE_MIN_FILTER,
                GLES30.GL_NEAREST_MIPMAP_LINEAR
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D_ARRAY,
                GLES30.GL_TEXTURE_WRAP_S,
                GLES30.GL_REPEAT
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D_ARRAY,
                GLES30.GL_TEXTURE_WRAP_T,
                GLES30.GL_REPEAT
            )

            if (anisotropy > 1f) {
                applyAnisotropy(anisotropy)
            }

            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, 0)
            return TextureArray(handle)
        }

        /**
         * Анизотропная фильтрация заметно улучшает вид поверхностей,
         * рассматриваемых под острым углом, — а в воксельной игре это весь
         * пол под ногами. Расширение необязательное: если его нет, просто
         * пропускаем. Ошибку GL после запроса параметра нужно сбросить,
         * иначе она всплывёт в следующей проверке и запутает диагностику.
         */
        private fun applyAnisotropy(level: Float) {
            val maxSupported = FloatArray(1)
            GLES30.glGetFloatv(GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT, maxSupported, 0)
            if (GLES30.glGetError() != GLES30.GL_NO_ERROR || maxSupported[0] < 2f) return

            GLES30.glTexParameterf(
                GLES30.GL_TEXTURE_2D_ARRAY,
                GL_TEXTURE_MAX_ANISOTROPY_EXT,
                level.coerceAtMost(maxSupported[0])
            )
            GLES30.glGetError()
        }

        /** Число мип-уровней до размера 1×1 включительно. */
        private fun mipLevelsFor(size: Int): Int {
            var levels = 1
            var s = size
            while (s > 1) {
                s = s shr 1
                levels++
            }
            return levels
        }

        // Константы расширения EXT_texture_filter_anisotropic — в GLES30
        // их нет, поэтому объявлены здесь.
        private const val GL_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FE
        private const val GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FF
    }
}
