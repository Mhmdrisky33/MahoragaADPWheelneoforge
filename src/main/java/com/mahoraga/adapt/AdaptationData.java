package com.mahoraga.adapt;

import net.minecraft.nbt.CompoundTag;

import java.util.HashSet;
import java.util.Set;

/**
 * 适应进度的存取。全部挂在玩家的 PersistentData 下（自动随角色存档存读 →
 * 退出重进天然保留），跨维度 / 睡觉由外面主动清空。
 *
 * 每个 key 一共 6 份状态：
 *   进度值   self.<def|atk|daily>.<key>  = float   （0 ~ 10.0，也就是 0 ~ 1000%）
 *   状态     self.md.<track|key>         = byte    （IDLE/GROW/HOLD/DECAY，给 /mahoraga 看）
 *   保持到   self.hu.<track|key>         = long    （**战斗轨专用**：超过保底后撑到几点开始衰减）
 *   活跃到   self.ac.<track|key>         = long    （**日常轨专用**：最后一次做这个动作的 gameTime）
 *   已播报档 self.an.<track|key>         = int     （25/50/75/100/200/…，可回退）
 *   衰减播报 self.no.<track|key>         = byte    （本轮"开始衰减"是否播报过，**只有战斗轨用**）
 *
 * 两条轨道是**分开存、分开算**的：
 *  - 战斗轨（防御 / 攻击 / 药水）：`hu` 生效，有保底有保持期，会播报；
 *  - 日常轨（挖矿 / 奔跑 / 跳跃 / 泡水 / 泡岩浆 / 黑暗）：`ac` 生效，没有保底、
 *    停下就衰减、不播报。
 * 「已播报档位」和「状态」都是**可回退**的 —— 这是"掉下来再涨上去，提示还会再出现一次"
 * 和"每次衰减开始都能播报一次"的实现方式。
 */
public final class AdaptationData {
    private static final String ROOT = "mahoraga_adapt";
    /** NBT 里的子表名（别和下面的状态常量重名）。 */
    private static final String K_ANNOUNCED = "an";
    private static final String K_MODE = "md";
    private static final String K_HOLD = "hu";
    private static final String K_NOTICE = "no";
    /** 加速倍率：挨打 / 做动作越多，随时间自动涨得越快。 */
    private static final String K_BOOST = "bl";
    /**
     * 最后一次"碰到"的 gameTime。
     * **只有日常适应用它** —— 判定"还在不在做这个动作"：还在做就不衰减，停下就立即衰减。
     */
    private static final String K_ACTIVE = "ac";

    /** 什么都没发生（还没被种下种子）。 */
    public static final int IDLE = 0;
    /** 正在随时间自动增长（150% 保底线以下）。 */
    public static final int GROW = 1;
    /** 已经到保底线，但还在 30 秒保持期内。 */
    public static final int HOLD = 2;
    /** 保持期过了，超出保底的那部分正在衰减。 */
    public static final int DECAY = 3;

    private final CompoundTag playerData;
    private CompoundTag self;

    public AdaptationData(CompoundTag playerData) {
        this.playerData = playerData;
        this.self = playerData.contains(ROOT, 10) ? playerData.getCompound(ROOT) : new CompoundTag();
        playerData.put(ROOT, this.self);
    }

    // ==================== 进度值 ====================

    public float get(Track track, String key) {
        CompoundTag map = this.self.getCompound(track.id());
        return map.contains(key) ? map.getFloat(key) : 0.0F;
    }

    public void set(Track track, String key, float value) {
        CompoundTag map = this.self.contains(track.id(), 10)
                ? this.self.getCompound(track.id()) : new CompoundTag();

        if (value <= 0.0F) {
            map.remove(key);
        } else {
            map.putFloat(key, value);
        }
        this.self.put(track.id(), map);
    }

    /** 有进度值的 key（副本，别拿活视图去遍历 + 改）。 */
    public Set<String> keys(Track track) {
        return new HashSet<>(this.self.getCompound(track.id()).getAllKeys());
    }

    /** 已经彻底适应的条目数量（跨过 100% 免疫线）。 */
    public int completedCount(Track track) {
        int n = 0;

        for (String key : this.keys(track)) {
            if (this.get(track, key) >= AdaptationManager.COMPLETE) n++;
        }
        return n;
    }

    // ==================== 每个 key 的状态 ====================

    public int mode(Track track, String key) {
        return flat(K_MODE).getByte(tag(track, key));
    }

    public void setMode(Track track, String key, int mode) {
        put(K_MODE, track, key, mode <= IDLE ? null : (byte) mode);
    }

    public long holdUntil(Track track, String key) {
        return flat(K_HOLD).getLong(tag(track, key));
    }

    public void setHoldUntil(Track track, String key, long time) {
        put(K_HOLD, track, key, time <= 0L ? null : time);
    }

    /** 加速倍率。 */
    public float boost(Track track, String key) {
        return flat(K_BOOST).getFloat(tag(track, key));
    }

    public void setBoost(Track track, String key, float value) {
        put(K_BOOST, track, key, value <= 0.0F ? null : value);
    }

    /** 已经播报到的百分比档位（0 表示还没播报过任何档）。 */
    public int announced(Track track, String key) {
        return flat(K_ANNOUNCED).getInt(tag(track, key));
    }

    public void setAnnounced(Track track, String key, int percent) {
        put(K_ANNOUNCED, track, key, percent <= 0 ? null : percent);
    }

    /** 最后一次做这个动作的 gameTime（日常适应专用：靠它判断"还在不在做"）。 */
    public long activeAt(Track track, String key) {
        return flat(K_ACTIVE).getLong(tag(track, key));
    }

    public void setActiveAt(Track track, String key, long time) {
        put(K_ACTIVE, track, key, time <= 0L ? null : time);
    }

    /** 本轮"开始衰减"播报过了没有。 */
    public boolean noticeSent(Track track, String key) {
        return flat(K_NOTICE).getBoolean(tag(track, key));
    }

    public void setNoticeSent(Track track, String key, boolean sent) {
        put(K_NOTICE, track, key, sent ? Boolean.TRUE : null);
    }

    /** 彻底清掉一个 key 的全部状态。 */
    public void removeKey(Track track, String key) {
        set(track, key, 0.0F);
        setMode(track, key, IDLE);
        setHoldUntil(track, key, 0L);
        setBoost(track, key, 0.0F);
        setAnnounced(track, key, 0);
        setNoticeSent(track, key, false);
        setActiveAt(track, key, 0L);
    }

    private CompoundTag flat(String name) {
        return this.self.getCompound(name);
    }

    /** value 为 null 表示删掉这个条目。 */
    private void put(String name, Track track, String key, Object value) {
        CompoundTag map = this.self.contains(name, 10)
                ? this.self.getCompound(name) : new CompoundTag();
        String tag = tag(track, key);

        if (value == null) {
            map.remove(tag);
        } else if (value instanceof Byte b) {
            map.putByte(tag, b);
        } else if (value instanceof Float f) {
            map.putFloat(tag, f);
        } else if (value instanceof Long l) {
            map.putLong(tag, l);
        } else if (value instanceof Integer i) {
            map.putInt(tag, i);
        } else if (value instanceof Boolean b) {
            map.putBoolean(tag, b);
        }
        this.self.put(name, map);
    }

    private static String tag(Track track, String key) {
        return track.id() + "|" + key;
    }

    public void clear() {
        this.playerData.remove(ROOT);
        this.self = new CompoundTag();
        this.playerData.put(ROOT, this.self);
    }
}
