// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.Map;
import java.util.Set;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.act.FirstPersonInteractionTargeting;
import org.maiwithu.maicraft.core.pathing.moves.AimGeometry;
import org.maiwithu.maicraft.task.TaskState;

/** Open doors need neither replacement nor spare items; one native use must confirm both halves. */
public final class BuildDoorStateRepairTest {
    private static final BlockPos DOOR = new BlockPos(5, 1, 5);
    private static final BlockState CLOSED = Blocks.SPRUCE_DOOR.defaultBlockState();
    private static final BlockState OPEN = CLOSED.setValue(DoorBlock.OPEN, true);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        eligibility();
        nativeResult(true);
        nativeResult(false);
        generatedHalfMustConfirm();
        constructionPhases(true);
        constructionPhases(false);
        unsupportedFinalState();
        placementBeforeOperatingState();
        activeMultiUsePlacement();
        implicitUpperIsVerified();
        System.out.println("BuildDoorStateRepairTest: exact safe pairs and acknowledged native restoration passed");
    }

    private static void eligibility() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            pair(h, OPEN); var target = target(CLOSED, DOOR);
            check(!target.matches(OPEN), "authored OPEN requirement stays exact before final native repair");
            check(repairable(h, target), "a complete opened spruce door requires no replacement/materials");
            check(!BuildDoorStateRepair.canRepair(target, Map.of(), h.level::isLoaded,
                    h.level::getBlockState, pos -> !pos.equals(DOOR.above())), "upper-half protection is authoritative");
            h.set(DOOR.above(), Blocks.AIR.defaultBlockState());
            check(!repairable(h, target), "a missing generated upper half requires actual construction");
            pair(h, OPEN.setValue(DoorBlock.FACING, Direction.EAST));
            check(!repairable(h, target), "a real orientation mismatch cannot become a click-only repair");
            pair(h, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, true));
            check(!repairable(h, target), "a material mismatch must not be hidden");
            pair(h, OPEN.setValue(DoorBlock.POWERED, true));
            check(!repairable(h, target), "powered door cannot be manually forced closed");
            pair(h, Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, true));
            check(!repairable(h, target(Blocks.IRON_DOOR.defaultBlockState(), DOOR)), "iron doors require another mechanism");
            pair(h, OPEN);
            check(!BuildDoorStateRepair.canRepair(target, Map.of(DOOR.above().asLong(),
                    target(OPEN.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), DOOR.above())),
                    h.level::isLoaded, h.level::getBlockState, pos -> true), "conflicting authored half cannot be toggled");
            pair(h, CLOSED);
            check(repairable(h, target(OPEN, DOOR)), "explicit open=true also restores via the same exact native mechanism");
            pair(h, OPEN.setValue(DoorBlock.FACING, Direction.EAST));
            var ignoredFacing = new BuildTaskRecord.Target(CLOSED, Items.SPRUCE_DOOR, DOOR, "flexible facing", null, null, null,
                    false, Set.of("open", "half"), true, Set.of("open", "half"));
            check(repairable(h, ignoredFacing), "repair preserves undeclared live orientation while restoring authored OPEN");
            pair(h, CLOSED);
            var lowerWithoutOpen = policyTarget(CLOSED, DOOR, false);
            var upperWithOpen = policyTarget(OPEN.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), DOOR.above(), true);
            check(BuildDoorStateRepair.canRepair(lowerWithoutOpen, Map.of(DOOR.above().asLong(), upperWithOpen),
                    h.level::isLoaded, h.level::getBlockState, pos -> true),
                    "an OPEN requirement declared only on the upper half controls the native pair adjustment");
        }
    }

    private static void nativeResult(boolean applied) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.position(new Vec3(5.5, 1, 8.5)); pair(h, OPEN);
            var repair = driver(h);
            submit(h, repair);
            check(h.blockUses() == 1, "one native right-click was submitted");
            if (applied) pair(h, CLOSED);
            for (int i = 0; i < 3; i++) {
                h.nextTick();
                check(repair.tick() == TaskState.RUNNING, "client prediction cannot finish a door repair without server acknowledgement");
            }
            h.level.acknowledgedSequence = h.level.blockSequence;
            h.nextTick();
            TaskState result = repair.tick();
            check(result == (applied ? TaskState.SUCCESS : TaskState.FAILED),
                    "an acknowledged " + (applied ? "applied" : "refused") + " toggle must report its real outcome: " + result);
            check(repair.changed() == applied && h.blockUses() == 1 && h.itemUses() == 0,
                    "a failed toggle cannot mark progress, retry or use a held item");
            repair.stop();
        }
    }

    private static void generatedHalfMustConfirm() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.position(new Vec3(5.5, 1, 8.5)); pair(h, OPEN);
            var repair = driver(h); submit(h, repair);
            h.set(DOOR, CLOSED); h.level.acknowledgedSequence = h.level.blockSequence;
            h.nextTick();
            check(repair.tick() == TaskState.RUNNING, "lower-half change alone cannot confirm the door");
            h.set(DOOR.above(), Blocks.STONE.defaultBlockState()); h.nextTick();
            check(repair.tick() == TaskState.FAILED && repair.uncertain(), "diverged generated upper state is never a success");
            check(h.blockUses() == 1, "unexpected upper half never triggers a second toggle");
            repair.stop();
        }
    }

    private static void constructionPhases(boolean important) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.position(new Vec3(5.5, 1, 8.5)); pair(h, OPEN);
            var record = modelRecord(important);
            var task = new FirstPersonBuildCompanionTask(h.player, record, (player, reach) -> h.level.clip(new ClipContext(
                    player.getEyePosition(), player.getEyePosition().add(player.getViewVector(1).scale(reach)),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player)));
            check(invoke(task, "preflightTick") == TaskState.RUNNING, "opened same-material door cannot block replace=false preflight");
            check(((Map<?, ?>) field(task, "required")).isEmpty() && ((List<?>) field(task, "blocked")).isEmpty(),
                    "existing door needs no spare item or replacement permission");
            invoke(task, "selectTick"); invoke(task, "verifyTick"); invoke(task, "scaffoldSelectTick");
            check(field(task, "phase").toString().equals("FINAL_STATE") && h.blockUses() == 0,
                    "native state repair begins only after structural verification and scaffold cleanup");
            if (important) {
                for (int i = 0; i < 9 && h.blockUses() == 0; i++) {
                    var visible = FirstPersonInteractionTargeting.visibleBlockHit(h.level, h.player, h.player.getEyePosition(), DOOR, 4.5);
                    h.player.setYRot(AimGeometry.yawTo(h.player.getEyePosition(), visible.getLocation()));
                    h.player.setXRot(AimGeometry.pitchTo(h.player.getEyePosition(), visible.getLocation()));
                    check(invoke(task, "finalStateTick") == TaskState.RUNNING, "final repair awaits native acknowledgement");
                    h.nextTick();
                }
                check(h.blockUses() == 1 && record.completed() == 0, "important OPEN is not counted as complete before restoration");
                pair(h, CLOSED); h.level.acknowledgedSequence = h.level.blockSequence; h.nextTick();
            }
            check(invoke(task, "finalStateTick") == TaskState.SUCCESS && record.completed() == 2,
                    "only required final fields decide completion");
            check(record.placed() == 0 && record.broken() == 0 && h.blockUses() == (important ? 1 : 0),
                    "omitted OPEN never becomes a replacement requirement or unnecessary toggle");
            invoke(task, "cleanup");
        }
    }

    private static void unsupportedFinalState() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            pair(h, CLOSED.setValue(DoorBlock.FACING, Direction.EAST));
            var task = new FirstPersonBuildCompanionTask(h.player, modelRecord(true));
            check(invoke(task, "preflightTick") == TaskState.RUNNING, "same-material state differences wait until finalization");
            invoke(task, "selectTick"); invoke(task, "verifyTick"); invoke(task, "scaffoldSelectTick");
            check(invoke(task, "finalStateTick") == TaskState.FAILED
                            && field(task, "failureCode").equals("final_state_adjustment_unsupported") && h.blockUses() == 0,
                    "important orientation mismatch is reported at finalization without silently replacing the door");
            invoke(task, "cleanup");
        }
        try (var h = new InteractionWorldTestHarness()) {
            pair(h, Blocks.OAK_DOOR.defaultBlockState());
            var chunk = h.level.getChunkSource().getChunk(0, 0, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
            setField(net.minecraft.world.level.chunk.LevelChunk.class, "level", chunk, h.level);
            setField(net.minecraft.world.level.chunk.ChunkAccess.class, "levelHeightAccessor", chunk, h.level);
            var task = new FirstPersonBuildCompanionTask(h.player, modelRecord(true));
            check(invoke(task, "preflightTick") == TaskState.FAILED
                            && field(task, "failureCode").equals("blocked_site_cells"),
                    "a true material mismatch still respects replace=false");
            invoke(task, "cleanup");
        }
    }

    private static void placementBeforeOperatingState() {
        var target = policyTarget(OPEN, DOOR, true);
        var generated = BuildPlacementGeometry.generatedBy(target);
        var confirmation = new BuildPlacementConfirmation(target, generated,
                Map.of(DOOR.asLong(), Blocks.AIR.defaultBlockState(), DOOR.above().asLong(), Blocks.AIR.defaultBlockState()), CLOSED);
        check(confirmation.observe(pos -> true, pos -> pos.equals(DOOR) ? CLOSED
                        : CLOSED.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), true)
                        == org.maiwithu.maicraft.client.actor.NativeConfirmation.Verdict.APPLIED,
                "normal closed-door placement is acknowledged before an important final OPEN=true adjustment");
    }

    private static void activeMultiUsePlacement() throws Exception {
        var bottom = Blocks.OAK_SLAB.defaultBlockState();
        var full = bottom.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE,
                net.minecraft.world.level.block.state.properties.SlabType.DOUBLE);
        var target = new BuildTaskRecord.Target(full, Items.OAK_SLAB, DOOR, "double slab", null, null, null,
                false, Set.of("type"), true, Set.of("type"));
        check(BuildPlacementGeometry.isProgress(target, Blocks.AIR.defaultBlockState(), bottom), "first authored slab half is valid progress");
        check(!BuildPlacementGeometry.placementComplete(target, bottom) && BuildPlacementGeometry.placementComplete(target, full),
                "native quantity completion requires both slab halves");
        try (var h = new InteractionWorldTestHarness()) {
            h.set(DOOR, bottom);
            var task = new FirstPersonBuildCompanionTask(h.player, new BuildTaskRecord("quantity", 1000, List.of(target), false));
            Class<?> cellType = Class.forName(FirstPersonBuildCompanionTask.class.getName() + "$CellPlan");
            var ctor = cellType.getDeclaredConstructor(BuildTaskRecord.Target.class, List.class); ctor.setAccessible(true);
            var cell = FirstPersonBuildCompanionTask.class.getDeclaredField("cell"); cell.setAccessible(true); cell.set(task, ctor.newInstance(target, List.of()));
            var uses = FirstPersonBuildCompanionTask.class.getDeclaredField("useCount"); uses.setAccessible(true); uses.setInt(task, 1);
            check(!(boolean) invoke(task, "currentPlacementComplete"), "active first-half placement cannot leave the cell early");
            h.set(DOOR, full);
            check((boolean) invoke(task, "currentPlacementComplete"), "completed active quantity may advance");
        }
        var layers = net.minecraft.world.level.block.state.properties.BlockStateProperties.LAYERS;
        var snow = new BuildTaskRecord.Target(Blocks.SNOW.defaultBlockState().setValue(layers, 3), Items.SNOW, DOOR,
                "snow layers", null, null, null, false, Set.of("layers"), true, Set.of("layers"));
        check(BuildPlacementGeometry.isProgress(snow, Blocks.AIR.defaultBlockState(), Blocks.SNOW.defaultBlockState())
                        && !BuildPlacementGeometry.placementComplete(snow, Blocks.SNOW.defaultBlockState()),
                "authored snow quantity accepts intermediate uses without completing early");
    }

    private static void implicitUpperIsVerified() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            pair(h, CLOSED);
            var record = new BuildTaskRecord("generated-upper", 1000, List.of(policyTarget(CLOSED, DOOR, true)), false);
            var task = new FirstPersonBuildCompanionTask(h.player, record);
            invoke(task, "preflightTick"); invoke(task, "selectTick"); invoke(task, "verifyTick"); invoke(task, "scaffoldSelectTick");
            h.set(DOOR.above(), OPEN.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            check(invoke(task, "finalStateTick") == TaskState.FAILED && h.blockUses() == 0,
                    "an inconsistent implicit generated upper half cannot escape final verification or trigger a blind toggle");
            invoke(task, "cleanup");
        }
    }

    private static BuildTaskRecord modelRecord(boolean important) {
        return new BuildTaskRecord("door-state", 1000, List.of(policyTarget(CLOSED, DOOR, important),
                policyTarget(CLOSED.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), DOOR.above(), important)), false);
    }
    private static BuildTaskRecord.Target policyTarget(BlockState state, BlockPos pos, boolean important) {
        Set<String> declared = important ? Set.of("open", "facing", "hinge", "half") : Set.of("facing", "hinge", "half");
        return new BuildTaskRecord.Target(state, Items.SPRUCE_DOOR, pos, "model door", null, null, null,
                false, declared, true, declared);
    }
    private static Object field(FirstPersonBuildCompanionTask task, String name) throws Exception {
        var field = FirstPersonBuildCompanionTask.class.getDeclaredField(name); field.setAccessible(true); return field.get(task);
    }
    private static void setField(Class<?> owner, String name, Object instance, Object value) throws Exception {
        var field = owner.getDeclaredField(name); field.setAccessible(true); field.set(instance, value);
    }
    private static Object invoke(FirstPersonBuildCompanionTask task, String name) throws Exception {
        var method = FirstPersonBuildCompanionTask.class.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(task);
    }

    private static BuildDoorStateRepair driver(InteractionWorldTestHarness h) {
        return new BuildDoorStateRepair(h.player, target(CLOSED, DOOR), pos -> true,
                (player, reach) -> h.level.clip(new ClipContext(player.getEyePosition(),
                        player.getEyePosition().add(player.getViewVector(1).scale(reach)),
                        ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player)));
    }
    private static void submit(InteractionWorldTestHarness h, BuildDoorStateRepair repair) throws Exception {
        for (int i = 0; i < 8 && h.blockUses() == 0; i++) {
            var visible = FirstPersonInteractionTargeting.visibleBlockHit(h.level, h.player, h.player.getEyePosition(), DOOR, 4.5);
            check(visible != null, "fixture exposes a real door face");
            h.player.setYRot(AimGeometry.yawTo(h.player.getEyePosition(), visible.getLocation()));
            h.player.setXRot(AimGeometry.pitchTo(h.player.getEyePosition(), visible.getLocation()));
            check(repair.tick() == TaskState.RUNNING, "repair must wait for its native postcondition: " + repair.failure());
            h.nextTick();
        }
        check(h.blockUses() == 1, "converged door repair must issue exactly one use");
    }
    private static boolean repairable(InteractionWorldTestHarness h, BuildTaskRecord.Target target) {
        return BuildDoorStateRepair.canRepair(target, Map.of(), h.level::isLoaded, h.level::getBlockState, pos -> true);
    }
    private static void pair(InteractionWorldTestHarness h, BlockState lower) {
        h.set(DOOR, lower); h.set(DOOR.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }
    private static BuildTaskRecord.Target target(BlockState state, BlockPos pos) {
        return new BuildTaskRecord.Target(state, Items.SPRUCE_DOOR, pos, "authored door", null, null, null,
                false, Set.of("open", "facing", "hinge", "half"), true);
    }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
