// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import org.maiwithu.maicraft.task.TaskState;

/** 缺建筑料也能进入出口准备；推进真实预检和到达回执，夹具站位变化不作为真人施工证据。 */
public final class BuildSupplyAccessTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        observedDepthGate();
        missingConstructionMaterialsDoNotBlockAccess();
        accessNeverVerifiesTheBuilding();
        System.out.println("BuildSupplyAccessTest: passed");
    }

    private static void observedDepthGate() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var targets = preparePit(h);
            check(BuildExcavationFrontier.needsSupplyAccess(h.player, targets), "a resumed three-block pit needs its own build access before storage navigation");
            h.position(new Vec3(4.5, 3, 4.5));
            check(!BuildExcavationFrontier.needsSupplyAccess(h.player, targets), "a one-block step does not add a needless access task");
            h.position(new Vec3(7.5, 1, 4.5));
            check(!BuildExcavationFrontier.needsSupplyAccess(h.player, targets), "an exterior body cannot authorize construction access through another area");
            h.position(new Vec3(4.5, 1, 4.5));
            for (BlockPos pos : List.of(new BlockPos(2, 3, 4), new BlockPos(6, 3, 4), new BlockPos(4, 3, 2), new BlockPos(4, 3, 6)))
                h.set(pos, Blocks.MAGMA_BLOCK.defaultBlockState());
            check(!BuildExcavationFrontier.needsSupplyAccess(h.player, targets), "unsafe exterior columns cannot become observed supply exits");
            check(!BuildExcavationFrontier.needsSupplyAccess(h.player, List.of()), "an empty blueprint has no authorized footprint");
        }
    }

    private static void missingConstructionMaterialsDoNotBlockAccess() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var targets = preparePit(h);
            var source = new BuildTaskRecord("shared-site", 1000, targets, false, true);
            BlockPos scaffold = new BlockPos(3, 2, 4), protectedAir = new BlockPos(5, 2, 4);
            h.set(scaffold, Blocks.DIRT.defaultBlockState()); source.scaffoldLedger().confirmed(scaffold, Blocks.DIRT.defaultBlockState());
            source.executionGuards(List.of(protectedAir), p -> true, (p, pos) -> !pos.equals(protectedAir), (p, pos) -> {});
            var access = new BuildTaskRecord("supply-access", 1000, targets, false, true);
            source.copyExecutionContextTo(access); access.previewManaged(true); access.supplyAccessOnly(true);
            var task = new FirstPersonBuildCompanionTask(h.player, access);
            task.start(h.player);
            check(preflight(task) == TaskState.RUNNING && field("phase").get(task).toString().equals("EXCAVATE_EXIT"),
                    "real preflight must reach access navigation despite having no required planks or affordable building cell");
            check(((Map<?, ?>) field("required").get(task)).get(Items.OAK_PLANKS).equals(9)
                    && h.inventory.isEmpty(), "the missing-material fixture must remain genuinely unfunded");
            check(task.permit() == TerrainPermit.TERRAFORM && PlayerNav.ContextProvider.DEFAULT.permit() == TerrainPermit.PRESERVE,
                    "construction owns its existing terrain permit; ordinary navigation is unchanged");
            check(task.embeddedProtectedMutationCells().contains(protectedAir.asLong())
                    && task.embeddedProtectedMutationCells().contains(new BlockPos(3, 1, 3).asLong()),
                    "the original protected air and permanent floor stay protected while making access");
            check(access.scaffoldLedger() == source.scaffoldLedger() && access.excavationCargo() == source.excavationCargo(),
                    "resumed access retains the existing scaffold and spoil ownership ledgers");
            check(!task.resultData().containsKey("supply_access_ready") || !Boolean.TRUE.equals(task.resultData().get("supply_access_ready")),
                    "the preflight phase cannot claim the exit was reached");
            BlockPos exit = (BlockPos) field("excavationExit").get(task);
            h.position(Vec3.atBottomCenterOf(exit)); h.nextTick();
            check(invoke(task, "excavationExitTick") == TaskState.SUCCESS,
                    "a physically observed exact exterior arrival settles the access child");
            var receipt = task.result(TaskState.SUCCESS);
            check(receipt.success() && Boolean.TRUE.equals(receipt.data().get("supply_access_ready"))
                    && Boolean.FALSE.equals(receipt.data().get("goal_satisfied")), "access success is separate from the unfinished building goal");
            check(!receipt.data().containsKey("verified_position") && !receipt.data().containsKey("aggregate_verification")
                    && access.internalVerifiedPosition() == null, "access cannot publish a verified building position");
            check(source.scaffoldLedger().owns(scaffold, Blocks.DIRT.defaultBlockState())
                    && h.level.getBlockState(scaffold).is(Blocks.DIRT) && access.placed() == 0 && access.broken() == 0,
                    "access completion retains existing supports without pretending to place or clear building cells");
            var next = new BuildTaskRecord("normal-next-batch", 1000, targets, false, true);
            access.copyExecutionContextTo(next);
            check(!next.supplyAccessOnly() && next.scaffoldLedger() == source.scaffoldLedger(),
                    "the next ordinary build inherits support ownership, not access-only operation mode");
        }
    }

    private static void accessNeverVerifiesTheBuilding() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var targets = preparePit(h); targets.forEach(target -> h.set(target.pos(), target.desiredState()));
            h.position(new Vec3(2.5, 4, 4.5));
            var access = new BuildTaskRecord("already-outside", 1000, targets, false, true);
            access.supplyAccessOnly(true); access.previewManaged(true); access.semanticFacts(Map.of("rooms", 1));
            var task = new FirstPersonBuildCompanionTask(h.player, access); task.start(h.player);
            check(preflight(task) == TaskState.SUCCESS, "a body already outside needs no new movement");
            var receipt = task.result(TaskState.SUCCESS);
            check(Boolean.FALSE.equals(receipt.data().get("goal_satisfied")) && !receipt.data().containsKey("aggregate_verification")
                    && !receipt.data().containsKey("verified_position"), "even coincidentally matching cells cannot turn access into whole-building verification");
            check(receipt.message().contains("material supply") && !receipt.message().contains("built and re-verified"),
                    "the success message names only the completed preparation");
        }
    }

    public static List<BuildTaskRecord.Target> preparePit(InteractionWorldTestHarness h) throws Exception {
        for (int x = 2; x <= 6; x++) for (int z = 2; z <= 6; z++)
            if (x == 2 || x == 6 || z == 2 || z == 6) for (int y = 1; y <= 3; y++) h.set(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
        var targets = new ArrayList<BuildTaskRecord.Target>();
        for (int x = 3; x <= 5; x++) for (int z = 3; z <= 5; z++) for (int y = 1; y <= 3; y++)
            targets.add(new BuildTaskRecord.Target(y == 1 ? Blocks.OAK_PLANKS : Blocks.AIR,
                    y == 1 ? Items.OAK_PLANKS : Items.AIR, new BlockPos(x, y, z), "pit", null, null, null));
        h.position(new Vec3(4.5, 1, 4.5)); return List.copyOf(targets);
    }
    private static TaskState preflight(FirstPersonBuildCompanionTask task) throws Exception {
        TaskState status = TaskState.RUNNING;
        for (int i = 0; i < 4 && field("phase").get(task).toString().equals("PREFLIGHT") && status == TaskState.RUNNING; i++)
            status = (TaskState) invoke(task, "preflightTick");
        return status;
    }
    private static Object invoke(Object target, String name) throws Exception {
        Method method = FirstPersonBuildCompanionTask.class.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(target);
    }
    private static Field field(String name) throws Exception {
        Field field = FirstPersonBuildCompanionTask.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
