package com.mahoraga.handler;

import com.mahoraga.registry.ModSounds;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * **第一次戴上法轮**的庆祝动画（只在第一次播，重登/死亡重生不会再演一遍）。
 *
 * 主人的原话是"总是庆祝一下，放个烟花都可以" —— 那就不客气了：
 *
 * | 环节 | 用什么 |
 * |---|---|
 * | 一声闷响 | `ANVIL_LAND`（铁砧落地） |
 * | "你做到了" | `UI_TOAST_CHALLENGE_COMPLETE`（原版**成就达成**的那个音效） |
 * | 轮子转起来 | `ModSounds.WHEEL` + 让头顶的法轮真的转一圈（{@link WheelHandler#spin}） |
 * | 金色光柱 | `TOTEM_OF_UNDYING` 粒子（**不死图腾**同款，60 粒撒一圈） |
 * | 眼前一黑 | `DARKNESS` 1.5 秒 —— 先黑一下，再"豁然开朗"，仪式感就在这里 |
 * | 三发烟花 | 玩家周围斜着放，会自己飞上去炸 |
 *
 * ⚠️ **黑暗效果不能给太长**：它会让屏幕真的看不见（还带监守者那个心跳声），
 * 1.5 秒刚好是"过场"，再长就是妨碍操作了。
 */
public final class WheelCeremony {
    /** 黑暗持续（tick）—— 30 = 1.5 秒。 */
    private static final int DARKNESS_TICKS = 30;
    /** 烟花几发。 */
    private static final int FIREWORKS = 3;
    /** 烟花离玩家多远摆一圈（格）。 */
    private static final double FIREWORK_RADIUS = 1.8D;

    /** 烟花的三个颜色：金 / 橙 / 暖白（跟法轮一个色系）。 */
    private static final int[] FIREWORK_COLORS = {0xFFC83C, 0xFF8A2C, 0xFFF3C4};

    private WheelCeremony() {
    }

    public static void play(Player player) {
        if (!(player.level() instanceof ServerLevel level)) return;

        double x = player.getX();
        double y = player.getY() + player.getBbHeight() * 0.5D;
        double z = player.getZ();

        // ① 三连音：闷响 → 成就 → 轮子转
        level.playSound(null, x, y, z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 1.0F, 1.0F);
        level.playSound(null, x, y, z, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
                SoundSource.PLAYERS, 1.0F, 1.0F);
        level.playSound(null, x, y, z, ModSounds.WHEEL.get(), SoundSource.PLAYERS, 1.0F, 1.0F);

        // ② 金色光柱（不死图腾那套粒子）
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING,
                x, y, z, 60, 0.6D, 0.9D, 0.6D, 0.35D);

        // ③ 眼前一黑
        player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, DARKNESS_TICKS,
                0, false, false, false));

        // ④ 三发烟花
        for (int i = 0; i < FIREWORKS; i++) {
            double angle = (Math.PI * 2.0D / FIREWORKS) * i;

            FireworkRocketEntity firework = new FireworkRocketEntity(level,
                    x + Math.cos(angle) * FIREWORK_RADIUS,
                    y + 0.4D,
                    z + Math.sin(angle) * FIREWORK_RADIUS,
                    fireworkStack());

            level.addFreshEntity(firework);
        }
    }

    /** 造一发金橙色的大球烟花（Trail + Flicker，炸开会有拖尾和闪烁）。 */
    private static ItemStack fireworkStack() {
        ItemStack stack = new ItemStack(Items.FIREWORK_ROCKET);

        CompoundTag fireworks = stack.getOrCreateTagElement("Fireworks");
        fireworks.putByte("Flight", (byte) 1);

        CompoundTag star = new CompoundTag();
        star.putByte("Type", (byte) FireworkRocketItem.Shape.LARGE_BALL.getId());
        star.putIntArray("Colors", FIREWORK_COLORS);
        star.putBoolean("Trail", true);
        star.putBoolean("Flicker", true);

        ListTag explosions = new ListTag();
        explosions.add(star);
        fireworks.put("Explosions", explosions);

        return stack;
    }
}
