package com.voxelforge.app.gl

import com.voxelforge.core.mesh.VertexFormat

/**
 * Исходники шейдеров GLSL ES 3.0.
 *
 * ## О связи с [VertexFormat]
 *
 * Вершина упакована в два 32-битных слова, и разбор этой упаковки здесь —
 * зеркальное отражение кода упаковки в :core. Смещения битов подставляются
 * из констант [VertexFormat] через интерполяцию строк, а не пишутся числами.
 * Это не украшательство: рассинхронизация упаковки и распаковки даёт
 * геометрию, разъехавшуюся на случайные величины, — симптом, по которому
 * почти невозможно догадаться о причине. Подстановка констант делает такое
 * расхождение невозможным.
 *
 * ## Об освещении
 *
 * Итоговая яркость грани — произведение трёх независимых множителей:
 *
 *  1. **Освещённость** — максимум из солнечного света, умноженного на время
 *     суток, и света от источников. Именно максимум, а не сумма: факел под
 *     открытым небом не должен пересвечивать поверхность.
 *  2. **Ambient Occlusion** — затенение углов, посчитанное мешером.
 *  3. **Ориентация грани** — грубая имитация направленного света: верх ярче,
 *     низ темнее, стороны между ними. Приём стоит одного чтения из константного
 *     массива и даёт больше объёма, чем полноценное освещение по нормали,
 *     потому что в мире из кубов нормалей всего шесть.
 */
object Shaders {

    /**
     * Вершинный шейдер геометрии чанков.
     *
     * Позиции приходят **относительно камеры**: центр мира для GPU всегда
     * находится в точке наблюдателя. Это устраняет потерю точности float32
     * при удалении игрока от начала координат на миллионы блоков — иначе
     * геометрия начала бы дрожать и разъезжаться по швам.
     */
    val CHUNK_VERTEX = """
        #version 300 es
        precision highp float;
        precision highp int;

        layout(location = 0) in uint aWord0;
        layout(location = 1) in uint aWord1;

        uniform mat4 uViewProj;
        uniform vec3 uSectionOrigin;
        uniform float uDayFactor;
        uniform float uTime;
        uniform vec3 uBiomeTint[8];
        uniform float uFogStart;
        uniform float uFogEnd;

        out vec2 vTexCoord;
        // Номер слоя не интерполируется: он одинаков во всех вершинах грани,
        // но интерполяция float может дать 4.99997 вместо 5.0, и драйвер
        // округлит выборку к соседнему слою — на гранях проступит чужая
        // текстура. Квалификатор flat снимает вопрос полностью.
        flat out float vLayer;
        out float vShade;
        out vec3 vTint;
        out float vFog;

        // Яркость по ориентации грани: UP, DOWN, NORTH, SOUTH, WEST, EAST.
        const float FACE_SHADE[6] = float[6](1.0, 0.52, 0.80, 0.80, 0.66, 0.66);

        void main() {
            uint w0 = aWord0;
            uint w1 = aWord1;

            // --- Распаковка позиции (в шестнадцатых долях блока) ---
            float px = float((w0 >> ${VertexFormat.POS_X_SHIFT}u) & ${VertexFormat.POS_MASK}u);
            float py = float((w0 >> ${VertexFormat.POS_Y_SHIFT}u) & ${VertexFormat.POS_MASK}u);
            float pz = float((w0 >> ${VertexFormat.POS_Z_SHIFT}u) & ${VertexFormat.POS_MASK}u);
            uint face = (w0 >> ${VertexFormat.FACE_SHIFT}u) & ${VertexFormat.FACE_MASK}u;
            float ao = float((w0 >> ${VertexFormat.AO_SHIFT}u) & ${VertexFormat.AO_MASK}u);

            // --- Распаковка атрибутов поверхности ---
            float u = float((w1 >> ${VertexFormat.U_SHIFT}u) & ${VertexFormat.UV_MASK}u);
            float v = float((w1 >> ${VertexFormat.V_SHIFT}u) & ${VertexFormat.UV_MASK}u);
            float layer = float((w1 >> ${VertexFormat.LAYER_SHIFT}u) & ${VertexFormat.LAYER_MASK}u);
            float sky = float((w1 >> ${VertexFormat.SKY_SHIFT}u) & ${VertexFormat.LIGHT_MASK}u);
            float blockLight = float((w1 >> ${VertexFormat.BLOCK_LIGHT_SHIFT}u) & ${VertexFormat.LIGHT_MASK}u);
            uint biome = (w1 >> ${VertexFormat.BIOME_SHIFT}u) & ${VertexFormat.BIOME_MASK}u;
            bool animated = ((w1 >> ${VertexFormat.ANIMATED_SHIFT}u) & 1u) != 0u;
            bool tinted = ((w1 >> ${VertexFormat.TINTED_SHIFT}u) & 1u) != 0u;

            vec3 localPos = vec3(px, py, pz) * ${1.0f / VertexFormat.POSITION_SCALE};
            vec3 worldPos = uSectionOrigin + localPos;

            // Колебание поверхности жидкости. Две волны с несоизмеримыми
            // периодами не дают заметного повторения рисунка.
            if (animated) {
                float wave = sin(worldPos.x * 0.7 + uTime * 1.6)
                           + sin(worldPos.z * 0.9 + uTime * 1.1);
                worldPos.y += wave * 0.022;
            }

            gl_Position = uViewProj * vec4(worldPos, 1.0);

            vTexCoord = vec2(u, v);
            vLayer = layer;

            // Максимум, а не сумма: см. пояснение в описании класса.
            float light = max(sky / 15.0 * uDayFactor, blockLight / 15.0);
            // Нижняя граница освещённости: абсолютно чёрные поверхности
            // читаются как дыра в геометрии, а не как темнота.
            light = max(light, 0.055);

            // AO: 0 — глухой угол, 3 — открытая поверхность.
            float aoFactor = 0.52 + ao * 0.16;

            vShade = light * aoFactor * FACE_SHADE[int(face)];
            vTint = tinted ? uBiomeTint[biome] : vec3(1.0);

            // Туман по расстоянию. Позиции уже относительны камере,
            // поэтому длина вектора и есть расстояние до наблюдателя.
            float dist = length(worldPos);
            vFog = clamp((dist - uFogStart) / max(uFogEnd - uFogStart, 0.001), 0.0, 1.0);
        }
    """.trimIndent()

    /**
     * Фрагментный шейдер геометрии.
     *
     * Генерируется в двух вариантах. Вариант с отбрасыванием пикселей
     * (`discard`) нужен листве и растительности, но он **отключает раннюю
     * проверку глубины** для всего шейдера: GPU не может отбросить фрагмент
     * до его выполнения, раз тот сам решает, существовать ему или нет.
     * Для непрозрачной геометрии, а это подавляющее большинство поверхностей,
     * такая потеря недопустима, поэтому для неё собирается отдельная
     * программа вообще без ветки отбрасывания.
     */
    fun chunkFragment(alphaTest: Boolean): String = """
        #version 300 es
        precision mediump float;
        precision mediump sampler2DArray;

        in vec2 vTexCoord;
        flat in float vLayer;
        in float vShade;
        in vec3 vTint;
        in float vFog;

        uniform sampler2DArray uAtlas;
        uniform vec3 uFogColor;

        out vec4 fragColor;

        void main() {
            vec4 texel = texture(uAtlas, vec3(vTexCoord, vLayer));
            ${if (alphaTest) "if (texel.a < 0.35) discard;" else ""}

            vec3 color = texel.rgb * vTint * vShade;
            color = mix(color, uFogColor, vFog);
            fragColor = vec4(color, texel.a);
        }
    """.trimIndent()

    /**
     * Вершинный шейдер неба.
     *
     * Геометрии нет вовсе: три вершины полноэкранного треугольника
     * вычисляются из `gl_VertexID`. Треугольник, а не два треугольника
     * прямоугольника, — чтобы не было диагонального шва, вдоль которого
     * GPU дважды обрабатывает пиксели.
     *
     * Глубина выставляется в единицу (дальняя плоскость), поэтому при
     * сравнении `GL_LEQUAL` небо закрашивает только те пиксели, которые
     * не занял рельеф. Это превращает дорогой полноэкранный шейдер
     * в шейдер, работающий лишь на видимой части неба.
     */
    val SKY_VERTEX = """
        #version 300 es
        precision highp float;

        uniform mat4 uInvViewProj;

        out vec3 vRay;

        void main() {
            vec2 pos = vec2(
                float((gl_VertexID & 1) << 2) - 1.0,
                float((gl_VertexID & 2) << 1) - 1.0
            );
            // z = 1 — дальняя плоскость.
            gl_Position = vec4(pos, 1.0, 1.0);

            vec4 far = uInvViewProj * vec4(pos, 1.0, 1.0);
            vRay = far.xyz / far.w;
        }
    """.trimIndent()

    /**
     * Фрагментный шейдер неба: градиент, солнце, луна и объёмные облака.
     *
     * Облака считаются пересечением луча взгляда с горизонтальной плоскостью
     * и выборкой процедурного шума в точке пересечения. Такой подход даёт
     * правильную перспективу — облака сходятся к горизонту — при нулевой
     * геометрии и без единой текстуры. Плоскость облаков смещается со
     * временем, что читается как ветер.
     */
    val SKY_FRAGMENT = """
        #version 300 es
        precision highp float;

        in vec3 vRay;

        uniform vec3 uZenithColor;
        uniform vec3 uHorizonColor;
        uniform vec3 uSunDirection;
        uniform vec3 uSunColor;
        uniform float uTime;
        uniform float uCloudCoverage;
        uniform float uCameraY;
        uniform float uDayFactor;

        out vec4 fragColor;

        // Хеш-функция для процедурного шума. Подобрана так, чтобы не давать
        // видимых регулярностей на больших расстояниях от начала координат.
        float hash(vec2 p) {
            p = fract(p * vec2(123.34, 456.21));
            p += dot(p, p + 45.32);
            return fract(p.x * p.y);
        }

        float valueNoise(vec2 p) {
            vec2 i = floor(p);
            vec2 f = fract(p);
            // Квинтическое сглаживание: непрерывна вторая производная,
            // поэтому на облаках не видно решётки.
            vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
            float a = hash(i);
            float b = hash(i + vec2(1.0, 0.0));
            float c = hash(i + vec2(0.0, 1.0));
            float d = hash(i + vec2(1.0, 1.0));
            return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
        }

        float fbm(vec2 p) {
            float sum = 0.0;
            float amp = 0.5;
            for (int i = 0; i < 4; i++) {
                sum += valueNoise(p) * amp;
                p *= 2.03;
                amp *= 0.5;
            }
            return sum;
        }

        void main() {
            vec3 ray = normalize(vRay);

            // --- Градиент неба ---
            // Степень 0.42 сгущает переход у горизонта: линейный градиент
            // выглядит как заливка, а не как атмосфера.
            float h = clamp(ray.y, 0.0, 1.0);
            vec3 sky = mix(uHorizonColor, uZenithColor, pow(h, 0.42));

            // Ниже горизонта небо темнеет — так читается «земля» за краем мира.
            if (ray.y < 0.0) {
                sky = mix(uHorizonColor, uHorizonColor * 0.45, clamp(-ray.y * 2.5, 0.0, 1.0));
            }

            // --- Светило ---
            float sunDot = dot(ray, uSunDirection);
            // Резкий диск плюс широкое гало.
            float disc = smoothstep(0.9985, 0.9993, sunDot);
            float glow = pow(max(sunDot, 0.0), 220.0) * 0.55
                       + pow(max(sunDot, 0.0), 8.0) * 0.10;
            sky += uSunColor * (disc * 1.4 + glow);

            // --- Облака ---
            // Плоскость облаков на фиксированной высоте над игроком.
            float cloudPlane = 120.0 - uCameraY;
            if (ray.y > 0.015 && uCloudCoverage > 0.001) {
                float t = cloudPlane / ray.y;
                if (t > 0.0 && t < 6000.0) {
                    vec2 cloudUV = ray.xz * t * 0.0016 + vec2(uTime * 0.004, uTime * 0.0022);
                    float density = fbm(cloudUV);
                    // Порог сдвигается облачностью: погода меняет небо
                    // без единого дополнительного вызова отрисовки.
                    float threshold = mix(0.72, 0.34, uCloudCoverage);
                    float cloud = smoothstep(threshold, threshold + 0.22, density);
                    // Растворяем облака у горизонта, иначе видна резкая
                    // граница плоскости.
                    cloud *= smoothstep(0.015, 0.22, ray.y);

                    vec3 cloudColor = mix(vec3(0.62, 0.64, 0.70), vec3(1.0, 0.99, 0.96), uDayFactor);
                    // Подсветка кромки со стороны солнца.
                    cloudColor += uSunColor * pow(max(sunDot, 0.0), 5.0) * 0.28;
                    sky = mix(sky, cloudColor, cloud * 0.88);
                }
            }

            fragColor = vec4(sky, 1.0);
        }
    """.trimIndent()

    /**
     * Шейдеры выделения блока под прицелом — контур из линий.
     * Отдельная простейшая программа: подмешивать выделение в основной
     * шейдер значило бы гнать через него ветку ради одного объекта в кадре.
     */
    val LINE_VERTEX = """
        #version 300 es
        precision highp float;

        layout(location = 0) in vec3 aPosition;

        uniform mat4 uViewProj;
        uniform vec3 uOrigin;

        void main() {
            gl_Position = uViewProj * vec4(uOrigin + aPosition, 1.0);
        }
    """.trimIndent()

    val LINE_FRAGMENT = """
        #version 300 es
        precision mediump float;

        uniform vec4 uColor;
        out vec4 fragColor;

        void main() {
            fragColor = uColor;
        }
    """.trimIndent()

    /**
     * Шейдеры двумерного интерфейса: джойстик, кнопки, инвентарь, прицел.
     *
     * Один и тот же шейдер рисует и сплошные прямоугольники, и иконки
     * из текстурного массива блоков: признак `uUseTexture` переключает
     * источник цвета. Это позволяет отрисовать весь интерфейс, ни разу
     * не переключая программу.
     */
    val UI_VERTEX = """
        #version 300 es
        precision highp float;

        layout(location = 0) in vec2 aPosition;
        layout(location = 1) in vec3 aTexCoord;
        layout(location = 2) in vec4 aColor;

        uniform mat4 uProjection;

        out vec2 vTexCoord;
        flat out float vLayer;
        out vec4 vColor;

        void main() {
            gl_Position = uProjection * vec4(aPosition, 0.0, 1.0);
            vTexCoord = aTexCoord.xy;
            vLayer = aTexCoord.z;
            vColor = aColor;
        }
    """.trimIndent()

    val UI_FRAGMENT = """
        #version 300 es
        precision mediump float;
        precision mediump sampler2DArray;

        in vec2 vTexCoord;
        flat in float vLayer;
        in vec4 vColor;

        uniform sampler2DArray uAtlas;

        out vec4 fragColor;

        void main() {
            // Отрицательный слой означает «без текстуры»: элемент заливается
            // сплошным цветом. Признак передаётся в самом атрибуте, поэтому
            // сплошные и текстурированные элементы попадают в один пакет.
            if (vLayer < 0.0) {
                fragColor = vColor;
            } else {
                vec4 texel = texture(uAtlas, vec3(vTexCoord, vLayer));
                if (texel.a < 0.02) discard;
                fragColor = vec4(texel.rgb * vColor.rgb, texel.a * vColor.a);
            }
        }
    """.trimIndent()
}
