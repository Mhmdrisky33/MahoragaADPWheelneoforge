package com.mahoraga.skill;

import com.mahoraga.MahoragaMod;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「空间适应」的攻击侧效果 —— 被锁定的目标**无法改变坐标、无法传送**。
 *
 * 做法不是去拦它的传送方法（那样就得 mixin 好几种生物），而是更霸道的一招：
 * 记下被锁那一刻的坐标，之后每 tick 把它**摁回原位**、速度归零。
 * 它自己想怎么传送、怎么瞬移都无所谓，下一个 tick 就被拽回来。
 *
 * 挂在自己的 {@code LivingTickEvent} 上，所以不用去世界表里捞实体，天然跨维度可用。
 */
@EventBusSubscriber(modid = MahoragaMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class SpaceLock {
    private static final Map<UUID, Lock> LOCKS = new ConcurrentHashMap<>();

    private SpaceLock() {
    }

    /** 锁住目标若干 tick。 */
    public static void lock(LivingEntity target, int ticks) {
        LOCKS.put(target.getUUID(), new Lock(target.getX(), target.getY(), target.getZ(),
                target.level().getGameTime() + ticks));
    }

    @SubscribeEvent
    public static void onLivingTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof LivingEntity entity)) return;
        if (entity.level().isClientSide) return;

        Lock lock = LOCKS.get(entity.getUUID());
        if (lock == null) return;

        if (entity.isRemoved() || entity.level().getGameTime() >= lock.until) {
            LOCKS.remove(entity.getUUID());
            return;
        }

        // 摁回原地：坐标 + 速度一起清，它想传送也传不走
        entity.setDeltaMovement(Vec3.ZERO);
        entity.setPos(lock.x, lock.y, lock.z);
        entity.fallDistance = 0.0F;
    }

    private record Lock(double x, double y, double z, long until) {
    }
}
