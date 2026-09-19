// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.build.BuildingBudgets;
import static org.maiwithu.maicraft.client.preview.PreviewSectionRefresh.Work.REBUILD;
import static org.maiwithu.maicraft.client.preview.PreviewSectionRefresh.Work.RESORT;

/** 用私有启动目录切换预算快照；预览类先加载也应采用新启动配置，文件编辑本身不能热更新或授予施工权。 */
public final class PreviewBuildingBudgetTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Path defaults = Files.createTempDirectory("maicraft-preview-default-budgets-");
        try {
            BuildingBudgets.initialize(defaults); defaultPreviewCapacity(); configuredStartupSnapshots(); largeNumericBudgets();
        } finally {
            // 恢复默认启动快照，避免本用例的小预算误伤后面的正常预览测试；不触碰用户真实游戏目录。
            BuildingBudgets.initialize(defaults);
        }
        System.out.println("PreviewBuildingBudgetTest: startup snapshots, preview capacity, distance and shared frame limits passed");
    }

    private static void defaultPreviewCapacity() {
        check(PreviewSession.maxCells() == 262_144 && PreviewRenderer.viewDistanceSquared() == 256.0 * 256,
                "the new defaults replace the old 65536-cell and 128-block preview restrictions");
        var cells = new LinkedHashMap<BlockPos, BlockState>();
        for (int i = 0; i < 65_537; i++) cells.put(new BlockPos(i % 512, 64, i / 512), Blocks.STONE.defaultBlockState());
        var session = new PreviewSession("large-plan", "minecraft:overworld", "large preview", cells);
        check(session.cells().size() == 65_537 && session.decision() == PreviewSession.Decision.WAITING,
                "a real plan above the previous ceiling is retained and still awaits explicit review");
    }

    private static void configuredStartupSnapshots() throws Exception {
        var clock = new AtomicLong(); var previousFrame = new PreviewFrameBudget(clock::get);
        Path game = Files.createTempDirectory("maicraft-preview-configured-budgets-");
        Path file = game.resolve(BuildingBudgets.CONFIG_PATH); Files.createDirectories(file.getParent());
        Files.writeString(file, """
                preview.maxCells=2
                preview.distance=8
                preview.frameMillis=0.25
                preview.preparationSteps=2
                preview.rebuildCells=3
                preview.resortSections=1
                """);
        BuildingBudgets.initialize(game);
        check(PreviewSession.maxCells() == 2 && PreviewRenderer.viewDistanceSquared() == 64,
                "already loaded preview classes use the newly installed startup snapshot");
        var session = new PreviewSession("parts-count", "minecraft:overworld", "one block and one part",
                Map.of(BlockPos.ZERO, Blocks.STONE.defaultBlockState()), List.of(new PreviewPart(BlockPos.ZERO, "ae2:cable", "center")));
        rejectsLimit(() -> new PreviewSession("too-many", "minecraft:overworld", "one block and two parts",
                session.cells(), List.of(session.parts().getFirst(), session.parts().getFirst())));
        var frame = new PreviewFrameBudget(clock::get);
        check(frame.claimPreparation() && frame.claimPreparation() && !frame.claimPreparation(), "configured preparation steps are consumed independently");
        // 保留现有原子分区规则：剩一格额度也可完成下一个两格分区，随后本帧不能再启动新重建。
        check(frame.claim(REBUILD, 2) && frame.claim(REBUILD, 2) && !frame.claim(REBUILD, 1), "configured rebuilding keeps the existing complete-section handoff");
        check(frame.claim(RESORT, 1) && !frame.claim(RESORT, 1), "configured sort count is independent of exhausted rebuild/preparation budgets");
        clock.set(249_999); check(frame.hasTime(), "fractional milliseconds retain nanosecond precision");
        clock.set(250_000); check(!frame.hasTime() && previousFrame.hasTime(), "one frame keeps its own shared clock allowance across a startup-snapshot change");
        clock.set(1_999_999); check(previousFrame.hasTime(), "the new default shared frame duration is two milliseconds");
        clock.set(2_000_000); check(!previousFrame.hasTime(), "the default frame yields at its exact configured boundary");
        Files.writeString(file, """
                preview.maxCells=7
                preview.distance=9
                preview.frameMillis=1.5
                preview.preparationSteps=5
                preview.rebuildCells=4
                preview.resortSections=2
                """);
        check(PreviewSession.maxCells() == 2 && PreviewRenderer.viewDistanceSquared() == 64,
                "editing the file alone must not change the current running preview budgets");
        BuildingBudgets.initialize(game);
        check(PreviewSession.maxCells() == 7 && PreviewRenderer.viewDistanceSquared() == 81,
                "a later startup reads all changed preview settings without class reloading");
        var later = new PreviewFrameBudget(clock::get);
        for (int i = 0; i < 5; i++) check(later.claimPreparation(), "the later startup applies its preparation count");
        check(!later.claimPreparation() && later.claim(REBUILD, 4) && !later.claim(REBUILD, 1)
                && later.claim(RESORT, 1) && later.claim(RESORT, 1) && !later.claim(RESORT, 1), "all work counters use the same new startup snapshot");
        var explicitRefresh = new PreviewFrameBudget(clock::get, 100, 1, 1);
        for (int i = 0; i < 5; i++) check(explicitRefresh.claimPreparation(), "the compatibility constructor no longer conceals an old fixed preparation count");
        check(!explicitRefresh.claimPreparation() && session.decision() == PreviewSession.Decision.WAITING,
                "changing presentation budgets cannot confirm an existing construction preview");
    }

    private static void largeNumericBudgets() throws Exception {
        Path game = Files.createTempDirectory("maicraft-preview-large-budgets-"); Path file = game.resolve(BuildingBudgets.CONFIG_PATH);
        Files.createDirectories(file.getParent()); Files.writeString(file, "preview.maxCells=2147483647\npreview.distance=2147483647\n");
        BuildingBudgets.initialize(game);
        check(PreviewRenderer.viewDistanceSquared() == (double) Integer.MAX_VALUE * Integer.MAX_VALUE,
                "a large positive configured distance must not overflow an integer square");
        // 只用size声明模拟Java集合边界；越界必须在复制前拒绝，测试无需分配数十亿个方块。
        Map<BlockPos, BlockState> enormous = new AbstractMap<>() {
            @Override public int size() { return Integer.MAX_VALUE; }
            @Override public Set<Entry<BlockPos, BlockState>> entrySet() { throw new AssertionError("an over-budget preview tried to copy its cells"); }
        };
        rejectsLimit(() -> new PreviewSession("overflow", "minecraft:overworld", "integer boundary", enormous,
                List.of(new PreviewPart(BlockPos.ZERO, "ae2:cable", "center"))));
        var clock = new AtomicLong(Long.MAX_VALUE - 10); var frame = new PreviewFrameBudget(clock::get, 20, 1, 1, 1);
        check(frame.hasTime(), "starting near nanoTime's signed boundary must not expire a fresh frame");
        clock.set(Long.MIN_VALUE + 8); check(frame.hasTime(), "elapsed-time budgeting survives the clock's signed wrap");
        clock.set(Long.MIN_VALUE + 9); check(!frame.hasTime(), "the wrapped clock still reaches the exact twenty-nanosecond deadline");
    }
    private static void rejectsLimit(Runnable create) {
        try { create.run(); } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("preview.maxCells") && expected.getMessage().contains(BuildingBudgets.CONFIG_PATH),
                    "capacity rejection should name the effective configuration setting and file"); return;
        }
        throw new AssertionError("an over-budget preview was accepted");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
