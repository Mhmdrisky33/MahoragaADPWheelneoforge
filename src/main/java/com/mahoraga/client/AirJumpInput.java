package com.mahoraga.client;

import com.mahoraga.MahoragaMod;
import com.mahoraga.adapt.AirJump;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * 二段跳 / 多段跳的客户端一半 —— 由**持续奔跑**适应解锁。
 *
 * ==================== 为什么只能在客户端做 ====================
 * "能不能在空中再跳一次"这件事，**原版只在地面时才允许跳跃**
 * （{@code LocalPlayer#aiStep} 里写着 `if (onGround) jumpFromGround()`）。
 * 要突破它，最干净的办法就是**每 tick 自己看着跳跃键**：
 * 松开再按下的那一下、人还在空中、额度还没用完 → 直接给一个向上的速度。
 *
 * 用键盘"边沿"（松开再按）而不是"按住"来计数，是为了跟原版一致 ——
 * 按住空格不会连跳，得一下一下按。
 *
 * 次数在**落地 / 入水 / 爬梯子 / 飞行**时回满；额度从
 * {@link AirJump#clientTiers()} 读（服务端每秒同步过来的）。
 */
@EventBusSubscriber(modid = MahoragaMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class AirJumpInput {
    /** 原版玩家的跳跃初速度 —— 用同一个值，手感才一致。 */
    private static final float JUMP_POWER = 0.42F;

    /** 上一 tick 跳跃键是不是按着的（用来认"这一 tick 刚按下"）。 */
    private static boolean wasJumping = false;
    /** 这一轮滞空已经用掉几段。 */
    private static int usedAirJumps = 0;

    private AirJumpInput() {
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Pre event) {
        
        if (!(event.getEntity() instanceof LocalPlayer player)) return;
        if (Minecraft.getInstance().player != player) return;      // 只管本地玩家

        tick(player);
    }

    private static void tick(LocalPlayer player) {
        boolean jumping = Minecraft.getInstance().options.keyJump.isDown();
        boolean justPressed = jumping && !wasJumping;
        wasJumping = jumping;

        int tiers = AirJump.clientTiers();

        // 没解锁 —— 顺便把状态归零，下次解锁时不会带着旧计数
        if (tiers < AirJump.MIN_TIERS) {
            usedAirJumps = 0;
            return;
        }

        // 落地 / 入水 / 爬梯子 / 飞行 → 额度回满
        if (player.onGround() || player.isInWater() || player.onClimbable()
                || player.getAbilities().flying) {
            usedAirJumps = 0;
            return;
        }

        if (!justPressed) return;
        if (Minecraft.getInstance().screen != null) return;        // 开着界面时不跳
        if (usedAirJumps >= tiers - 1) return;                     // 这一段用完了

        usedAirJumps++;

        Vec3 motion = player.getDeltaMovement();
        player.setDeltaMovement(motion.x, JUMP_POWER, motion.z);
        player.hasImpulse = true;

        int index = usedAirJumps;                                  // 第几段（2 = 二段跳）
        spawnEffects(player, index);
    }

    /** 踏空的样子：脚下一圈白烟 + 几点光尘 + 一声振翅。 */
    private static void spawnEffects(LocalPlayer player, int index) {
        var level = player.level();
        var random = player.getRandom();

        for (int i = 0; i < 10; i++) {
            level.addParticle(ParticleTypes.CLOUD,
                    player.getX() + (random.nextDouble() - 0.5D) * 0.8D,
                    player.getY() + 0.05D,
                    player.getZ() + (random.nextDouble() - 0.5D) * 0.8D,
                    0.0D, -0.03D, 0.0D);
        }
        for (int i = 0; i < 5; i++) {
            level.addParticle(ParticleTypes.END_ROD,
                    player.getX() + (random.nextDouble() - 0.5D) * 0.7D,
                    player.getY() + random.nextDouble() * 0.6D,
                    player.getZ() + (random.nextDouble() - 0.5D) * 0.7D,
                    0.0D, 0.02D, 0.0D);
        }

        // 段数越高，音调越高 —— 听得出来自己跳到第几段了
        level.playLocalSound(player.getX(), player.getY(), player.getZ(),
                SoundEvents.PHANTOM_FLAP, SoundSource.PLAYERS,
                0.65F, 1.2F + 0.12F * (index - 1), false);
    }
}
