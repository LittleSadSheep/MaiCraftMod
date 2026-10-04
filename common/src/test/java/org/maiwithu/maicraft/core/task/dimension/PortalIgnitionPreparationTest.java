// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import static org.maiwithu.maicraft.core.task.dimension.NetherPortalFrameTest.check;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.scan.TargetIndex;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 点火用品必须在第一笔施工之前落实；夹具只验证任务先后，合成与点火另由原生实机核实。 */
public final class PortalIgnitionPreparationTest {
    public static void main(String[] args) throws Exception {
        for (boolean prepared : new boolean[]{false, true}) try (var h = new InteractionWorldTestHarness()) {
            h.position(new Vec3(7.5, 2, 5.5));
            for (int x = 4; x <= 11; x++) for (int z = 6; z <= 12; z++)
                h.set(new BlockPos(x,1,z), (z == 6 ? Blocks.STONE : Blocks.LAVA).defaultBlockState());
            h.inventory.setItem(0, new ItemStack(Items.WATER_BUCKET));
            h.inventory.setItem(1, new ItemStack(Items.COBBLESTONE, 64));
            h.inventory.setItem(2, new ItemStack(Items.STONE_PICKAXE));
            h.inventory.setItem(3, new ItemStack(Items.FLINT));
            h.inventory.setItem(4, new ItemStack(Items.IRON_INGOT));
            boolean[] supplied = {false}, construction = {false};
            var policy = new PortalPreparationPolicy(true, false, false, 128, MaterialPolicy.ORDINARY,
                    List.of(), List.of(), PortalPreparationPolicy.Method.LAVA_CAST, 0);
            var task = new NetherPortalCastingTask(h.player, new PortalPreparationTaskRecord("ignition-first",3000,
                    "minecraft:the_nether",16,true,policy), (player, record) -> {
                if (record instanceof BuildTaskRecord) {
                    check(supplied[0] && h.inventory.getItem(3).is(Items.FLINT_AND_STEEL), "construction requires proven ignition stock");
                    construction[0] = true;
                } else check(record instanceof SemanticAcquireTaskRecord, "the first native child obtains ignition supplies");
                return new Task() {
                    public String name() { return "ignition_preparation_fixture"; }
                    public void stop(LocalPlayer player, StopReason reason) {}
                    public TaskState tick(LocalPlayer player) {
                        if (record instanceof SemanticAcquireTaskRecord supply) {
                            check(supply.itemIds.contains(ResourceLocation.parse("minecraft:flint_and_steel"))
                                    && supply.itemIds.contains(ResourceLocation.parse("minecraft:fire_charge")), "either ignition item satisfies the prerequisite");
                            supplied[0] = prepared;
                            if (prepared) { h.inventory.setItem(3, new ItemStack(Items.FLINT_AND_STEEL)); h.inventory.setItem(4, ItemStack.EMPTY); }
                            return prepared ? TaskState.SUCCESS : TaskState.FAILED;
                        }
                        return TaskState.RUNNING;
                    }
                    public TaskResult result(TaskState state) { return state == TaskState.SUCCESS
                            ? TaskResult.ok("fixture ignition supplied") : TaskResult.fail("fixture ignition missing", Map.of()); }
                };
            });
            task.start(h.player); var state = TaskState.RUNNING;
            for (int tick = 0; tick < 1000 && state == TaskState.RUNNING && !construction[0]; tick++) {
                h.nextTick(); TargetIndex.clientTick(h.level); state = task.tick(h.player);
            }
            check(construction[0] == prepared, "missing ignition stops before any construction task");
            if (prepared) { task.stop(h.player, Task.StopReason.REPLACED); task.result(TaskState.CANCELLED); }
            else check(state == TaskState.FAILED && "casting_prepare_ignition_failed".equals(task.result(state).data().get("issue_code")),
                    "the failure identifies the ignition prerequisite");
        }
        System.out.println("PortalIgnitionPreparationTest: ignition stock is verified before construction");
    }
}
