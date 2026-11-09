package com.mahoraga.adapt;

import net.minecraft.network.chat.Component;

/**
 * 「解析标签」—— 你从**一次次攻击**里看穿的目标特性。
 *
 * 攻击适应打到 100%（解析完成）时，把这时攒到的标签交给
 * {@link com.mahoraga.skill.CrackPool} 去抽取对应的「独特破解」。
 *
 * ⚠️ **标签不是"这只怪的属性"，是"你观察到的现象"** ——
 * 所以判定全部基于**玩家的攻击数据**（打了多少、掉没掉血、它有没有回血/传送/召唤），
 * 而不是去读生物内部的字段（原版生物根本没有"回血速度""闪避率"这种量）。
 */
public enum AnalyzeTag {
    /** 最大生命 ≥ 100 —— 血厚。 */
    HIGH_HP("high_hp"),
    /** 上次打完它之后，它自己把血涨回去了 —— 会回血。 */
    FAST_HEAL("fast_heal"),
    /** 这一刀砍下去几乎没掉血 —— 锁血。 */
    LOCK_BLOOD("lock_blood"),
    /** 护甲 ≥ 15 —— 硬。 */
    HIGH_ARMOR("high_armor"),
    /** 掉血明显少于伤害 —— 有减伤。 */
    HIGH_REDUCTION("high_reduction"),
    /** 连着几次都没打实在 —— 会闪避 / 打不中。 */
    DODGE("dodge"),
    /** `canBeAffected()` 直接返回 false —— 吃不了状态效果。 */
    IMMUNE_DEBUFF("immune_debuff"),
    /** 战斗中位置凭空跳走 —— 会传送。 */
    TELEPORT("teleport"),
    /** 战斗中凭空多出以它为主的生物 —— 会召唤。 */
    SUMMONER("summoner"),
    /** 摸到它的时候处于无敌 —— 有阶段无敌。 */
    PHASE("phase"),
    /** 它一直在天上 / 天生会飞 —— 打不着。 */
    FLYER("flyer");

    private final String id;

    AnalyzeTag(String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }

    /** 用来在 NBT 里塞一个 int 位掩码。 */
    public int mask() {
        return 1 << ordinal();
    }

    public Component display() {
        return Component.translatable("mahoraga.tag." + this.id);
    }

    public static AnalyzeTag byId(String id) {
        for (AnalyzeTag tag : values()) {
            if (tag.id.equals(id)) return tag;
        }
        return null;
    }
}
