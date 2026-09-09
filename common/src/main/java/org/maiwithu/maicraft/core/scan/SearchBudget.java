package org.maiwithu.maicraft.core.scan;

/**
 * 给 BlockSearch 等使用它的扫描共享一份每刻预算，防止多个请求各自把游戏主线程占满。
 * 当前先调用者先用，不保证轮流分配。TargetIndex 另有自己的两毫秒预算，所以这里不是全项目所有搜索的唯一上限。
 */
public final class SearchBudget {

    /**
     * Cached presence checks are cheap; this caps loop work across ALL searches.
     *
     * <p>查询一律只读已加载的东西,没有"为了查而加载"这档额度——限流限不住它:名额是<b>发起前</b>
     * 检查的,一旦进了同步加载就再也收不回来,而一次冷区块的世界生成足以让单 tick 超过看门狗的
     * 六十秒。所以那条路是删掉的,不是限住的。
     */
    private static final int MAX_CHECKS_PER_TICK = 128;
    /**
     * Biome locator samples (pure climate-noise lookups, no chunk access; one
     * "sample" = one x/z column across all its Y probes). Cheaper than a
     * structure check, hence the larger pool — still under the shared 4ms lid.
     */
    private static final int MAX_BIOME_SAMPLES_PER_TICK = 256;
    /**
     * Block-scan section visits (one permit = one 16³ chunk section). The
     * palette pre-check makes a miss sub-microsecond and a hit ~50µs of
     * iteration, so a generous pool still sits safely under the 4ms lid.
     */
    private static final int MAX_SECTION_SCANS_PER_TICK = 256;
    /**
     * Wall-clock hard stop. The count caps bound the common case; this makes
     * the "never stalls the server" promise unconditional even when every
     * check goes cold to disk. 4ms ≈ 8% of a 50ms tick.
     */
    private static final long MAX_NANOS_PER_TICK = 4_000_000L;

    private static long stampTick = Long.MIN_VALUE;
    private static int checksLeft;
    private static int biomeSamplesLeft;
    private static int sectionScansLeft;
    private static long deadlineNanos;

    private SearchBudget() {}

    /** Reset the pool when the observed client world tick has advanced. */
    public static void refresh(long gameTime) {
        if (gameTime != stampTick) {
            resetForTick(gameTime);
        }
    }

    /** The actual pool reset; also the test seam. */
    // 每个新的游戏刻重新给列检查、群系采样和区块段扫描发额度，并设置本轮时间截止点。
    public static void resetForTick(long tick) {
        stampTick = tick;
        checksLeft = MAX_CHECKS_PER_TICK;
        biomeSamplesLeft = MAX_BIOME_SAMPLES_PER_TICK;
        sectionScansLeft = MAX_SECTION_SCANS_PER_TICK;
        deadlineNanos = System.nanoTime() + MAX_NANOS_PER_TICK;
    }

    /** Take one section-scan permit (one 16³ section); false = resume next tick. */
    // 额度或时间用完就让调用方留到下一刻；已经开始的一段扫描不会在这里被中途打断。
    public static boolean trySectionScan() {
        if (sectionScansLeft <= 0 || System.nanoTime() >= deadlineNanos) return false;
        sectionScansLeft--;
        return true;
    }

    /** Take one biome-sample permit; false = pool drained, resume next tick. */
    public static boolean tryBiomeSample() {
        if (biomeSamplesLeft <= 0 || System.nanoTime() >= deadlineNanos) return false;
        biomeSamplesLeft--;
        return true;
    }

    /** Take one candidate-check permit; false = pool drained, resume next tick. */
    public static boolean tryCheck() {
        if (checksLeft <= 0 || System.nanoTime() >= deadlineNanos) return false;
        checksLeft--;
        return true;
    }

}
