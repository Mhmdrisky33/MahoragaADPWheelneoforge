#version 150

// 空间权能 —— 人物身上的故障（第三人称）。
//
// 读一张**剪影遮罩**（MaskSampler），只在遮罩覆盖的地方做故障，其余画面原样输出。
// 剪影由 Java 侧每帧把玩家单独渲染到一张离屏 target 得到（见 MaskRenderer）。
//
// ⏱ 时间尺度同全屏版：**帧计数**，进来先 `mod` 防止大数把噪声冻死。
//
// 👻 运动残影（"时间断裂"）：沿移动方向把人物拽出若干个错位副本，
//    配合频闪 —— 跑起来的时候，身后会断续留下一串"上一瞬间的自己"。
//    方向由 Java 侧算好的屏幕空间速度传入（uniform `motion`）。

uniform sampler2D DiffuseSampler;
uniform sampler2D MaskSampler;
uniform vec2 OutSize;
uniform float time;
uniform float intensity;
uniform vec2 motion;        // 屏幕空间：方向 × 速度（0~1）

in vec2 texCoord;
out vec4 fragColor;

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

vec3 glitchOf(vec2 uv, float mask, float amp, float t) {
    float burst = max(pulse(7.0, 1.0, t), pulse(11.0, 17.0, t));
    float heavy = pulse(3.0, 41.0, t);

    float line = floor(uv.y * OutSize.y);
    float band = floor(uv.y * 42.0);
    vec2  cell = floor(uv * vec2(34.0, 19.0));

    // ⚠️ GLSL 的 `+ -` 不允许 vector 与 scalar 混用（只有 `* /` 才允许广播）
    float fineNoise = n2(vec2(line, floor(t * 2.0)));
    float bandNoise = n2(vec2(band, floor(t * 0.75)));
    float cellNoise = n2(cell + vec2(floor(t * 1.6)));

    float tearMask   = step(0.86, bandNoise) * burst * mask;
    float jitterMask = step(0.985, fineNoise) * mask;
    float cellMask   = step(0.965, cellNoise) * (0.45 + burst) * mask;
    float scanMask   = step(0.5, fract((uv.y * OutSize.y + t * 1.8) * 0.5));

    float tearOff   = (n2(vec2(band + 7.0, floor(t * 3.0))) - 0.5) * 0.075 * tearMask;
    float jitterOff = (fineNoise - 0.5) * 0.014 * jitterMask;
    float waveOff   = sin(uv.y * 90.0 + t * 1.4) * 0.0022 * burst * mask;
    float rollOff   = heavy * sin(t * 0.0085) * 0.014 * mask;

    vec2 suv = clamp(uv + vec2(tearOff + jitterOff + waveOff, rollOff),
                     vec2(0.001), vec2(0.999));

    vec2 blockUv = (cell + 0.5) / vec2(34.0, 19.0);
    blockUv.x += (n2(vec2(cell.y, floor(t * 4.0))) - 0.5) * 0.10 * cellMask;
    suv = mix(suv, clamp(blockUv, vec2(0.001), vec2(0.999)),
              clamp(cellMask * 0.65, 0.0, 0.9));

    float split = (0.0035 + 0.0060 * burst + abs(tearOff) * 0.26)
                * (0.65 + n2(vec2(band, 5.0)) * 0.70) * mask * clamp(amp, 0.6, 1.8);

    vec3 col;
    col.r = texture(DiffuseSampler, clamp(suv + vec2(split, 0.0), vec2(0.001), vec2(0.999))).r;
    col.g = texture(DiffuseSampler, suv).g;
    col.b = texture(DiffuseSampler, clamp(suv - vec2(split, 0.0), vec2(0.001), vec2(0.999))).b;

    float grain = n2(floor(uv * OutSize * vec2(0.45, 0.22)) + vec2(floor(t * 8.0)));
    col += (grain - 0.5) * (0.030 + 0.055 * tearMask);

    col *= mix(1.0, mix(0.95, 1.03, scanMask), clamp(mask, 0.0, 1.0));

    float flash = tearMask * smoothstep(0.15, 0.95, n2(vec2(band, floor(t * 9.0))));
    col = mix(col, vec3(col.r * 1.25, col.g * 0.82, col.b * 1.45), clamp(flash * 0.32, 0.0, 0.75));

    float inv = heavy * step(0.992, n2(vec2(band, floor(t * 16.0)))) * mask;
    col = mix(col, 1.0 - col, clamp(inv * 0.20, 0.0, 0.65));

    return clamp(col, 0.0, 1.0);
}

/**
 * 人物身上的**故障闪烁**。
 *
 * <h3>为什么不是拖尾</h3>
 * 之前那版是"沿运动方向拖出一串副本" —— 那是**运动残影**的语汇（速度感），
 * 观感是一堆贴着自己的影子，规律太强，一眼就能看懂人在往哪走。
 * 而主人要的是**故障**：让人**判不出本体的位置**。故障的语汇是**随机**，不是方向。
 *
 * <h3>四个随机</h3>
 * <ol>
 *   <li>沿身高切成几十条横带，**每一条各自**决定动不动（不是整块一起动）</li>
 *   <li>动的那几条左右错开 —— 幅度给足，本体的准确位置就模糊了</li>
 *   <li>极少数横带**整条抹掉**（露出背后的世界）—— 那一下才是真"故障"</li>
 *   <li>错开的每条各带自己的 RGB 分离</li>
 * </ol>
 *
 * <p>节奏跟帧号跳（约每 2~3 帧换一次随机值），所以是"闪"不是"飘"。
 * 站着留一点底噪（这人不稳定），移动时整体更凶 —— {@code drive} 控制这个。
 *
 * <h3>🔴 一条分水岭（留着以后用）</h3>
 * 采样用的是 {@code DiffuseSampler}（整幅画面，含身后的世界）。
 * **没有 mask 判定**就混回人身上的话，混进来的是**背景** ——
 * 人身上的像素被背后的风景顶掉，看起来就是**半透明 / 隐形**。
 * 那个写法本身就是个不错的**幽灵感 / 局部隐身**效果（主人点名要留），
 * 做"相位化、虚化"时把下面那道 {@code gm} 判定删掉即可。
 */
vec3 flickerBreak(vec2 uv, float mask, float t, vec3 base, vec3 world) {
    float speed = length(motion);
    float drive = clamp(speed * 3.0, 0.0, 1.0);
    float energy = 0.40 + drive * 0.60;

    // 横带编号 + 高频跳变的随机种子（约每 2~3 帧换一批）
    float band = floor(uv.y * 52.0);
    float tick = floor(t * 0.4);

    float r1 = n2(vec2(band, tick));
    float r2 = n2(vec2(band + 71.0, tick));
    float r3 = n2(vec2(band + 137.0, tick));

    // ③ 整条抹掉 —— 最像"故障"的一下
    if (step(1.0 - 0.11 * energy, r3) > 0.5) {
        return world;
    }

    // ① 这条带参不参与撕裂（各带独立决定）
    if (step(1.0 - 0.40 * energy, r1) <= 0.5) {
        return base;
    }

    // ② 左右错开：幅度给足，让人判不出本体的准确位置
    float off = (r2 - 0.5) * (0.018 + 0.060 * energy);
    vec2 guv = clamp(uv + vec2(off, 0.0), vec2(0.001), vec2(0.999));

    // 错开之后还在人身上吗（不在 = 采到的是背景，作废，别把世界糊到人身上）
    if (texture(MaskSampler, guv).a <= 0.05) {
        return base;
    }

    // ④ 每条自己的 RGB 分离
    float sep = (0.005 + 0.013 * energy) * (0.4 + r2);
    vec3 ghost;
    ghost.r = texture(DiffuseSampler, clamp(guv + vec2(sep, 0.0), vec2(0.001), vec2(0.999))).r;
    ghost.g = texture(DiffuseSampler, guv).g;
    ghost.b = texture(DiffuseSampler, clamp(guv - vec2(sep, 0.0), vec2(0.001), vec2(0.999))).b;

    return mix(base, ghost, 0.92);
}

void main() {
    vec4 src = texture(DiffuseSampler, texCoord);
    float mask = texture(MaskSampler, texCoord).a;

    if (mask <= 0.01 || intensity <= 0.001) {
        fragColor = vec4(src.rgb, 1.0);
        return;
    }

    float t = mod(time, 1000.0);

    vec3 affected = glitchOf(texCoord, mask, intensity, t);
    affected = flickerBreak(texCoord, mask, t, affected, src.rgb);

    fragColor = vec4(mix(src.rgb, affected, mask), 1.0);
}
