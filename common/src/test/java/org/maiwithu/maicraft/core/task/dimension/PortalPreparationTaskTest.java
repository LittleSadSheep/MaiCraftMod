// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import static org.maiwithu.maicraft.core.task.dimension.NetherPortalFrameTest.check;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.scan.TargetIndex;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.TaskState;

public final class PortalPreparationTaskTest {
    public static void main(String[] args) throws Exception {
        refusesUnapprovedPreparation();
        for (boolean rare : new boolean[]{false, true}) {
            try (var world = new InteractionWorldTestHarness()) {
                var ring = new EndPortalFrame(new BlockPos(3, 1, 3));
                BlockPos missing = ring.center().north(2);
                ring.frames().forEach((p, direction) -> world.set(p, Blocks.END_PORTAL_FRAME.defaultBlockState()
                        .setValue(EndPortalFrameBlock.FACING, direction).setValue(EndPortalFrameBlock.HAS_EYE, !p.equals(missing))));
                world.position(new Vec3(3.5, 1, .5));
                world.inventory.setItem(0, new ItemStack(Items.ENDER_EYE));
                var policy = new PortalPreparationPolicy(true, rare, false, 128, MaterialPolicy.INVENTORY_ONLY, List.of(), List.of());
                var task = new PortalPreparationTask(world.player,
                        new PortalPreparationTaskRecord("end-preparation", 2000, "minecraft:the_end", 32, false, policy));
                task.start(world.player);
                TaskState state = TaskState.RUNNING;
                for (int i = 0; i < 100 && state == TaskState.RUNNING && world.blockUses() == 0; i++) {
                    world.nextTick(); TargetIndex.clientTick(world.level);
                    Vec3 aim = Vec3.atLowerCornerOf(missing).add(.5, .8125, .5).subtract(world.player.getEyePosition());
                    world.player.setYRot((float) Math.toDegrees(Math.atan2(-aim.x, aim.z)));
                    world.player.setXRot((float) -Math.toDegrees(Math.atan2(aim.y, Math.hypot(aim.x, aim.z))));
                    state = task.tick(world.player);
                }
                if (!rare) {
                    check(state == TaskState.FAILED && world.blockUses() == 0 && world.itemUses() == 0,
                            "an observed empty frame never implies permission to consume an eye");
                    check(task.result(state).data().get("issue_code").equals("rare_consumable_permission_required"), "refusal explains the missing permission");
                    continue;
                }
                check(world.blockUses() == 1 && state == TaskState.RUNNING, "preparation reaches the frame and inserts the missing eye once");
                world.set(missing, world.level.getBlockState(missing).setValue(EndPortalFrameBlock.HAS_EYE, true));
                ring.interior().forEach(p -> world.set(p, Blocks.END_PORTAL.defaultBlockState()));
                for (int i = 0; i < 3; i++) {
                    world.nextTick();
                    check(task.tick(world.player) == TaskState.RUNNING, "even a predicted full portal cannot bypass the pending activation receipt");
                }
                world.level.acknowledgedSequence = world.level.blockSequence;
                world.nextTick(); state = task.tick(world.player);
                check(state == TaskState.SUCCESS && Boolean.TRUE.equals(task.result(state).data().get("portal_prepared")),
                        "the full workflow succeeds only after acknowledged activation and a complete portal surface");
                check(world.itemUses() == 0 && world.blockUses() == 1, "already filled frames are never clicked and eyes are never thrown at air");
            }
        }
        System.out.println("PortalPreparationTaskTest: preparation permission and observed End activation workflow passed");
    }
    private static void refusesUnapprovedPreparation() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var task = new PortalPreparationTask(world.player,
                    new PortalPreparationTaskRecord("disabled", 2000, "minecraft:the_nether", 32, true, PortalPreparationPolicy.DISABLED));
            task.start(world.player);
            check(task.tick(world.player) == TaskState.FAILED && world.blockUses() == 0, "route terrain permission cannot authorize preparing a portal");
            task.result(TaskState.FAILED);
        }
    }
}
