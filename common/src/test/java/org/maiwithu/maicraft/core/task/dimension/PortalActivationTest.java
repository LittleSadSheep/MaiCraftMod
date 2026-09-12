// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import static org.maiwithu.maicraft.core.task.dimension.NetherPortalFrameTest.check;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.task.TaskState;

public final class PortalActivationTest {
    private static final BlockPos TARGET = new BlockPos(1, 1, 1);
    private static final Vec3 AIM = new Vec3(1.5, 2, 1.5);

    public static void main(String[] args) throws Exception {
        for (boolean acknowledge : new boolean[]{false, true}) {
            try (var world = new InteractionWorldTestHarness()) {
                world.set(TARGET, Blocks.OBSIDIAN.defaultBlockState());
                world.inventory.setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
                var predicted = new AtomicBoolean();
                var activation = new PortalActivation(Items.FLINT_AND_STEEL, TARGET, AIM, Direction.UP,
                        () -> true, predicted::get);
                check(activation.tick(world.player) == TaskState.RUNNING, "activation first aims without clicking");
                aim(world); world.nextTick(); activation.tick(world.player);
                check(world.blockUses() == 1 && world.itemUses() == 0, "activation uses only the observed block face");
                predicted.set(true);
                for (int i = 0; i < 4; i++) {
                    world.nextTick();
                    check(activation.tick(world.player) == TaskState.RUNNING, "client prediction alone is insufficient");
                }
                if (acknowledge) {
                    world.level.acknowledgedSequence = world.level.blockSequence;
                    world.nextTick();
                    check(activation.tick(world.player) == TaskState.SUCCESS, "acknowledged portal creation completes");
                } else {
                    for (int i = 0; i < 110; i++) { world.nextTick(); activation.tick(world.player); }
                    check(activation.tick(world.player) == TaskState.FAILED, "missing acknowledgement is a bounded failure");
                }
                check(world.blockUses() == 1, "waiting and timeout never repeat the ignition click");
                activation.close(world.player);
            }
        }
        try (var world = new InteractionWorldTestHarness()) {
            world.set(TARGET, Blocks.END_PORTAL_FRAME.defaultBlockState());
            world.inventory.setItem(0, new ItemStack(Items.ENDER_EYE, 12));
            var permitted = new AtomicBoolean(true);
            var activation = new PortalActivation(Items.ENDER_EYE, TARGET, AIM, null, permitted::get, () -> false);
            activation.tick(world.player); aim(world); world.nextTick(); permitted.set(false);
            check(activation.tick(world.player) == TaskState.FAILED && world.blockUses() == 0 && world.itemUses() == 0,
                    "a changed ring cannot consume or throw an eye");
            activation.close(world.player);
        }
        System.out.println("PortalActivationTest: native aim, acknowledgement, timeout and changed-frame refusal passed");
    }

    private static void aim(InteractionWorldTestHarness world) {
        Vec3 direction = AIM.subtract(world.player.getEyePosition());
        world.player.setYRot((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)));
        world.player.setXRot((float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z))));
    }
}
