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
import org.maiwithu.maicraft.core.scan.TargetIndex;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 缺资源时只报告已查范围和准备效果；装水夹具只验证顺序，不冒充真实桶交互验收。 */
public final class PortalCastingPreparationTest {
    public static void main(String[] args) throws Exception {
        missingWaterStopsBeforeConstruction();
        waterBeforePool(false);
        waterBeforePool(true);
        reuseCarriedLavaBucket(true);
        reuseCarriedLavaBucket(false);
        System.out.println("PortalCastingPreparationTest: missing water/pool and preparation evidence passed");
    }

    private static PortalPreparationTaskRecord record(String id) {
        var policy = new PortalPreparationPolicy(true, false, false, 128, MaterialPolicy.INVENTORY_ONLY,
                List.of(), List.of(), PortalPreparationPolicy.Method.LAVA_CAST);
        return new PortalPreparationTaskRecord(id, 3000, "minecraft:the_nether", 16, true, policy);
    }

    private static TaskResult finish(InteractionWorldTestHarness world, Task task) throws Exception {
        task.start(world.player); TaskState state = TaskState.RUNNING;
        for (int tick = 0; tick < 1000 && state == TaskState.RUNNING; tick++) {
            world.nextTick(); TargetIndex.clientTick(world.level); state = task.tick(world.player);
        }
        check(state == TaskState.FAILED, "missing resources settle as a reported preparation gap");
        return task.result(state);
    }

    private static void missingWaterStopsBeforeConstruction() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            world.position(new Vec3(7.5, 2, 5.5));
            world.inventory.setItem(0, new ItemStack(Items.BUCKET));
            // 即使旁边有岩浆池，没有装到水也不能进入拆地、搭模具或倒岩浆阶段。
            for (int x = 4; x <= 11; x++) for (int z = 6; z <= 12; z++)
                world.set(new BlockPos(x, 1, z), z == 6 ? Blocks.STONE.defaultBlockState() : Blocks.LAVA.defaultBlockState());
            var task = new PortalPreparationTask(world.player, record("no-water"));
            task.start(world.player);
            check("prepare_water".equals(task.progress().get("preparation_phase")), "acceptance reports water preparation, not casting");
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 1000 && state == TaskState.RUNNING; tick++) {
                world.nextTick(); TargetIndex.clientTick(world.level); state = task.tick(world.player);
            }
            var result = task.result(state); var data = result.data();
            check(state == TaskState.FAILED && "find_water_source_not_observed".equals(data.get("issue_code")),
                    "the missing water reason survives the public preparation parent");
            check("find_water_source".equals(data.get("preparation_phase"))
                    && "find_water_source".equals(((Map<?, ?>) data.get("blocked_facts")).get("phase")),
                    "both status and failure facts name the actual preparation phase");
            check(world.itemUses() == 0 && world.blockUses() == 0 && Boolean.FALSE.equals(data.get("construction_phase_started")),
                    "no construction or bucket input was submitted without water");
            var resources = (Map<?, ?>) data.get("resource_preparation");
            var search = (Map<?, ?>) resources.get("search");
            check(Boolean.FALSE.equals(resources.get("initial_water_prepared")) && "not_surveyed".equals(search.get("lava_pool_status")),
                    "unobserved pool status remains unknown instead of inventing absence");
            check(data.get("recovery_options").toString().contains("locate_casting_resources"), "the model receives a resource discovery handoff");
        }
    }

    private static void waterBeforePool(boolean carried) throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            world.position(new Vec3(7.5, 2, 5.5));
            world.inventory.setItem(0, new ItemStack(carried ? Items.WATER_BUCKET : Items.BUCKET));
            BlockPos water = new BlockPos(5, 1, 5);
            if (!carried) world.set(water, Blocks.WATER.defaultBlockState());
            int[] fills = {0};
            var task = new NetherPortalCastingTask(world.player, record("no-pool"), (player, child) -> new Task() {
                public String name() { return "water_preparation_receipt"; }
                public void stop(LocalPlayer player, StopReason why) {}
                public TaskState tick(LocalPlayer player) {
                    // 没有岩浆池时仍先把身边水源装进同一只桶；此时不能提前获取模具或提交施工动作。
                    check(child instanceof FluidPlacementTaskRecord, "only water collection precedes finding a pool");
                    var bucket = (FluidPlacementTaskRecord) child;
                    check(bucket.target.equals(water) && bucket.removedSource.is(Blocks.WATER), "the observed water source is collected");
                    fills[0]++; world.set(water, Blocks.AIR.defaultBlockState());
                    world.inventory.setItem(0, new ItemStack(Items.WATER_BUCKET)); return TaskState.SUCCESS;
                }
                public TaskResult result(TaskState state) { return TaskResult.ok("fixture water collection", Map.of("native_effect_verified", true)); }
            });
            var result = finish(world, task); var data = result.data();
            check(fills[0] == (carried ? 0 : 1), "carried water is reused; otherwise collection precedes the pool search");
            check("casting_lava_pool_not_observed".equals(data.get("issue_code")) && world.inventory.getItem(0).is(Items.WATER_BUCKET),
                    "a missing pool preserves the water that was actually prepared");
            check(Boolean.FALSE.equals(data.get("construction_phase_started")), "pool absence cannot be presented as started construction");
            var resources = (Map<?, ?>) data.get("resource_preparation");
            check(Boolean.TRUE.equals(resources.get("initial_water_prepared")) && Boolean.FALSE.equals(resources.get("lava_pool_selected")),
                    "prepared water and absent pool are separate facts");
        }
    }

    private static void reuseCarriedLavaBucket(boolean returned) throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            world.position(new Vec3(7.5, 2, 5.5));
            for (int x = 4; x <= 11; x++) for (int z = 6; z <= 12; z++)
                world.set(new BlockPos(x, 1, z), z == 6 ? Blocks.STONE.defaultBlockState() : Blocks.LAVA.defaultBlockState());
            BlockPos water = new BlockPos(2, 1, 3); world.set(water, Blocks.WATER.defaultBlockState());
            world.inventory.setItem(0, new ItemStack(Items.LAVA_BUCKET));
            world.inventory.setItem(1, new ItemStack(Items.IRON_INGOT, 2));
            int[] operations = {0};
            var task = new NetherPortalCastingTask(world.player, record("occupied-bucket"), (player, child) -> new Task() {
                public String name() { return "occupied_bucket_receipt_fixture"; }
                public void stop(LocalPlayer player, StopReason why) {}
                public TaskState tick(LocalPlayer player) {
                    // 复现只有一只岩浆桶和两块铁：只能倒回旧池、返空桶、再装水，不能另开采铁造桶任务。
                    check(child instanceof FluidPlacementTaskRecord, "occupied bucket reuse cannot request new bucket supplies");
                    var bucket = (FluidPlacementTaskRecord) child;
                    if (operations[0]++ == 0) {
                        check(bucket.requireBucketUse && world.level.getBlockState(bucket.target).is(Blocks.LAVA),
                                "first operation really empties the carried bucket into the observed pool");
                        if (returned) world.inventory.setItem(0, new ItemStack(Items.BUCKET));
                    } else {
                        check(bucket.removedSource.is(Blocks.WATER) && bucket.target.equals(water), "water follows confirmed empty-bucket return");
                        world.inventory.setItem(0, new ItemStack(Items.WATER_BUCKET)); world.set(water, Blocks.AIR.defaultBlockState());
                    }
                    return TaskState.SUCCESS;
                }
                public TaskResult result(TaskState state) { return TaskResult.ok("fixture bucket receipt", Map.of("native_effect_verified", true)); }
            });
            task.start(world.player); var state = TaskState.RUNNING;
            for (int tick = 0; tick < 1000 && state == TaskState.RUNNING && operations[0] < 2; tick++) {
                world.nextTick(); TargetIndex.clientTick(world.level); state = task.tick(world.player);
            }
            check(world.inventory.getItem(1).getCount() == 2, "reusing the existing bucket leaves iron untouched");
            if (returned) {
                check(operations[0] == 2 && world.inventory.getItem(0).is(Items.WATER_BUCKET), "one occupied bucket is reused for water");
                task.stop(world.player, Task.StopReason.REPLACED); task.result(TaskState.CANCELLED);
            } else {
                var result = task.result(state);
                check(state == TaskState.FAILED && operations[0] == 1
                        && "casting_empty_bucket_return_unverified".equals(result.data().get("issue_code")),
                        "a claimed action without an empty bucket cannot loop or enter water collection");
            }
        }
    }
}
