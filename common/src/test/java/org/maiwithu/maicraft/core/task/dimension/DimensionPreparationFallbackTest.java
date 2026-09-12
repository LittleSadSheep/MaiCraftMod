// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import static org.maiwithu.maicraft.core.task.dimension.NetherPortalFrameTest.check;
import java.util.List;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.scan.TargetIndex;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.TaskState;

public final class DimensionPreparationFallbackTest {
    public static void main(String[] args) throws Exception {
        for (boolean enabled : new boolean[]{false, true}) {
            try (var world = new InteractionWorldTestHarness()) {
                var policy = new PortalPreparationPolicy(enabled, false, false, 128, MaterialPolicy.INVENTORY_ONLY, List.of(), List.of());
                var record = new DimensionTravelTaskRecord("travel", 2000, "minecraft:the_end", 16, false, policy);
                var task = new DimensionTravelCompanionTask(world.player, record);
                task.start(world.player);
                TaskState state = TaskState.RUNNING;
                for (int i = 0; i < 200 && state == TaskState.RUNNING; i++) {
                    world.nextTick(); TargetIndex.clientTick(world.level); state = task.tick(world.player);
                }
                check(state == TaskState.FAILED, "missing prerequisites settle instead of looping");
                var result = task.result(state);
                check(result.data().get("issue_code").equals(enabled ? "rare_consumable_permission_required" : "portal_not_observed"),
                        "opted-in travel invokes preparation and preserves its actionable refusal");
                check(world.itemUses() == 0 && world.blockUses() == 0, "travel never infers consumption from a missing portal");
            }
        }
        System.out.println("DimensionPreparationFallbackTest: enabled fallback, legacy behavior and failure propagation passed");
    }
}
