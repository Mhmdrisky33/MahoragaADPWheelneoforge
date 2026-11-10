#version 150

// 空间权能 —— 故障（glitch）后期处理。原创实现，观感对齐参考实现。
//
// ⏱ time = 帧计数（≈60fps）。进来先 `mod` 到小范围 —— **这一步是必须的**：
//    float 超过 1677 万就分辨不出相邻整数，`floor(time * 7)` 会停止变化，
//    噪声冻死、整屏静止（跑几分钟必现）。
//
// 🌊 边缘优先：所有效果乘一个「边缘权重」，画面中心基本干净，
//    故障先在四边出现；intensity 越高，界限越往中心蔓延。

uniform sampler2D DiffuseSampler;
uniform vec2 OutSize;
uniform float time;
uniform float intensity;

in vec2 texCoord;
out vec4 fragColor;

// ---- 数值稳定的哈希（Dave Hoskins 无 sin 版）----
// 不用 `sin(x * 大常数)`：那种写法在大输入下精度崩坏，正是"特效卡住"的元凶。
float n1(float x) {
    float p = fract(x * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
}

float n2(vec2 v) {
    vec3 p = fract(vec3(v.x, v.y, v.x) * vec3(0.1031, 0.1030, 0.0973));
    // ⚠️ 必须写成 vec3(dot(...)) —— GLSL 不允许 `vec3 += float`（隐式广播会报 C7011）
    p += vec3(dot(p, p.yxz + 33.33));
    return fract((p.x + p.y) * p.z);
}

float pulse(float speed, float seed, float t) {
    return step(0.78, n2(vec2(floor(t * speed), seed)));
}

void main() {
    vec2 uv = texCoord;
    float amp = clamp(intensity, 0.0, 3.0);

    // 🔑 时间取模：保持在小数值区间，噪声才不会僵死
    float t = mod(time, 1000.0);

    // ---- 边缘权重 ----
    float dist = length(uv - vec2(0.5));
    float creep = clamp((amp - 0.80) / 0.90, 0.0, 1.0);
    float inner = mix(0.60, -0.12, creep);
    float edgeW = 0.16 + 0.84 * smoothstep(inner, inner + 0.44, dist);

    // ---- 爆发节拍 ----
    float burst = max(pulse(7.0, 1.0, t), pulse(11.0, 17.0, t));
    float heavy = pulse(3.0, 41.0, t);

    // ---- 划分 ----
    float line = floor(uv.y * OutSize.y);
    float band = floor(uv.y * 42.0);
    vec2  cell = floor(uv * vec2(34.0, 19.0));

    // ⚠️ GLSL 的 `+ -` 不允许 vector 与 scalar 混用（只有 `* /` 才允许广播），
    //    所以下面凡是"网格坐标 + 时间"都必须显式包一层 vec2。
    float fineNoise = n2(vec2(line, floor(t * 2.0)));
    float bandNoise = n2(vec2(band, floor(t * 0.75)));
    float cellNoise = n2(cell + vec2(floor(t * 1.6)));

    float tearMask   = step(0.86, bandNoise) * burst * edgeW;
    float jitterMask = step(0.985, fineNoise) * edgeW;
    float cellMask   = step(0.965, cellNoise) * (0.45 + burst) * edgeW;
    float scanMask   = step(0.5, fract((uv.y * OutSize.y + t * 1.8) * 0.5));

    // ---- 位移：幅度小、频率高 ----
    float tearOff   = (n2(vec2(band + 7.0, floor(t * 3.0))) - 0.5) * 0.075 * tearMask;
    float jitterOff = (fineNoise - 0.5) * 0.014 * jitterMask;
    float waveOff   = sin(uv.y * 90.0 + t * 1.4) * 0.0022 * burst * edgeW;
    float rollOff   = heavy * sin(t * 0.0085) * 0.014 * edgeW;

    vec2 suv = clamp(uv + vec2(tearOff + jitterOff + waveOff, rollOff),
                     vec2(0.001), vec2(0.999));

    vec2 blockUv = (cell + 0.5) / vec2(34.0, 19.0);
    blockUv.x += (n2(vec2(cell.y, floor(t * 4.0))) - 0.5) * 0.10 * cellMask;
    suv = mix(suv, clamp(blockUv, vec2(0.001), vec2(0.999)),
              clamp(cellMask * 0.65, 0.0, 0.9));

    // ---- RGB 分离（收着点）----
    float split = (0.0020 + 0.0045 * burst + abs(tearOff) * 0.22)
                * (0.65 + n2(vec2(band, 5.0)) * 0.70);

    vec3 col;
    col.r = texture(DiffuseSampler, clamp(suv + vec2(split, 0.0), vec2(0.001), vec2(0.999))).r;
    col.g = texture(DiffuseSampler, suv).g;
    col.b = texture(DiffuseSampler, clamp(suv - vec2(split, 0.0), vec2(0.001), vec2(0.999))).b;

    // ---- 噪点 / 扫描线 ----
    float grain = n2(floor(uv * OutSize * vec2(0.45, 0.22)) + vec2(floor(t * 8.0)));
    col += (grain - 0.5) * (0.030 + 0.055 * tearMask);

    col *= mix(0.95, 1.03, scanMask);

    // ---- 色闪 / 偶发反相 ----
    float flash = tearMask * smoothstep(0.15, 0.95, n2(vec2(band, floor(t * 9.0))));
    col = mix(col, vec3(col.r * 1.25, col.g * 0.82, col.b * 1.45), clamp(flash * 0.32, 0.0, 0.75));

    float inv = heavy * step(0.992, n2(vec2(band, floor(t * 16.0)))) * edgeW;
    col = mix(col, 1.0 - col, clamp(inv * 0.20, 0.0, 0.65));

    // ---- 边缘色散 + 暗角 ----
    float bleed = smoothstep(0.42, 0.74, dist) * (0.2 + 0.35 * burst);
    col.rb += vec2(0.045, 0.070) * bleed;
    col *= 1.0 - smoothstep(0.55, 0.88, dist) * 0.16;

    // ---- 撑不住时从边缘把画面吞掉（随强度向中心蔓延）----
    float swallow = clamp(amp - 1.0, 0.0, 1.0);
    col *= 1.0 - smoothstep(0.66 - 0.46 * swallow, 0.96, dist) * (0.90 * swallow);

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
