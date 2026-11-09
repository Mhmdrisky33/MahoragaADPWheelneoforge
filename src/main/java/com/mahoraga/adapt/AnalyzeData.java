package com.mahoraga.adapt;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 「解析」的存档 —— 存在玩家自己的 PersistentData 里（跨重登、死亡自动保留）。
 *
 * 结构：
 * <pre>
 * mahoraga_analyze
 *   └─ &lt;生物注册名&gt;              （一个生物一份记录）
 *        tg  = int    已识别的标签（{@link AnalyzeTag#mask()} 位掩码）
 *        dg  = int    连续打空次数（判 DODGE）
 *        hp  = float  上一次攻击结算后它的血量
 *        dm  = float  上一次攻击造成的伤害
 *        px/py/pz = double  上一次攻击时它的位置（判 TELEPORT）
 *        sn  = int    累计被观察的次数
 *        cr  = list   已经抽到的「独特破解」id
 *        bf  = list   已经抽到的「自增益」id
 *
 * mahoraga_crack_cd            （玩家级：每个破解的下次可用时间，不按生物分）
 *   └─ &lt;破解id&gt; = long
 * </pre>
 *
 * ⚠️ 标签用**位掩码 int** 存 —— 比塞 10 个 boolean 省事，也省 NBT 体积。
 */
public final class AnalyzeData {

    /**
     * 🔴 抽签窗口宽度 —— 每满这么多个百分点给一次机会。
     *
     * <p>2026-09-15 从 <b>100 放宽到 50</b>（主人："限制目前太多了"）——
     * 原来要练到 100% 才有第一次机会、而且每 100% 才一次，节奏太慢；
     * 现在 50% 就开抽，机会数直接翻倍（50/100/…/1000 共 20 次）。
     */
    public static final int DRAW_STEP = 50;
    private static final String ROOT = "mahoraga_analyze";
    private static final String COOLDOWN_ROOT = "mahoraga_crack_cd";

    private static final String K_TAGS = "tg";
    private static final String K_DODGE = "dg";
    private static final String K_HP = "hp";
    private static final String K_DMG = "dm";
    private static final String K_X = "px";
    private static final String K_Y = "py";
    private static final String K_Z = "pz";
    private static final String K_SEEN = "sn";
    private static final String K_CRACKS = "cr";
    private static final String K_BUFFS = "bf";
    private static final String K_MAX_HP = "mh";
    private static final String K_NEXT_DRAW = "nd";
    private static final String K_FIRED = "fd";

    private final CompoundTag root;
    private final CompoundTag cooldowns;

    private AnalyzeData(CompoundTag persistent) {
        if (!persistent.contains(ROOT, Tag.TAG_COMPOUND)) persistent.put(ROOT, new CompoundTag());
        if (!persistent.contains(COOLDOWN_ROOT, Tag.TAG_COMPOUND)) {
            persistent.put(COOLDOWN_ROOT, new CompoundTag());
        }

        this.root = persistent.getCompound(ROOT);
        this.cooldowns = persistent.getCompound(COOLDOWN_ROOT);
    }

    public static AnalyzeData of(Player player) {
        return new AnalyzeData(player.getPersistentData());
    }

    private CompoundTag record(String id) {
        if (!this.root.contains(id, Tag.TAG_COMPOUND)) this.root.put(id, new CompoundTag());
        return this.root.getCompound(id);
    }

    /** 这只生物有没有解析记录（**只读**：不存在也不会凭空建一个）。 */
    public boolean hasRecord(String id) {
        return this.root.contains(id, Tag.TAG_COMPOUND);
    }

    /** 把这笔记录整个删掉（血太少的怪用它擦屁股）。 */
    public void clearRecord(String id) {
        this.root.remove(id);
    }

    // ==================== 最大生命（「特殊适应」的门槛依据）====================

    /** 记一笔"这只生物最大生命是多少"。只读，没记录就是 0。 */
    public float maxHealth(String id) {
        return this.root.getCompound(id).getFloat(K_MAX_HP);
    }

    public void setMaxHealth(String id, float value) {
        record(id).putFloat(K_MAX_HP, value);
    }

    // ==================== 下一次抽签的档位 ====================

    /**
     * 下一个抽取档位（百分比）—— **默认 100**。
     *
     * 每抽到一张就 +{@link #DRAW_STEP}，超过 {@code CrackPool.MAX_PCT} 就表示机会用完了。
     * 只读，没记录就是从第一档开始。
     */
    public int nextDraw(String id) {
        CompoundTag rec = this.root.getCompound(id);
        return rec.contains(K_NEXT_DRAW) ? rec.getInt(K_NEXT_DRAW) : DRAW_STEP;
    }

    public void setNextDraw(String id, int milestone) {
        record(id).putInt(K_NEXT_DRAW, milestone);
    }

    /** 所有解析过的生物（注册名）。 */
    public Set<String> ids() {
        return this.root.getAllKeys();
    }

    // ==================== 标签 ====================

    public boolean hasTag(String id, AnalyzeTag tag) {
        return (record(id).getInt(K_TAGS) & tag.mask()) != 0;
    }

    /** @return 这次是不是**新识别**到的（用来播报） */
    public boolean addTag(String id, AnalyzeTag tag) {
        CompoundTag rec = record(id);
        int mask = rec.getInt(K_TAGS);

        if ((mask & tag.mask()) != 0) return false;

        rec.putInt(K_TAGS, mask | tag.mask());
        return true;
    }

    public int tagCount(String id) {
        return Integer.bitCount(record(id).getInt(K_TAGS));
    }

    // ==================== 打空计数 ====================

    public int dodge(String id) {
        return record(id).getInt(K_DODGE);
    }

    public void setDodge(String id, int value) {
        record(id).putInt(K_DODGE, value);
    }

    // ==================== 上一次攻击的快照 ====================

    public float lastHp(String id) {
        return record(id).getFloat(K_HP);
    }

    public float lastDamage(String id) {
        return record(id).getFloat(K_DMG);
    }

    public boolean hasSnapshot(String id) {
        return record(id).getInt(K_SEEN) > 0;
    }

    public int seen(String id) {
        return record(id).getInt(K_SEEN);
    }

    public void snapshot(String id, float hp, float damage, double x, double y, double z) {
        CompoundTag rec = record(id);

        rec.putFloat(K_HP, hp);
        rec.putFloat(K_DMG, damage);
        rec.putDouble(K_X, x);
        rec.putDouble(K_Y, y);
        rec.putDouble(K_Z, z);
        rec.putInt(K_SEEN, rec.getInt(K_SEEN) + 1);
    }

    /** 上一次攻击时它在哪。 */
    public double[] lastPos(String id) {
        CompoundTag rec = record(id);
        return new double[]{rec.getDouble(K_X), rec.getDouble(K_Y), rec.getDouble(K_Z)};
    }

    // ==================== 抽到的破解 / 自增益 ====================

    public List<String> cracks(String id) {
        return readList(record(id), K_CRACKS);
    }

    public void addCrack(String id, String crack) {
        addToList(record(id), K_CRACKS, crack);
    }

    /**
     * 已经**真正打出去过**的破解 —— 每个破解对同一只生物**只触发一次**（主人 2026-09-14 定）。
     *
     * 抽到的（`cr`）和打出去的（`fd`）是两份：`cr` 决定"不会再被抽到"，
     * `fd` 决定"不会再被打出去"。少了 `fd` 的话，同一只怪身上同一个破解会每隔 4 秒刷一次。
     */
    public List<String> fired(String id) {
        return readList(record(id), K_FIRED);
    }

    public void markFired(String id, String crack) {
        addToList(record(id), K_FIRED, crack);
    }

    public List<String> buffs(String id) {
        return readList(record(id), K_BUFFS);
    }

    public void addBuff(String id, String buff) {
        addToList(record(id), K_BUFFS, buff);
    }

    private static List<String> readList(CompoundTag rec, String key) {
        ListTag list = rec.getList(key, Tag.TAG_STRING);
        List<String> out = new ArrayList<>(list.size());

        for (int i = 0; i < list.size(); i++) out.add(list.getString(i));
        return out;
    }

    private static void addToList(CompoundTag rec, String key, String value) {
        ListTag list = rec.getList(key, Tag.TAG_STRING);
        list.add(StringTag.valueOf(value));
        rec.put(key, list);
    }

    // ==================== 破解冷却（玩家级）====================

    public long cooldownUntil(String crackId) {
        return this.cooldowns.getLong(crackId);
    }

    public void setCooldownUntil(String crackId, long until) {
        this.cooldowns.putLong(crackId, until);
    }

    // ==================== 清空 ====================

    public void clear() {
        for (String key : Set.copyOf(this.root.getAllKeys())) this.root.remove(key);
        for (String key : Set.copyOf(this.cooldowns.getAllKeys())) this.cooldowns.remove(key);
    }
}
