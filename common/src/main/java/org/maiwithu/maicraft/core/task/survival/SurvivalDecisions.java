package org.maiwithu.maicraft.core.task.survival;


/**
 * 只回答某种自救现在是否需要介入，不操作玩家。调用方负责读取血量、空气和速度。
 * 多种危险同时出现时谁先处理，由 MaiCraftCore 注册自救任务的顺序决定。
 */
public final class SurvivalDecisions {

    private SurvivalDecisions() {}

    // ---- fall thresholds ----
    /**
     * 竖直速度为负表示下落；低于这个数值时，这个简单判据把它视为快速坠落。
     * 实际落地自救入口还会通过 EmergencyLanding 检查当前身体状态和可用方案。
     */
    public static final double MLG_FALL_SPEED = -0.7;

    /** 有威胁就触发。 */
    public static boolean mobDefenseTriggered(boolean threatPresent) {
        return threatPresent;
    }

    /**
     * 这个简单判据要求：尚未落稳、存在救助办法，并且下落速度达到阈值。是否真有救助办法由调用方判断。
     */
    public static boolean mlgTriggered(boolean grounded, double fallSpeed, boolean canSave) {
        if (grounded) return false;
        if (!canSave) return false;
        return fallSpeed <= MLG_FALL_SPEED;
    }

    /**
     * 下落慢于这个速度时，可与仍在快速坠落的状态区分；数值本身不证明已经安全落地。
     */
    public static final double MLG_SETTLED_SPEED = -0.5;

    /**
     * 头还在水下，而且剩余空气已经不足以覆盖预计上浮用量时，开始换气自救。
     */
    public static boolean breathTriggered(boolean headUnderWater, int airSupply, int ascentAir) {
        return headUnderWater && airSupply <= Math.max(1, ascentAir);
    }

    /**
     * 开始换气后，不在头刚露出水面时立即交还控制权，而是等空气补满，避免刚露头又被导航带回水下。
     * 如果已经离水并站到地面上，可以直接结束；空气上限读取当前玩家的值，以适应其他 Mod。
     */
    public static boolean breathRecoveryRequired(boolean bodyInWater,
                                                 boolean headUnderWater,
                                                 boolean grounded,
                                                 int airSupply,
                                                 int maxAirSupply) {
        if (!bodyInWater && !headUnderWater && grounded) return false;
        return headUnderWater || airSupply < maxAirSupply;
    }
}
