package com.mahoraga.skill;

import com.mahoraga.MahoragaMod;
import com.mahoraga.adapt.AdaptationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.TickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * 物质穿透 —— 「窒息 / 卡墙 / 身处洞穴」任一条适应满 100% 之后解锁。
 * 每 tick 把玩家**周围**的方块直接抹掉（爆裂粒子 + 一声爆炸），于是能一路穿墙。
 *
 * ==================== 盒子怎么取，是这个能力的命门（踩过三次坑）====================
 *
 * 1. 狮子之心原代码是 `deflate(0.5, 0.0, 0.5)`。原版玩家只有 0.6 格宽，两边各缩 0.5
 *    之后宽度成了 **-0.4**（反的）—— 不过 `BlockPos.containing` 会取整，
 *    退化成"只剩玩家脚下那一格"，所以**贴上去**勉强能穿，但非常钝。
 * 2. 人家第一次改成 `deflate(0.1, 0.5, 0.1)`：宽度是正的，但只剩 0.4 格，
 *    而贴墙时"身体中心到墙面"正好 0.3 格 → 盒子够不到墙 → **什么墙都穿不过去**，
 *    只有草/花这种没碰撞箱的能穿（表现成"只能破坏草方块"）。
 * 3. 第二次改成"直接用完整碰撞箱"：能穿了，但**手感一顿一顿的**（主人 2026-09-14 报）。
 *
 * 🔴 **第 3 次那个"顿"的根因**：判据用的是玩家碰撞箱覆盖的格子，可玩家的碰撞箱
 * **永远在方块外面**（`Entity#move` 会把人推出来）—— 只有"恰好贴住"时 `floor`
 * 才落到墙那一格上，而浮点误差让它**时灵时不灵** → 走两步通一下、再顿一下。
 *
 * ✅ **正解：水平方向往外扩一圈**（{@link #REACH}）。玩家**靠近**墙就开路，
 * 不用真的贴上去，也不再依赖浮点精度 —— 这才是"顺"的来源。
 */
@EventBusSubscriber(modid = MahoragaMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class MaterialPhase {
    /** 水平外扩距离（格）。0.45 ≈ "下一格还没踩上去就先给你清掉"。 */
    private static final double REACH = 0.45D;
    /** 一个 tick 最多毁多少块 —— 防着一把掀掉半座山。 */
    private static final int MAX_PER_TICK = 24;

    private MaterialPhase() {
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Player player = event.player;
        if (player.level().isClientSide) return;
        if (!AdaptationManager.hasSuffocationPhase(player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        destroyPhasedBlocks(level, player);
    }

    private static void destroyPhasedBlocks(ServerLevel level, Player player) {
        AABB area = player.getBoundingBox().inflate(REACH, 0.0D, REACH);
        BlockPos min = BlockPos.containing(area.minX, area.minY, area.minZ);
        BlockPos max = BlockPos.containing(area.maxX, area.maxY, area.maxZ);

        // 脚踩的那一格永远不动 —— 地板 / 半砖 / 台阶都在这里，不然一边走一边啃自己家
        BlockPos standOn = player.getOnPos();

        boolean anyDestroyed = false;
        int done = 0;

        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (done >= MAX_PER_TICK) break;

            BlockPos target = pos.immutable();
            if (target.equals(standOn)) continue;

            BlockState state = level.getBlockState(target);
            if (state.isAir()) continue;
            if (state.getBlock() instanceof LiquidBlock) continue;
            if (state.getDestroySpeed(level, target) < 0.0F) continue;   // 基岩这种挖不动的跳过

            level.destroyBlock(target, false);                           // false = 不掉落
            level.sendParticles(ParticleTypes.EXPLOSION,
                    target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, 1, 0.0, 0.0, 0.0, 0.0);

            anyDestroyed = true;
            done++;
        }

        // 声音一轮只播一次 —— 每块都播会吵成一锅粥（主人说的"很奇怪"有一半是这个）
        if (anyDestroyed) {
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS,
                    0.35F, 1.5F + level.random.nextFloat() * 0.2F);
        }
    }
}
