// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import static org.maiwithu.maicraft.core.task.dimension.NetherPortalFrameTest.check;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.scan.TargetIndex;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

public final class PortalPreparationSupplyTest {
    public static void main(String[] args) throws Exception {
        for (boolean needsDimension : new boolean[]{false, true}) {
            try (var world = new InteractionWorldTestHarness()) {
                var policy = new PortalPreparationPolicy(true, true, false, 128, MaterialPolicy.INVENTORY_ONLY, List.of(), List.of());
                var calls = new int[1];
                var task = new PortalPreparationTask(world.player,
                        new PortalPreparationTaskRecord("supply", 2000, "minecraft:the_end", 16, false, policy),
                        (player, record) -> {
                            calls[0]++;
                            check(record instanceof SemanticAcquireTaskRecord, "the first prerequisite is real semantic acquisition");
                            return new Task() {
                                public String name() { return "observed_supply_result"; }
                                public void stop(LocalPlayer body, StopReason reason) {}
                                public TaskState tick(LocalPlayer body) { return needsDimension ? TaskState.FAILED : TaskState.SUCCESS; }
                                public TaskResult result(TaskState state) {
                                    return needsDimension ? new TaskResult(false, "blaze material requires the Nether", false, false,
                                            Map.of("failure_type", "requires_dimension", "allowed_dimensions", List.of("minecraft:the_nether")))
                                            : TaskResult.ok("acquisition reported success");
                                }
                            };
                        });
                task.start(world.player);
                TaskState state = TaskState.RUNNING;
                for (int i = 0; i < 200 && state == TaskState.RUNNING; i++) {
                    world.nextTick(); TargetIndex.clientTick(world.level); state = task.tick(world.player);
                }
                check(state == TaskState.FAILED && calls[0] == 1, "unproven supply cannot loop or start construction");
                var result = task.result(state);
                check(result.data().get("issue_code").equals(needsDimension ? "requires_dimension" : "portal_supply_unverified"),
                        "supply must retain typed travel requirements and reject success without inventory proof");
                if (needsDimension) check(result.data().get("allowed_dimensions").equals(List.of("minecraft:the_nether")),
                        "progression can select a supply dimension from the retained evidence");
                check(world.blockUses() == 0 && world.itemUses() == 0, "unresolved materials cannot consume activation items");
            }
        }
        System.out.println("PortalPreparationSupplyTest: typed dimension requirements and inventory proof passed");
    }
}
