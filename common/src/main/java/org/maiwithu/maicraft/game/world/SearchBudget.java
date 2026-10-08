package org.maiwithu.maicraft.core.scan;

/**
 * 给 BlockSearch 等使用它的扫描共享一份每刻预算，防止多个请求各自把游戏主线程占满。
 * 当前先调用者先用，不保证轮流分配。TargetIndex 另有自己的两毫秒预算，所以这里不是全项目所有搜索的唯一上限。
 */
public final class SearchBudget {

    /**
     * 缓存命中检查成本很低；此上限控制所有搜索的循环工作量。
     *
     * <p>查询一律只读已加载的东西,没有"为了查而加载"这档额度——限流限不住它:名额是<b>发起前</b>
     * 检查的,一旦进了同步加载就再也收不回来,而一次冷区块的世界生成足以让单 tick 超过看门狗的
     * 六十秒。所以那条路是删掉的,不是限住的。
     */
    private static final int MAX_CHECKS_PER_TICK = 128;
    /**
     * 生物群系定位采样（只查询气候噪声，不访问区块；一次“采样”指检查一个 x/z 柱列中的所有 Y 值）。
     * 其成本低于结构检查，因此配额更大，但仍受共享的 4 毫秒上限约束。
     */
    private static final int MAX_BIOME_SAMPLES_PER_TICK = 256;
    /**
     * 方块扫描区块访问量（一个许可对应一个 16³ 方块区段）。方块调色板预检查使未命中耗时不足一微秒，命中约迭代 50 微秒，
     * 因此即使配额较宽松，总耗时仍可安全保持在 4 毫秒以内。
     */
    private static final int MAX_SECTION_SCANS_PER_TICK = 256;
    /**
     * 墙上时间硬上限。数量配额可限制常见情况；即使每次检查都需要冷读磁盘，此上限也能保证服务器不会卡死。
     * 4 毫秒约占 50 毫秒游戏 tick 的 8%。
     */
    private static final long MAX_NANOS_PER_TICK = 4_000_000L;

    private static long stampTick = Long.MIN_VALUE;
    private static int checksLeft;
    private static int biomeSamplesLeft;
    private static int sectionScansLeft;
    private static long deadlineNanos;

    private SearchBudget() {}

    /** 观察到客户端世界 tick 推进后，重置共享配额池。 */
    public static void refresh(long gameTime) {
        if (gameTime != stampTick) {
            resetForTick(gameTime);
        }
    }

    /** 执行实际的配额池重置，也作为测试替换点。 */
    // 每个新的游戏刻重新给列检查、群系采样和区块段扫描发额度，并设置本轮时间截止点。
    public static void resetForTick(long tick) {
        stampTick = tick;
        checksLeft = MAX_CHECKS_PER_TICK;
        biomeSamplesLeft = MAX_BIOME_SAMPLES_PER_TICK;
        sectionScansLeft = MAX_SECTION_SCANS_PER_TICK;
        deadlineNanos = System.nanoTime() + MAX_NANOS_PER_TICK;
    }

    /** 消耗一个区段扫描许可（一个 16³ 区段）；返回 false 表示下个 tick 继续。 */
    // 额度或时间用完就让调用方留到下一刻；已经开始的一段扫描不会在这里被中途打断。
    public static boolean trySectionScan() {
        if (sectionScansLeft <= 0 || System.nanoTime() >= deadlineNanos) return false;
        sectionScansLeft--;
        return true;
    }

    /** 消耗一个生物群系采样许可；返回 false 表示配额耗尽，下个 tick 继续。 */
    public static boolean tryBiomeSample() {
        if (biomeSamplesLeft <= 0 || System.nanoTime() >= deadlineNanos) return false;
        biomeSamplesLeft--;
        return true;
    }

    /** 消耗一个候选检查许可；返回 false 表示配额耗尽，下个 tick 继续。 */
    public static boolean tryCheck() {
        if (checksLeft <= 0 || System.nanoTime() >= deadlineNanos) return false;
        checksLeft--;
        return true;
    }

}
