// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import static org.maiwithu.maicraft.core.task.dimension.NetherPortalFrameTest.check;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.machine.assembly.FluidPlacementTaskRecord;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.scan.TargetIndex;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.interact.InteractAtTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 子动作夹具只验证单桶编排与错误产物回执；真实取放、流动与成型交给实机测试。 */
public final class PortalCastingWorkflowTest {
    public static void main(String[] args) throws Exception {
        for (boolean wrongProduct : new boolean[]{false, true}) try (var world = new InteractionWorldTestHarness()) {
            world.position(new Vec3(7.5, 2, 5.5));
            for (int x = 4; x <= 11; x++) for (int z = 6; z <= 12; z++)
                world.set(new BlockPos(x, 1, z), z == 6 ? Blocks.STONE.defaultBlockState() : Blocks.LAVA.defaultBlockState());
            world.inventory.setItem(0, new ItemStack(Items.WATER_BUCKET));
            world.inventory.setItem(1, new ItemStack(Items.COBBLESTONE, 16));
            world.inventory.setItem(2, new ItemStack(Items.STONE_PICKAXE));
            var policy = new PortalPreparationPolicy(true, false, false, 128, MaterialPolicy.INVENTORY_ONLY, List.of(), List.of());
            int[] pours = {0};
            var task = new NetherPortalCastingTask(world.player,
                    new PortalPreparationTaskRecord("casting", 6000, "minecraft:the_nether", 32, true, policy),
                    (player, record) -> new Task() {
                        public String name() { return "casting_receipt_fixture"; }
                        public void stop(LocalPlayer player, StopReason why) {}
                        public TaskState tick(LocalPlayer player) {
                            if (record instanceof FluidPlacementTaskRecord bucket) {
                                check(!NavigationSafetyContext.protectsMutation(bucket.target), "only this bucket target is mutable");
                                if (bucket.removedSource != null) {
                                    check(world.inventory.getItem(0).is(Items.BUCKET), "collecting a source uses the same empty bucket");
                                    world.inventory.setItem(0, new ItemStack(bucket.removedSource.is(Blocks.WATER) ? Items.WATER_BUCKET : Items.LAVA_BUCKET));
                                    world.set(bucket.target, Blocks.AIR.defaultBlockState());
                                } else {
                                    check(world.inventory.getItem(0).is(bucket.bucket), "pouring waits for the required bucket contents");
                                    world.inventory.setItem(0, new ItemStack(Items.BUCKET));
                                    if (bucket.expected.is(Blocks.LAVA)) {
                                        pours[0]++;
                                        world.set(bucket.target, wrongProduct && pours[0] == 1 ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.OBSIDIAN.defaultBlockState());
                                    } else world.set(bucket.target, bucket.expected);
                                }
                            } else if (record instanceof BuildTaskRecord build) {
                                build.targets.forEach(t -> world.set(t.pos(), t.desiredState()));
                            } else if (record instanceof InteractAtTaskRecord dig) world.set(dig.aim, Blocks.AIR.defaultBlockState());
                            else throw new AssertionError("unexpected child " + record);
                            return TaskState.SUCCESS;
                        }
                        public TaskResult result(TaskState state) { return TaskResult.ok("fixture receipt", Map.of("native_effect_verified", true)); }
                    });
            task.start(world.player); TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 3000 && state == TaskState.RUNNING; tick++) {
                world.nextTick(); TargetIndex.clientTick(world.level); state = task.tick(world.player);
            }
            var result = task.result(state);
            check(state == TaskState.SUCCESS && pours[0] == 10, "all declared pours run once, including after a wrong product");
            check(world.inventory.getItem(0).is(Items.WATER_BUCKET), "the single bucket recovers the final water source");
            var observation = (Map<?, ?>) result.data().get("portal_observation");
            check(observation.get("frame_ready").equals(!wrongProduct), "action completion does not claim a correct frame");
        }
        System.out.println("PortalCastingWorkflowTest: single bucket and separate action/frame outcomes passed");
    }
}
