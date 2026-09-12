package org.maiwithu.maicraft.core.task.build;

import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.moves.AimGeometry;
import org.maiwithu.maicraft.task.TaskState;

/** Replays the reported sub-degree slab mismatch through real outline hits and the AIM state. */
public final class BuildPrecisionAimTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            h.position(new Vec3(5.5, 1, 4.5));
            h.inventory.setItem(0, new ItemStack(Items.STONE_SLAB));
            BlockPos support = new BlockPos(3, 1, 4);
            h.set(support, Blocks.STONE.defaultBlockState());
            var target = new BuildTaskRecord.Target(Blocks.STONE_SLAB, Items.STONE_SLAB,
                    support.east(), "bottom slab", null, null, null);
            var record = new BuildTaskRecord("precision-aim", 1000, List.of(target), false);
            var task = new FirstPersonBuildCompanionTask(h.player, record, (player, reach) ->
                    h.level.getBlockState(support).getShape(h.level, support).clip(player.getEyePosition(),
                            player.getEyePosition().add(player.getViewVector(1).scale(reach)), support));
            // Leave enough margin for Minecraft's quantized view vector once the view converges.
            var point = new Vec3(3.9999, 1.49, 4.5);
            var gesture = new BuildPlacementGeometry.Gesture(h.player.blockPosition(), support, Direction.EAST,
                    point, AimGeometry.yawTo(h.player.getEyePosition(), point),
                    AimGeometry.pitchTo(h.player.getEyePosition(), point), false, "boundary replay");
            Class<?> cellType = Class.forName(FirstPersonBuildCompanionTask.class.getName() + "$CellPlan");
            var ctor = cellType.getDeclaredConstructor(BuildTaskRecord.Target.class, List.class);
            ctor.setAccessible(true);
            field("cell").set(task, ctor.newInstance(target, List.of()));
            field("gesture").set(task, gesture);
            var select = task.getClass().getDeclaredMethod("selectItemTick"); select.setAccessible(true);
            select.invoke(task);
            var aim = task.getClass().getDeclaredMethod("aimTick"); aim.setAccessible(true);
            for (int tick = 0; tick < 2; tick++) {
                h.nextTick(); h.player.setYRot(gesture.yaw()); h.player.setXRot(gesture.pitch() - .712f);
                check(aim.invoke(task) == TaskState.RUNNING, "transient aim mismatch should keep running");
                check(field("phase").get(task).toString().equals("AIM"), "sub-degree mismatch must converge before rejecting");
                check(field("aimWaitReason").get(task).equals("native_placement_state_mismatch"), "replay must hit the other slab half");
            }
            var attempts = (PlacementAttemptLedger) field("placementAttempts").get(task);
            check(attempts.rejectedCount(target) == 0 && h.blockUses() == 0, "no blacklist or incorrect native click while converging");
            for (int tick = 0; tick < 3; tick++) {
                h.nextTick(); h.player.setYRot(gesture.yaw()); h.player.setXRot(gesture.pitch());
                aim.invoke(task);
                if (tick < 2) check(h.blockUses() == 0, "a newly correct ray still needs settled physical ticks");
            }
            check(field("phase").get(task).toString().equals("WAIT_USE") && h.blockUses() == 1,
                    "the same feet may place once the real ray remains on the correct half: phase="
                            + field("phase").get(task) + ", reason=" + field("aimWaitReason").get(task)
                            + ", error=" + field("aimError").get(task) + ", uses=" + h.blockUses());

            attempts.reject(target, gesture); attempts.rejectStance(target, gesture.stance());
            attempts.changedNear(support.offset(20, 0, 0));
            check(!attempts.allows(target, gesture), "unrelated distant changes cannot revive a failed click");
            task.confirmedScaffold(support.below(), Blocks.DIRT.defaultBlockState());
            check(attempts.allows(target, gesture), "new nearby support releases both click and stance failures");
            attempts.reject(target, gesture);
            task.confirmedScaffold(support.below(), Blocks.DIRT.defaultBlockState());
            check(!attempts.allows(target, gesture), "repeated support bookkeeping cannot revive a failed click");
            task.confirmedScaffoldRemoval(support.below());
            check(attempts.allows(target, gesture), "confirmed nearby removal can reopen a view");
        }
        System.out.println("BuildPrecisionAimTest: sub-degree slab recovery and local world changes passed");
    }

    private static Field field(String name) throws Exception {
        Field field = FirstPersonBuildCompanionTask.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
