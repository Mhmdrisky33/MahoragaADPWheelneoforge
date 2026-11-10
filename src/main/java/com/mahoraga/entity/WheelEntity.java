package com.mahoraga.entity;

import com.mahoraga.handler.WheelHandler;
import com.mahoraga.registry.ModEntities;
import com.mahoraga.registry.ModSounds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;

/** 退魔法轮：骑在拥有者身上、悬在头顶、会自己旋转。 */
public class WheelEntity extends Entity implements GeoEntity {
    private static final EntityDataAccessor<Integer> DATA_SPIN =
            SynchedEntityData.defineId(WheelEntity.class, EntityDataSerializers.INT);
    /**
     * 主人的实体 id，**同步给客户端**。
     *
     * 为什么必须有它：客户端拿不到 UUID → 实体 的查询（那只有 ServerLevel 才有），
     * 只靠 UUID 的话，一旦 cachedOwner 指向的旧实体被移除（死亡重生就是这种情况），
     * 客户端 getOwner() 会返回 null → 渲染器拿不到主人 → 轮子就不画了（能力却还在）。
     */
    private static final EntityDataAccessor<Integer> DATA_OWNER =
            SynchedEntityData.defineId(WheelEntity.class, EntityDataSerializers.INT);

    /**
     * <b>永久法轮</b> —— 挂给式神（魔虚罗）的那一只。
     *
     * <p>普通的法轮是"玩家带着它"的产物，所以 {@code tick()} 里会检查
     * {@code WheelHandler.hasWheel(ownerUUID)}，玩家没法轮就自毁。
     * 但式神**永远不在那个名单里** —— 没有这个标记的话，
     * 挂上去的法轮下一 tick 就被自己删掉了。
     */
    private static final EntityDataAccessor<Boolean> DATA_PERMANENT =
            SynchedEntityData.defineId(WheelEntity.class, EntityDataSerializers.BOOLEAN);

    private static final RawAnimation SPIN = RawAnimation.begin().thenPlay("misc.spin");
    private static final int SPIN_DURATION = 20;

    /** 长时间找不到主人就自毁（10 秒），让 handler 重新生成一只。 */
    private static final int ORPHAN_TIMEOUT = 200;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    @Nullable
    private UUID ownerUUID;
    @Nullable
    private LivingEntity cachedOwner;
    private int orphanTicks;

    public WheelEntity(EntityType<?> type, Level level) {
        super(type, level);
    }

    public WheelEntity(LivingEntity owner) {
        this(owner, false);
    }

    /**
     * @param permanent {@code true} = 挂给**式神**（魔虚罗）的那种。
     *                  普通的法轮由 {@code WheelHandler} 每 tick 维护，主人没法轮就该消失；
     *                  式神那只没有"法轮标记"可查，必须显式声明成永久的。
     */
    public WheelEntity(LivingEntity owner, boolean permanent) {
        this(ModEntities.WHEEL.get(), owner.level());

        this.setPermanent(permanent);
        this.setOwner(owner);
        this.setPos(owner.position());
        this.startRiding(owner, true);
    }

    @Override
    public boolean shouldRender(double x, double y, double z) {
        return true;
    }

    @Override
    public Vec3 getVehicleAttachmentPoint(Entity vehicle) {
        return new Vec3(0.0D, 0.5D, 0.0D);
    }

    @Nullable
    public UUID getOwnerUUID() {
        return this.ownerUUID;
    }

    /** 用 UUID 判断归属，比拿实体引用比较可靠（死亡重生会换实体）。 */
    public boolean isOwnedBy(@Nullable Entity entity) {
        return entity != null && this.ownerUUID != null && this.ownerUUID.equals(entity.getUUID());
    }

    /** 让轮盘转一圈（适应完成时播）。 */
    public void spin() {
        this.entityData.set(DATA_SPIN, SPIN_DURATION);

        // 显式广播：Entity#playSound 那句只在本机 world 上发声，
        // 走到服务端时很容易"看得见动画听不见声"，这里直接对附近所有人播。
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                ModSounds.WHEEL.get(), SoundSource.PLAYERS, 6.0F, 1.0F);

        this.triggerAnim("Spin", "misc.spin");
    }

    public void setOwner(@Nullable LivingEntity owner) {
        if (owner == null) return;

        this.ownerUUID = owner.getUUID();
        this.cachedOwner = owner;
        this.entityData.set(DATA_OWNER, owner.getId());
    }

    /** 服务端 / 客户端都能解析出主人。 */
    @Nullable
    public LivingEntity getOwner() {
        if (this.cachedOwner != null && !this.cachedOwner.isRemoved()) {
            return this.cachedOwner;
        }

        // 1) 用同步过来的实体 id 找（客户端也能用）
        int id = this.entityData.get(DATA_OWNER);

        if (id != 0) {
            Entity entity = this.level().getEntity(id);

            if (entity instanceof LivingEntity living && !living.isRemoved()) {
                this.cachedOwner = living;
                return living;
            }
        }

        // 2) 退一步：我正骑着谁，谁就是主人
        if (this.getVehicle() instanceof LivingEntity vehicle && !vehicle.isRemoved()) {
            this.setOwner(vehicle);
            return vehicle;
        }

        // 3) 最后才走 UUID（只有服务端有）→ 解析出来后把 id 同步给客户端
        if (this.ownerUUID != null && this.level() instanceof ServerLevel server) {
            Entity entity = server.getEntity(this.ownerUUID);

            if (entity instanceof LivingEntity living) {
                this.setOwner(living);
                return living;
            }
        }
        return this.cachedOwner;
    }

    /**
     * 跟上主人。
     *
     * 关键点是判据用 **getVehicle() != owner**，而不是 **!isPassenger()** ——
     * 玩家死亡时旧实体被移除，法轮会卡在"骑着一个已经不存在的玩家"的状态里，
     * isPassenger() 永远返回 true，于是永远不再重新上车，就地留在死亡点。
     */
    public void followOwner(LivingEntity owner) {
        if (owner.level() != this.level()) return;

        if (this.getVehicle() != owner) {
            this.startRiding(owner, true);
        }
        if (!this.isPassenger()) {
            // 兜底：实在骑不上就直接贴坐标，绝对不留在原地
            this.setPos(owner.getX(), owner.getY(), owner.getZ());
        }
    }

    /** 位置对不上（换维度 / 重生）时，从 handler 里拽回来。 */
    public void snapToOwner(LivingEntity owner) {
        if (owner.level() != this.level()) return;

        this.setPos(owner.getX(), owner.getY(), owner.getZ());
        this.setDeltaMovement(0.0D, 0.0D, 0.0D);
        this.followOwner(owner);
    }

    @Override
    public void tick() {
        super.tick();

        // —— 客户端：什么都不判、什么都不删。
        // 客户端没有 ServerLevel，也没有 WheelHandler 的持有名单，
        // 任何"服务端语义"的判断放这里都会误杀轮子。
        if (this.level().isClientSide) return;

        // ⚠️ 2026-09-18 加的 `isPermanent()` 例外：
        //    法轮挂到**魔虚罗**头上时，owner 是式神不是玩家，而
        //    `WheelHandler.hasWheel(式神UUID)` **永远返回 false**（那个名单里只装玩家）→
        //    法轮**生成的下一 tick 就把自己删了**。
        //    症状极具迷惑性：日志里 "wheel attached" 明明成功了，进游戏却看不到轮子。
        if (!this.isPermanent() && (this.ownerUUID == null || !WheelHandler.hasWheel(this.ownerUUID))) {
            this.discard();
            return;
        }

        LivingEntity owner = this.getOwner();

        if (owner == null || owner.isRemoved()) {
            // 找不到主人（死亡→重生的那一瞬间）：先原地等，别急着删，
            // 删了再生成会出现"一闪一闪"。
            if (++this.orphanTicks > ORPHAN_TIMEOUT) {
                this.discard();
                return;
            }
        } else if (owner.level() != this.level()) {
            this.discard();                       // 主人去别的维度了，这只作废
            return;
        } else {
            this.orphanTicks = 0;
            this.followOwner(owner);

            if (this.discardDuplicates()) return;
        }

        int spin = this.entityData.get(DATA_SPIN);

        if (spin > 0) {
            this.entityData.set(DATA_SPIN, spin - 1);
        }
    }

    /** 同一主人的法轮只留一只（编号最小的那只），避免重新生成后越积越多。 */
    private boolean discardDuplicates() {
        for (WheelEntity other : this.level().getEntitiesOfClass(WheelEntity.class,
                this.getBoundingBox().inflate(4.0D),
                w -> w.getId() < this.getId() && Objects.equals(w.ownerUUID, this.ownerUUID))) {
            this.discard();
            return true;
        }
        return false;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_SPIN, 0);
        builder.define(DATA_OWNER, 0);
        builder.define(DATA_PERMANENT, false);
    }

    /** 这只是"永久法轮"吗（挂给式神用的那种，不会因为主人没法轮标记而自毁）。 */
    public boolean isPermanent() {
        return this.entityData.get(DATA_PERMANENT);
    }

    public void setPermanent(boolean permanent) {
        this.entityData.set(DATA_PERMANENT, permanent);
    }

    @Override
    protected void readAdditionalSaveData(@NotNull CompoundTag tag) {
        if (tag.hasUUID("owner")) {
            this.ownerUUID = tag.getUUID("owner");
        }
        this.entityData.set(DATA_SPIN, tag.getInt("spin"));
    }

    @Override
    protected void addAdditionalSaveData(@NotNull CompoundTag tag) {
        if (this.ownerUUID != null) {
            tag.putUUID("owner", this.ownerUUID);
        }
        tag.putInt("spin", this.entityData.get(DATA_SPIN));
    }

    private PlayState spinPredicate(AnimationState<WheelEntity> state) {
        if (this.entityData.get(DATA_SPIN) > 0) {
            return state.setAndContinue(SPIN);
        }
        state.getController().forceAnimationReset();
        return PlayState.STOP;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar registrar) {
        registrar.add(new AnimationController<>(this, "Spin", this::spinPredicate));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    @Override
    public @NotNull Packet<ClientGamePacketListener> getAddEntityPacket(ServerEntity serverEntity) {
        Entity owner = this.getOwner();
        return new ClientboundAddEntityPacket(this, serverEntity, owner == null ? 0 : owner.getId());
    }

    @Override
    public void recreateFromPacket(@NotNull ClientboundAddEntityPacket packet) {
        super.recreateFromPacket(packet);

        Entity owner = this.level().getEntity(packet.getData());

        if (owner instanceof LivingEntity living) {
            this.setOwner(living);
        }
    }
}
