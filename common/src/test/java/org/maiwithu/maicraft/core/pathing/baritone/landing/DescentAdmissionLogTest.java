// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone.landing;

import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.DescentAdmissionLog;
import baritone.pathing.movement.movements.MovementDescend;
import baritone.pathing.precompute.PrecomputedData;
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.MutableMoveResult;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritonePolicy;
import org.maiwithu.maicraft.core.pathing.baritone.FallDamageBudget;
import java.io.File;
import net.minecraft.client.Minecraft;

/**
 * 检查被拒下降的记录契约：预测有伤且无保护的下降在准入闸门被拒时留下样本，
 * 无伤下降不记，同一落柱重复评估只记一条，新一轮寻路开始时清空。
 */
public final class DescentAdmissionLogTest {

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var f = new WaterLandingReplayTest.Fixture(false);
        Field gameThread = Minecraft.class.getDeclaredField("gameThread"); gameThread.setAccessible(true);
        gameThread.set(f.minecraft, Thread.currentThread());
        Field gameDirectory = Minecraft.class.getDeclaredField("gameDirectory"); gameDirectory.setAccessible(true);
        gameDirectory.set(f.minecraft, new File("descent-admission-log-fixture"));
        Field instance = Minecraft.class.getDeclaredField("instance"); instance.setAccessible(true);
        Object previous = instance.get(null);
        instance.set(null, f.minecraft);
        try { admission(f); } finally { instance.set(null, previous); }
        System.out.println("DescentAdmissionLogTest: passed");
    }

    private static void admission(WaterLandingReplayTest.Fixture f) throws Exception {
        var blocks = (BlocksView) f.memory.allocateInstance(BlocksView.class);
        blocks.world = f.world;
        Field bsiAccess = BlockStateInterface.class.getDeclaredField("access"); bsiAccess.setAccessible(true);
        bsiAccess.set(blocks, f.world);
        var context = (CalculationContext) f.memory.allocateInstance(CalculationContext.class);
        Field bsiField = CalculationContext.class.getDeclaredField("bsi"); bsiField.setAccessible(true);
        bsiField.set(context, blocks);
        Field worldField = CalculationContext.class.getDeclaredField("world"); worldField.setAccessible(true);
        worldField.set(context, f.world);
        Field precomputed = CalculationContext.class.getDeclaredField("precomputedData"); precomputed.setAccessible(true);
        precomputed.set(context, new PrecomputedData());
        Field fallOrigin = CalculationContext.class.getDeclaredField("fallOrigin"); fallOrigin.setAccessible(true);
        fallOrigin.set(context, new BlockPos(0, 6, 0));
        Field fallOriginY = CalculationContext.class.getDeclaredField("fallOriginY"); fallOriginY.setAccessible(true);
        fallOriginY.set(context, 6.0);
        Field budget = CalculationContext.class.getDeclaredField("fallDamageBudget"); budget.setAccessible(true);
        budget.set(context, new FallDamageBudget(20, 0, 3, 1, 0, 0, 0, 0, 0.08, 0, false));
        Field window = CalculationContext.class.getDeclaredField("waterLandingWindow"); window.setAccessible(true);
        window.set(context, new WaterLandingWindow(0.08, 4.5, 1.62, 0));
        Field policy = CalculationContext.class.getDeclaredField("maicraftPolicy"); policy.setAccessible(true);
        policy.set(context, EmbeddedBaritonePolicy.capture(null, null, null));
        context.minFallHeight = 3;
        inventory(context, Set.of());

        var result = new MutableMoveResult();
        DescentAdmissionLog.beginSearch();
        // 空背包 + 不允许自动备料：六格深落有预测伤害且无任何保护，闸门拒绝并留下样本。
        check(!MovementDescend.dynamicFallCost(context, 0, 6, 0, 1, 0, 0,
                        Blocks.AIR.defaultBlockState(), result)
                && result.cost == ActionCosts.COST_INF,
                "the unprotected injurious drop is rejected by the admission gate");
        var log = DescentAdmissionLog.snapshot();
        check((int) log.get("rejected_total") >= 1, "the rejected injurious drop is counted");
        List<?> samples = (List<?>) log.get("samples");
        check(!samples.isEmpty() && samples.getFirst().toString().contains("injurious_drop_without_protection"),
                "the rejection sample names the injurious reason: " + samples);
        check(samples.getFirst().toString().startsWith("1,"), "the sample points at the landing column, not the source");

        // 同一落柱被 A* 反复评估：总数累加，样本去重。
        int samplesBefore = samples.size();
        check(!MovementDescend.dynamicFallCost(context, 0, 6, 0, 1, 0, 0,
                        Blocks.AIR.defaultBlockState(), result),
                "the same drop is rejected again on re-evaluation");
        var afterRepeat = DescentAdmissionLog.snapshot();
        check((int) afterRepeat.get("rejected_total") > (int) log.get("rejected_total"),
                "repeat rejections keep incrementing the total");
        check(((List<?>) afterRepeat.get("samples")).size() == samplesBefore,
                "the same landing column is not sampled twice");

        // 三格无伤下降不走保护准入，也不产生拒绝记录。
        int totalBefore = (int) afterRepeat.get("rejected_total");
        result.reset();
        MovementDescend.dynamicFallCost(context, 0, 3, 0, 1, 0, 0, Blocks.AIR.defaultBlockState(), result);
        check((int) DescentAdmissionLog.snapshot().get("rejected_total") == totalBefore,
                "a harmless descent leaves no rejection record");

        // 新一轮寻路开始时清空上一轮记录。
        DescentAdmissionLog.beginSearch();
        var fresh = DescentAdmissionLog.snapshot();
        check((int) fresh.get("rejected_total") == 0 && ((List<?>) fresh.get("samples")).isEmpty(),
                "beginSearch clears the previous search's records");
    }

    private static void inventory(CalculationContext context, Set<LandingAssistPlan.Kind> kinds) throws Exception {
        Field landingInventory = CalculationContext.class.getDeclaredField("landingInventory");
        landingInventory.setAccessible(true);
        landingInventory.set(context, new LandingAssistPlan.InventorySnapshot(kinds, true, true, false));
    }

    private static final class BlocksView extends BlockStateInterface {
        BlockGetter world;
        private BlocksView() { super(null); }
        public boolean worldContainsLoadedChunk(int x, int z) { return true; }
        public BlockState get0(int x, int y, int z) { return world.getBlockState(new BlockPos(x, y, z)); }
    }

    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
