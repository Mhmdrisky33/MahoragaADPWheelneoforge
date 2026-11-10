package com.mahoraga.client;

import com.mahoraga.MahoragaMod;
import com.mahoraga.adapt.AdaptationKeys;
import com.mahoraga.network.AdaptationHudPacket;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * **左上角的法轮 HUD**（主人 2026-09-15 要的）。
 *
 * 长这样：
 * ```
 *    ⚙   ← 法轮图标（30×30，自己画的那个八辐轮）
 *    岩浆伤害   74%   ← 正在适应的项目，按进度从高到低
 *    末影人    112%
 * ```
 *
 * 三条规矩（都是主人明确要的）：
 * - **小一点、别挡视野** —— 图标 30 像素、最多列 5 行、贴着屏幕左上角；
 * - **每次适应完成，法轮转 360 度** —— 序号变了就转（见 {@link #accept}）；
 * - 没有法轮的人整块都不画。
 *
 * ⚠️ 数据全是服务端推过来的（适应进度存在服务端，客户端读不到）——
 * 见 {@link AdaptationHudPacket}。显示名在这里才翻译，所以中文客户端看到的是中文。
 */
public final class WheelHud {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MahoragaMod.MOD_ID, "textures/gui/wheel_hud.png");

    /** 离屏幕左上角多远。 */
    /** 图标边长（GUI 像素，不是贴图像素 —— 贴图是 128×128，缩着画）。 */
    private static final int WHEEL_SIZE = 30;
    /** 列表行高。字体本身高 9，给 9 就够紧。 */
    private static final int ROW_HEIGHT = 9;
    /** 名字最长画多宽（超了截断加不了省略号，但总比糊出屏幕好）。 */
    private static final int NAME_WIDTH = 82;
    /** 转一圈用多久（毫秒）。 */
    private static final long SPIN_MS = 850L;

    /** 三条轨道各一个颜色：防御淡蓝 / 攻击橙红 / 日常黄绿。 */
    private static final int[] TRACK_COLORS = {0xFF9BD8FF, 0xFFFFA07A, 0xFFB6EE8C};

    private static boolean visible = false;
    private static List<AdaptationHudPacket.Entry> entries = List.of();
    private static int lastSpin = Integer.MIN_VALUE;
    /** 这次旋转的起点时刻（毫秒）；0 = 没在转。 */
    private static long spinStart = 0L;

    private WheelHud() {
    }

    /** 收到服务端推来的数据。 */
    public static void accept(AdaptationHudPacket packet) {
        visible = packet.wheel();
        entries = packet.entries();

        if (packet.spinSeq() != lastSpin) {
            // ⚠️ 第一次收到（刚进世界 / 刚连上）**不转** —— 那个序号是历史累计圈数，
            //    不是"你刚刚完成了一次适应"。不然每次进游戏都白转一圈。
            if (lastSpin != Integer.MIN_VALUE) spinStart = System.currentTimeMillis();
            lastSpin = packet.spinSeq();
        }
    }

    /** 退出世界 / 断线时清干净，免得下次进游戏还挂着上一局的列表。 */
    public static void reset() {
        visible = false;
        entries = List.of();
        lastSpin = Integer.MIN_VALUE;
        spinStart = 0L;
    }


    /** 左上角那块东西的全部绘制。 */
    public static void render(GuiGraphics graphics) {
        WheelHudSettings.loadIfNeeded();
        if (!visible || (!WheelHudSettings.showIcon() && !WheelHudSettings.showEntries())) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.options.hideGui) return;

        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(WheelHudSettings.x(), WheelHudSettings.y(), 0.0F);
        pose.scale(WheelHudSettings.scale(), WheelHudSettings.scale(), 1.0F);

        if (WheelHudSettings.showIcon()) drawWheel(graphics);

        if (WheelHudSettings.showEntries()) {
            var font = minecraft.font;
            int y = WheelHudSettings.showIcon() ? WHEEL_SIZE + 2 : 0;

            for (AdaptationHudPacket.Entry entry : entries) {
                String name = font.plainSubstrByWidth(
                        AdaptationKeys.display(entry.key()).getString(), NAME_WIDTH);
                String percent = " " + entry.percent() + "%";

                int nameWidth = font.width(name);
                int color = TRACK_COLORS[Mth.clamp(entry.track(), 0, TRACK_COLORS.length - 1)];

                graphics.fill(-1, y - 1, nameWidth + font.width(percent) + 2, y + 8, 0x1F000000);
                graphics.drawString(font, name, 0, y, color, true);
                graphics.drawString(font, percent, nameWidth, y, 0xFFE6E6E6, true);
                y += ROW_HEIGHT;
            }
        }

        pose.popPose();
    }

    private static void drawWheel(GuiGraphics graphics) {
        var pose = graphics.pose();
        float half = WHEEL_SIZE / 2.0F;

        float angle = spinAngle();
        boolean spinning = angle != 0.0F;

        float wobble = spinning ? 0.0F : idleWobble();
        float jitterX = spinning ? 0.0F : idleJitter(7.3D, 0.55F);
        float jitterY = spinning ? 0.0F : idleJitter(4.9D, 0.45F);

        pose.pushPose();
        pose.translate(half + jitterX, half + jitterY, 0.0F);
        pose.mulPose(Axis.ZP.rotationDegrees(angle + wobble));
        pose.translate(-half, -half, 0.0F);

        graphics.blit(TEXTURE, 0, 0, WHEEL_SIZE, WHEEL_SIZE, 0.0F, 0.0F, 128, 128, 128, 128);

        pose.popPose();
    }

    /** 当前该转到第几度（不在转就是 0）。 */
    private static float spinAngle() {
        if (spinStart <= 0L) return 0.0F;

        float t = (System.currentTimeMillis() - spinStart) / (float) SPIN_MS;

        if (t >= 1.0F) {
            spinStart = 0L;
            return 0.0F;                    // 360° 和 0° 是同一个位置，收尾不会跳
        }

        // ease-out：起步快、收尾慢 —— 线性转完会"咯噔"一下停住，很难看
        float eased = 1.0F - (float) Math.pow(1.0F - t, 3.0D);
        return 360.0F * eased;
    }

    /**
     * <b>憋着要转的那股劲儿</b> —— 平时法轮一直小幅晃动，时不时还"抽"一下。
     *
     * <p>三层叠出来的：
     * <ol>
     *   <li><b>两个不同频率的正弦</b>（2.3Hz + 5.9Hz）——
     *       单一频率看着像坏了／像贴图在抖，两个beat在一起才"活"；</li>
     *   <li><b>偶尔一记猛抽</b>（{@code sin^80} 的尖峰）——
     *       一秒多来一次，幅度 6 度，就是"差一点就要转起来、又忍住了"那一下；</li>
     *   <li>整体幅度压在几度以内 —— 主人要的是**蠢蠢欲动**，不是"坏掉的轮子"。</li>
     * </ol>
     */
    private static float idleWobble() {
        double t = System.currentTimeMillis() / 1000.0D;

        double sway = Math.sin(t * 2.3D) * 2.4D + Math.sin(t * 5.9D) * 1.2D;

        // sin 的高次幂 = 又窄又尖的脉冲（只在 sin 接近 1 时才冒出来）
        double twitch = Math.pow(Math.max(0.0D, Math.sin(t * 1.7D)), 80.0D) * 6.0D;

        return (float) (sway + twitch);
    }

    /** 位置上的细碎抖动（像素级）—— 光转不晃还是会显得"贴"在屏幕上。 */
    private static float idleJitter(double speed, float amount) {
        double t = System.currentTimeMillis() / 1000.0D;
        return (float) (Math.sin(t * speed) * amount);
    }
}
