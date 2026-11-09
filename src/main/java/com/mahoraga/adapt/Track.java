package com.mahoraga.adapt;

/** 三套完全独立的适应轨迹。 */
public enum Track {
    /** 防御适应：被什么打，就适应什么。 */
    DEFENSE("def"),
    /** 攻击适应：打什么，就适应什么。 */
    ATTACK("atk"),
    /** 日常适应：一直做什么，就适应什么（快涨快掉）。 */
    DAILY("daily");

    private final String id;

    Track(String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }

    /**
     * 是不是日常适应。
     *
     * 日常走的是**另一套完全独立的逻辑**：没有保底、不会自动推到 150%、停下就立即衰减、
     * 也不播报"开始衰减"。所以凡是"按轨分派"的地方都先问这一句。
     */
    public boolean isDaily() {
        return this == DAILY;
    }
}
