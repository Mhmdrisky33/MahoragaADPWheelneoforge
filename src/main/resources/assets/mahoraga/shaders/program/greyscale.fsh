#version 150

// 魔虚罗 —— 「影」的保红灰度（2026-09-25 · 第三版）。
//
// ## 主人：「那个屏幕上的扩散还在」→ 删掉
//
// 我前两版都在往"波"上使劲：第一版是整屏突然换滤镜，第二版是加了一道
// 从屏幕中心扫出去的环。**两次都错了** —— 因为参考实现的灰度压根不是波。
//
// ## 参考实现到底怎么做的（它的 uniform 是 Saturation / Folly / Darkness）
//
//     float f = worldblackraincomponent.getGradient();   // 黑雨 / 日蚀的渐变
//     if (isWithinEclipse()) f = 1.0F;
//     greyscaleSaturation.set(1.0F - f);                 // f=1 → 去色拉满
//     greyscaleDarkness.set(0.5F + (1.0F - f) * 0.5F);
//     if (f > 0.0F) GREYSCALE_PROGRAM.render(tickDelta);
//
// 也就是说：它由**天气事件**（黑雨 / 日蚀）驱动，`f` 一升，
// **整屏一起、常驻地**变灰 —— 没有波、没有环、没有方向、没有"扫过"这回事。
//
// 所以这一版就是全屏：
//     `intensity` 平滑地从 0 爬到 1（约 1.3 秒），到位就一直保持；
//     浮出时再平滑地回到 0。
//
// ## 🔑 判据不是"去色"，是"挑两类色相留下来"
//
//      hue ∈ (0.9, 1.0] 且 饱和 > 0.5   → 红 / 血
//      hue ∈ (0.7, 1.0] 且 明度 < 0.1   → 近黑（别把暗部一块洗成灰）
//    其余全部按亮度拍平。
//
// ⚠️ 判据必须用 **hue（色相角）**，不能用"R 大于 G"那种土办法：
//     后者会把**橙色火把、黄色沙**一起留下来，满屏都是"没变灰的东西"，
//     一眼看上去就是"滤镜没生效"，效果全毁。
//
// ## 一处故意没抄
//
// 参考实现还有 `darkness = 0.5 + (1-f)*0.5` —— 那是**把暗部抬亮**。
// 它配黑雨是对的（雨天叠去色，画面会糊成一团纯黑，需要抬暗部）。
// 我们只在潜入时用，**不加**那一层：潜入本来就该是暗的。

uniform sampler2D DiffuseSampler;
uniform float intensity;    // 灰度的量（0 = 原样，1 = 完全变灰）
uniform float saturation;   // 0 = 完全去色（留成 uniform 是为了以后想"只洗掉一部分颜色"）

in vec2 texCoord;
out vec4 fragColor;

// 标准 RGB→HSV（Book of Shaders 那个经典写法）。
// d 是 max-min，归一化时要除以它 —— 灰色像素 d == 0，必须拿 e 兜住。
// ⚠️ 漏了那个 e：**灰色像素**（天空、石头）会算出 NaN → 那片区域变纯黑或纯白，
//    而且只在某几种颜色上出，极难定位。
vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));

    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;

    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

void main() {
    vec4 texel = texture(DiffuseSampler, texCoord);

    vec3 rgb = texel.rgb;
    vec3 hsv = rgb2hsv(rgb);

    bool keepRed = (hsv.x > 0.9 && hsv.x <= 1.0 && hsv.y > 0.5);
    bool keepDark = (hsv.x > 0.7 && hsv.x <= 1.0 && hsv.z < 0.1);

    vec3 grey = rgb;

    if (!(keepRed || keepDark)) {
        float luma = dot(rgb, vec3(0.299, 0.587, 0.114));
        grey = (rgb - luma) * saturation + luma;
    }

    // ⚠️ alpha 原样带走 —— 不然半透明的云 / 水 / 玻璃会变成不透明的色块
    fragColor = vec4(mix(rgb, grey, clamp(intensity, 0.0, 1.0)), texel.a);
}
