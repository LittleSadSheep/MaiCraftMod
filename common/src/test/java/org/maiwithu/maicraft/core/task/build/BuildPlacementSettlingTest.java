// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.moves.AimGeometry;
import sun.misc.Unsafe;

/** A successful native prediction is not permission to click during an unfinished camera turn. */
public final class BuildPlacementSettlingTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            h.position(new Vec3(5.5, 1, 4.5));
            h.inventory.setItem(0, new ItemStack(Items.STONE));
            BlockPos support = new BlockPos(3, 1, 4);
            h.set(support, Blocks.STONE.defaultBlockState());
            Vec3 point = new Vec3(3.9999, 1.5, 4.5);
            var hit = new BlockHitResult(point, Direction.EAST, support, false);
            var target = new BuildTaskRecord.Target(Blocks.STONE, Items.STONE,
                    support.east(), "settled placement", null, null, null);
            var task = new FirstPersonBuildCompanionTask(h.player,
                    new BuildTaskRecord("settling", 1000, List.of(target), false), (player, reach) -> hit);
            var gesture = new BuildPlacementGeometry.Gesture(h.player.blockPosition(), support, Direction.EAST, point,
                    AimGeometry.yawTo(h.player.getEyePosition(), point), AimGeometry.pitchTo(h.player.getEyePosition(), point),
                    false, "real native state with changing view");
            Class<?> cellType = Class.forName(task.getClass().getName() + "$CellPlan");
            var ctor = cellType.getDeclaredConstructor(BuildTaskRecord.Target.class, List.class); ctor.setAccessible(true);
            field("cell").set(task, ctor.newInstance(target, List.of()));
            field("gesture").set(task, gesture);
            var select = task.getClass().getDeclaredMethod("selectItemTick"); select.setAccessible(true); select.invoke(task);
            var aim = task.getClass().getDeclaredMethod("aimTick"); aim.setAccessible(true);
            for (int tick = 0; tick < 3; tick++) {
                h.nextTick(); h.player.setYRot(gesture.yaw() - .5F); h.player.setXRot(gesture.pitch());
                aim.invoke(task);
                check(h.blockUses() == 0 && field("aimWaitReason").get(task).equals("waiting_for_view_settle"),
                        "a valid hit and matching block prediction must not skip camera convergence");
            }
            h.nextTick(); h.player.setYRot(gesture.yaw()); h.player.setXRot(gesture.pitch());
            aim.invoke(task); aim.invoke(task);
            check(h.blockUses() == 0, "same-tick calls cannot replace settled physical ticks");
            h.nextTick(); h.player.input.shiftKeyDown = true; aim.invoke(task);
            check(field("aimWaitReason").get(task).equals("waiting_for_posture") && h.blockUses() == 0,
                    "incorrect posture waits without clicking or consuming the candidate");
            h.nextTick(); h.player.input.shiftKeyDown = false; aim.invoke(task);
            check(h.blockUses() == 0, "a posture interruption must restart consecutive settling");
            h.set(support, Blocks.COBBLESTONE.defaultBlockState());
            for (int tick = 0; tick < 3; tick++) {
                h.nextTick(); aim.invoke(task);
                if (tick < 2) check(h.blockUses() == 0, "changed support facts must also restart consecutive settling");
            }
            check(h.blockUses() == 1 && field("phase").get(task).toString().equals("WAIT_USE"),
                    "three matching physical samples submit exactly one ordinary native placement");
            var attempts = (PlacementAttemptLedger) field("placementAttempts").get(task);
            check(attempts.rejectedCount(target) == 0, "converging view and posture do not blacklist a usable candidate");
            serverHeadYawChangesNativePlacement(h, hit);
        }
        System.out.println("BuildPlacementSettlingTest: successful AIM waits; native server head yaw affects facing");
    }

    private static void serverHeadYawChangesNativePlacement(InteractionWorldTestHarness h, BlockHitResult hit) throws Exception {
        Field unsafe = Unsafe.class.getDeclaredField("theUnsafe"); unsafe.setAccessible(true);
        var server = (ServerPlayer) ((Unsafe) unsafe.get(null)).allocateInstance(ServerPlayer.class);
        server.setYRot(46.642803F); server.setXRot(17.5165F); server.setYHeadRot(0);
        var context = new BlockPlaceContext(new UseOnContext(h.level, server, InteractionHand.MAIN_HAND,
                new ItemStack(Items.DISPENSER), hit) {});
        check(Blocks.DISPENSER.getStateForPlacement(context).getValue(BlockStateProperties.FACING) == Direction.NORTH,
                "real ServerPlayer uses the preceding head yaw even though its body already faces west");
        server.setYHeadRot(server.getYRot());
        check(Blocks.DISPENSER.getStateForPlacement(context).getValue(BlockStateProperties.FACING) == Direction.EAST,
                "the same native context faces east once head and body agree; this fixture does not run a server tick");
    }

    private static Field field(String name) throws Exception {
        Field field = FirstPersonBuildCompanionTask.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
