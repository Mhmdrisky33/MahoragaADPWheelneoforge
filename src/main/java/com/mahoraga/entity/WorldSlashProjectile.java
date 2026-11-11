package com.mahoraga.entity;

import com.mahoraga.registry.ModDamageTypes;
import com.mahoraga.registry.ModEntities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 「**空间斩**」—— 退魔之剑右键放出的那一道**横贯视野的巨大斩击**。
 *
 * ==================== 这是照着原模组的「世界斩」抄的 ====================
 *
 * 原模组里叫 {@code WorldSlashProjectile}（在 `radon.jujutsu_kaisen.entity.projectile`）。
 * 贴图（`world_slash.png`）、渲染方式（**加色发光的四边形**）、音效
 * （`SoundEvents.ELDER_GUARDIAN_CURSE`）**全部照抄**。
 *
 * 那套实体继承原模组的 {@code JujutsuProjectile} 基类、还牵着一堆内部类，抄不动；
 * 所以人家按同样的行为**重写了一个轻量版**：
 * - 沿视线直飞，约 10 tick（半秒）后自行消失；
 * - 刀身是一条**横着的长斩痕**，绕飞行轴按 roll 自转（潜行时转 90°，横劈变竖劈）；
 * - 扫到的东西**一击抹除**。
 *
 * ⚠️ **原版会一路把方块犁掉**（`getHits()` 里 `setBlockAndUpdate(AIR)`），
 * 我们**不拆方块** —— 主人试过剑气版"跟原版一致"，结论是生存里随手一挥就把家切碎了。
 * 空间斩同理，只斩生物。
 *
 * ⚠️ **不要在这里硬塞死亡**。原来那版写的是"打不死就 `setHealth(0) + die()`"，
 * 结果 Boss 的死亡流程被绕过 —— 表现是**Boss 还站在那儿、掉落物却先掉出来了**
 * （主人 2026-09-14 报的）。现在只发一次"必杀级"的伤害，死不死交给目标自己的逻辑。
 */
public class WorldSlashProjectile extends Projectile {
    private static final EntityDataAccessor<Float> DATA_ROLL =
            SynchedEntityData.defineId(WorldSlashProjectile.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_LENGTH =
            SynchedEntityData.defineId(WorldSlashProjectile.class, EntityDataSerializers.INT);

    /** 刀身最短 / 最长（格）。原模组就是 3 ~ 30。 */
    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 30;
    /** 活多久（tick）—— 原模组也是 10。 */
    private static final int DURATION = 10;
    /** 沿刀身取样、找目标的步长（格）。 */
    private static final double SAMPLE_STEP = 0.5D;
    /** 每个取样点判定盒的半边长（格）。 */
    private static final double HIT_RADIUS = 0.55D;

    private int life;
    /** 已经斩过的目标 —— 刀身 24 格长，同一个目标会被连续几 tick 扫到，必须去重。 */
    private final Set<UUID> hit = new HashSet<>();

    public WorldSlashProjectile(EntityType<? extends WorldSlashProjectile> type, Level level) {
        super(type, level);
        this.noCulling = true;
    }

    public WorldSlashProjectile(LivingEntity owner, float roll, int length, float speed) {
        super(ModEntities.WORLD_SLASH.get(), owner.level());

        this.setOwner(owner);
        this.setRoll(roll);
        this.setLength(length);
        this.setPos(owner.getX(), owner.getEyeY() - 0.5D, owner.getZ());
        this.setYRot(owner.getYRot());
        this.setXRot(owner.getXRot());
        this.yRotO = owner.getYRot();
        this.xRotO = owner.getXRot();
        this.setDeltaMovement(owner.getLookAngle().scale(speed));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_ROLL, 0.0F);
        builder.define(DATA_LENGTH, MAX_LENGTH);
    }

    public float getRoll() {
        return this.entityData.get(DATA_ROLL);
    }

    private void setRoll(float roll) {
        this.entityData.set(DATA_ROLL, roll);
    }

    public int getLength() {
        int length = this.entityData.get(DATA_LENGTH);
        return Math.max(MIN_LENGTH, Math.min(MAX_LENGTH, length));
    }

    private void setLength(int length) {
        this.entityData.set(DATA_LENGTH, Math.max(MIN_LENGTH, Math.min(MAX_LENGTH, length)));
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.life = tag.getInt("Life");
        this.setRoll(tag.getFloat("Roll"));
        this.setLength(tag.getInt("Length"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Life", this.life);
        tag.putFloat("Roll", this.getRoll());
        tag.putInt("Length", this.getLength());
    }

    @Override
    public void tick() {
        super.tick();

        if (!this.level().isClientSide) {
            this.scan();
            if (++this.life > DURATION) this.discard();
        }

        Vec3 move = this.getDeltaMovement();
        this.setPos(this.getX() + move.x, this.getY() + move.y, this.getZ() + move.z);
    }

    /**
     * 沿刀身扫一遍。
     *
     * 刀身长轴 = **垂直于飞行方向**、再**绕飞行轴按 roll 转一下** ——
     * 和渲染器里那三步旋转出来的结果是一个意思（横着的一条斩痕）。
     */
    private void scan() {
        if (!(this.getOwner() instanceof LivingEntity owner)) return;

        Vec3 forward = this.getDeltaMovement();
        if (forward.lengthSqr() < 1.0E-6D) return;
        forward = forward.normalize();

        // 先找一个和 forward 不平行的参考轴，叉乘出"垂直方向"
        Vec3 ref = Math.abs(forward.y) > 0.99D
                ? new Vec3(1.0D, 0.0D, 0.0D)
                : new Vec3(0.0D, 1.0D, 0.0D);

        Vec3 side = forward.cross(ref).normalize();

        // 绕飞行轴转 roll（原版就是这么转的）
        Quaternionf quat = new Quaternionf().rotateAxis((float) Math.toRadians(-this.getRoll()),
                (float) forward.x, (float) forward.y, (float) forward.z);
        side = new Vec3(quat.transform(side.toVector3f())).normalize();

        double half = this.getLength() / 2.0D;
        Vec3 center = this.position().add(0.0D, this.getBbHeight() / 2.0D, 0.0D);

        for (double t = -half; t <= half; t += SAMPLE_STEP) {
            Vec3 point = center.add(side.scale(t));
            AABB box = AABB.ofSize(point, HIT_RADIUS * 2.0D, HIT_RADIUS * 2.0D, HIT_RADIUS * 2.0D);

            for (LivingEntity victim : this.level().getEntitiesOfClass(LivingEntity.class, box, this::canHit)) {
                if (!this.hit.add(victim.getUUID())) continue;

                erase(owner, victim);
            }
        }
    }

    private boolean canHit(LivingEntity entity) {
        if (entity == this.getOwner()) return false;
        if (!entity.isAlive()) return false;

        return !(entity instanceof Player other)
                || !(this.getOwner() instanceof Player owner)
                || owner.canHarmPlayer(other);
    }

    /**
     * 一击抹除 —— 打穿血量上限（原模组的世界斩是按最大生命比例算，我们直接抹掉）。
     *
     * 🔴 **绝不再补一句 `setHealth(0) + die()`**：那样会绕过目标自己的死亡动画 / 掉落 /
     * 复活逻辑，Boss 就会出现"人还在、东西先掉了"。打不打得死，交给它自己判。
     */
    private static void erase(LivingEntity owner, LivingEntity victim) {
        DamageSource source = owner instanceof Player player
                ? ModDamageTypes.spell(player)
                : owner.damageSources().mobAttack(owner);

        victim.invulnerableTime = 0;
        victim.hurt(source, victim.getMaxHealth() + victim.getAbsorptionAmount() + 100.0F);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 16384.0D;
    }
}
