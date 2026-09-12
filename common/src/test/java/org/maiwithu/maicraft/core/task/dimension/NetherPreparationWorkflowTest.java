// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import static org.maiwithu.maicraft.core.task.dimension.NetherPortalFrameTest.check;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.scan.TargetIndex;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** Orchestration fixture substitutes construction/travel receipts; ignition uses the real native action port. */
public final class NetherPreparationWorkflowTest {
    public static void main(String[] args) throws Exception {
        for (boolean built : new boolean[]{false, true}) {
            try (var world = new InteractionWorldTestHarness()) {
                world.position(new Vec3(7.5, 1, 7.5));
                world.inventory.setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
                world.inventory.setItem(1, new ItemStack(Items.OBSIDIAN, 10));
                world.inventory.setItem(2, new ItemStack(Items.COBBLESTONE, 8));
                var policy = new PortalPreparationPolicy(true, false, false, 128, MaterialPolicy.INVENTORY_ONLY, List.of(), List.of());
                var observed = new NetherPortalFrame[1];
                var builds = new int[1];
                var task = new PortalPreparationTask(world.player,
                        new PortalPreparationTaskRecord("nether", 3000, "minecraft:the_nether", 32, true, policy),
                        (player, record) -> new Task() {
                            public String name() { return "bounded_portal_child"; }
                            public void stop(LocalPlayer body, StopReason why) {}
                            public TaskState tick(LocalPlayer body) {
                                if (record instanceof BuildTaskRecord plan) {
                                    builds[0]++;
                                    check(plan.targets.size() == 10 && plan.targets.stream().allMatch(t ->
                                                    t.desiredState().is(Blocks.OBSIDIAN) && world.level.getBlockState(t.pos()).isAir()
                                                            && !NavigationSafetyContext.forbidsBody(t.pos())),
                                            "construction targets only ten empty frame cells, outside the body exclusion");
                                    if (!built) return TaskState.FAILED;
                                    plan.targets.forEach(t -> world.set(t.pos(), t.desiredState()));
                                    var seed = plan.targets.getFirst().pos().above();
                                    for (var axis : new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}) {
                                        var frame = NetherPortalFrame.observe(p -> PortalPreparationSite.read(world.level, p), seed, axis);
                                        if (frame != null) observed[0] = frame;
                                    }
                                    check(observed[0] != null, "the construction child produced a valid portal frame");
                                } else if (record instanceof MoveToTaskRecord move) {
                                    try { world.position(new Vec3(move.x + .5, move.y, move.z + .5)); }
                                    catch (Exception failure) { throw new IllegalStateException(failure); }
                                } else throw new AssertionError("unexpected material acquisition with sufficient inventory");
                                return TaskState.SUCCESS;
                            }
                            public TaskResult result(TaskState state) {
                                return state == TaskState.SUCCESS ? TaskResult.ok("observed child completion") : TaskResult.fail("construction obstructed");
                            }
                        });
                task.start(world.player);
                TaskState state = TaskState.RUNNING;
                for (int i = 0; i < 500 && state == TaskState.RUNNING && world.blockUses() == 0; i++) {
                    world.nextTick(); TargetIndex.clientTick(world.level);
                    if (observed[0] != null) {
                        Vec3 aim = Vec3.atBottomCenterOf(observed[0].origin()).subtract(world.player.getEyePosition());
                        world.player.setYRot((float) Math.toDegrees(Math.atan2(-aim.x, aim.z)));
                        world.player.setXRot((float) -Math.toDegrees(Math.atan2(aim.y, Math.hypot(aim.x, aim.z))));
                    }
                    state = task.tick(world.player);
                }
                check(builds[0] == 1, "one frozen construction plan is executed once");
                if (!built) {
                    check(state == TaskState.FAILED && world.blockUses() == 0, "failed construction never triggers ignition");
                } else {
                    check(state == TaskState.RUNNING && world.blockUses() == 1, "verified construction is followed by one ignition click");
                    observed[0].interior().forEach(p -> world.set(p,
                            Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, observed[0].axis())));
                    world.level.acknowledgedSequence = world.level.blockSequence;
                    world.nextTick(); state = task.tick(world.player);
                    check(state == TaskState.SUCCESS, "acknowledged portal creation completes preparation");
                }
                task.result(state);
            }
        }
        System.out.println("NetherPreparationWorkflowTest: construction receipts, exterior approach and confirmed ignition passed");
    }
}
