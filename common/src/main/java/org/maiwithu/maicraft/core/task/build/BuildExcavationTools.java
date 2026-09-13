package org.maiwithu.maicraft.core.task.build;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.acquire.WorkToolPreparation;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** Obtain tools through the same visible, permission-bound supply path as building materials. */
final class BuildExcavationTools {
    private final SemanticMaterialSupplyCoordinator supply = new SemanticMaterialSupplyCoordinator();
    private final List<BlockPos> protection;
    private String failure;

    BuildExcavationTools(BuildTaskRecord record) {
        var cells = new java.util.ArrayList<>(record.targets.stream().map(BuildTaskRecord.Target::pos).toList());
        cells.addAll(record.materialSupplyProtection());
        protection = List.copyOf(cells);
    }

    boolean ready(LocalPlayer player, BuildTaskRecord record, BlockPos target, int work,
                  Function<Task, TaskState> childRunner) {
        if (player.getAbilities().instabuild) return true;
        if (supply.active()) {
            var result = NavigationSafetyContext.withProtectedArea(protection, List.of(),
                    () -> supply.tick(player, childRunner));
            record.extendDeadlineTo(supply.childDeadline());
            if (result.status() == SemanticMaterialSupplyCoordinator.Status.FAILED) failure = result.message();
            return false;
        }
        var item = WorkToolPreparation.excavationTool(player, player.level().getBlockState(target), work);
        if (item == null) return true;
        var options = record.toolSupply();
        if (options.policy() == SemanticMaterialSupplyCoordinator.MaterialPolicy.INVENTORY_ONLY) {
            failure = "Bulk excavation needs a serviceable " + item + "; inventory-only policy forbids acquiring one";
            return false;
        }
        int existing = PlayerInv.buildableCount(player.getInventory(), BuiltInRegistries.ITEM.get(item));
        supply.begin(player, record.getToolCallId() + "/excavation-tool", record.getDeadlineGameTime(),
                new SemanticMaterialSupplyCoordinator.Demand(List.of(item), existing + 1, "tool for bulk excavation"),
                options.policy(), options.sources(), options.allowHarm(), options.protectedLabels());
        return false;
    }

    String failure() { return failure; }
    boolean active() { return supply.active(); }
    Map<String, Object> progress() { return supply.active() ? supply.progress() : Map.of(); }
    void stop(LocalPlayer player) { supply.cancel(player); }
}
