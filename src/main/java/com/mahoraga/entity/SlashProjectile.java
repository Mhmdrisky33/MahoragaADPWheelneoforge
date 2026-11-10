package com.mahoraga.entity;

import com.mahoraga.registry.ModEntities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 「剑气」—— 普通攻击挥出去的那道**飞行斩击**。
 *
 * 素材（贴图 / 破空音效）和渲染方式是**直接搬原模组的「解」**；
 * 实体本体是人家重写的轻量版 —— 原版的 {@code DismantleProjectile} 继承原模组
 * 的 {@code JujutsuProjectile} 基类、还挂了 14 个内部类，抄不动也没必要抄。
 *
 * 行为（照抄原版「解」）：
 * - 无重力，沿视线方向直飞；约 12 tick 后自行消失；
 * - 沿途**切割**命中的生物（最多穿 3 个），伤害 = 玩家的普通攻击力；
 * - **不打碎方块**（撞墙即散）。
 *
 * ⚠️ 原版「解」是一路犁过去、见什么拆什么的，我们**特意不这么做** ——
 * 主人 2026-09-14 试过"跟原版一致"那一版，结论是在生存里随手一挥就把自家房子切碎了，
 * 所以又改回"只切生物"。**别再加回来了。**
 *
 * ⚠️ 会**跳过挥剑那一刻的主目标** —— 不然近战 + 剑气会把同一个目标打两遍。
 */
public class SlashProjectile extends Projectile {
    private static final EntityDataAccessor<Float> DATA_ROLL =
            SynchedEntityData.defineId(SlashProjectile.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_LENGTH =
            SynchedEntityData.defineId(SlashProjectile.class, EntityDataSerializers.INT);

    /** 刀身最大长度（格）—— 渲染时按它铺开。 */
    public static final int MAX_LENGTH = 12;
    /** 能飞多久（tick）。 */
    private static final int MAX_LIFE = 12;
    /** 最多穿透几个目标。 */
    private static final int MAX_PIERCE = 3;

    private float damage;
    private int life;
    /** 已经砍过的目标 —— 同一个目标只砍一次（见 {@link #onHitEntity}）。 */
    private final Set<UUID> hit = new HashSet<>();
    /** 挥剑那一刻的主目标：不让剑气把它再打一遍。 */
    @Nullable
    private UUID ignored;

    public SlashProjectile(EntityType<? extends SlashProjectile> type, Level level) {
        super(type, level);
        this.noCulling = true;
    }

    /**
     * @param owner   挥剑的玩家
     * @param damage  单次伤害（＝玩家的普通攻击力）
     * @param roll    刀身自转角度（给每次挥砍一点随机感）
     * @param primary 本次挥剑的主目标（会被剑气跳过）
     */
    public SlashProjectile(LivingEntity owner, float damage, float roll, @Nullable Entity primary) {
        super(ModEntities.SLASH.get(), owner.level());

        this.setOwner(owner);
        this.damage = damage;
        this.ignored = primary == null ? null : primary.getUUID();

        this.setRoll(roll);
        this.setLength(MAX_LENGTH);
        this.setPos(owner.getX(), owner.getEyeY() - 0.25D, owner.getZ());
        this.setYRot(owner.getYRot());
        this.setXRot(owner.getXRot());
        this.yRotO = owner.getYRot();
        this.xRotO = owner.getXRot();
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_ROLL, 0.0F);
        this.entityData.define(DATA_LENGTH, MAX_LENGTH);
    }

    public float getRoll() {
        return this.entityData.get(DATA_ROLL);
    }

    private void setRoll(float roll) {
        this.entityData.set(DATA_ROLL, roll);
    }

    public int getLength() {
        return this.entityData.get(DATA_LENGTH);
    }

    private void setLength(int length) {
        this.entityData.set(DATA_LENGTH, length);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.damage = tag.getFloat("Damage");
        this.life = tag.getInt("Life");
        this.setRoll(tag.getFloat("Roll"));
        this.setLength(tag.getInt("Length"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putFloat("Damage", this.damage);
        tag.putInt("Life", this.life);
        tag.putFloat("Roll", this.getRoll());
        tag.putInt("Length", this.getLength());
    }

    @Override
    public void tick() {
        super.tick();

        Vec3 next = this.position().add(this.getDeltaMovement());

        if (!this.level().isClientSide) {
            HitResult hit = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);

            if (hit.getType() != HitResult.Type.MISS) {
                this.onHit(hit);
            }
            if (++this.life > MAX_LIFE) {
                this.discard();
            }
        }

        this.setPos(next.x, next.y, next.z);
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        if (!super.canHitEntity(target)) return false;
        if (target == this.getOwner()) return false;

        return this.ignored == null || !this.ignored.equals(target.getUUID());
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);

        if (!(this.getOwner() instanceof LivingEntity owner)) return;

        // 同一个目标**只砍一次** —— 剑气刀身有 12 格长，体型大的怪（劫掠兽、末影龙）
        // 飞过去时可能被连续好几 tick 扫到，不去重的话一道剑气能打出"多重伤害"。
        if (!this.hit.add(result.getEntity().getUUID())) return;

        result.getEntity().invulnerableTime = 0;
        result.getEntity().hurt(this.sourceOf(owner), this.damage);

        if (this.hit.size() >= MAX_PIERCE) {
            this.discard();
        }
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        // 剑气只切生物，不打碎方块 —— 撞墙就散（见类注释）
        this.discard();
    }

    private DamageSource sourceOf(LivingEntity owner) {
        return owner instanceof Player player
                ? this.damageSources().playerAttack(player)
                : this.damageSources().mobAttack(owner);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 4096.0D;
    }
}
