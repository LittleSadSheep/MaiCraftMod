package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import org.maiwithu.maicraft.behavior.navigation.execute.PlayerNav;
import org.maiwithu.maicraft.behavior.navigation.moves.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.moves.movements.BuildPlacementRegistry;
import org.maiwithu.maicraft.behavior.navigation.settings.ClearanceWhitelist;
import org.maiwithu.maicraft.behavior.navigation.settings.ScaffoldMaterials;

/** 无路时保存真实起点、眼前清障限制与手边材料，避免一条 no_path 隐去模型调整行动所需的事实。 */
final class NavigationFailureEvidence {
    private NavigationFailureEvidence() {}

    static Map<String, Object> capture(LocalPlayer player, BlockPos destination, TerrainPermit permit,
                                       EmbeddedBaritonePolicy.Snapshot policy) {
        var facts = new LinkedHashMap<String, Object>();
        facts.put("dimension", player.level().dimension().location().toString());
        facts.put("origin", List.of(player.getX(), player.getY(), player.getZ()));
        facts.put("destination", position(destination)); facts.put("terrain_permit", permit.name());
        BlockPos feet = PlayerNav.playerFeet(player);
        var cells = new ArrayList<Map<String, Object>>();
        // 脚下支撑、身体两格和跳起所需顶格分别呈现；这些是现场观察，不声称已经解释整条路线为何失败。
        for (int dy = -1; dy <= 2; dy++) {
            BlockPos cell = feet.offset(0, dy, 0);
            if (!player.level().isLoaded(cell)) { cells.add(Map.of("position", position(cell), "loaded", false)); continue; }
            var state = player.level().getBlockState(cell);
            cells.add(Map.of("position", position(cell), "loaded", true,
                    "block_id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), "state", state.toString(),
                    "clearance_whitelisted", ClearanceWhitelist.allows(state),
                    "mutation_protected", policy.protects(cell.getX(), cell.getY(), cell.getZ()),
                    "body_forbidden", policy.forbidsBody(cell.getX(), cell.getY(), cell.getZ())));
        }
        facts.put("origin_cells", List.copyOf(cells));
        var carried = new LinkedHashMap<String, Integer>();
        for (var stack : player.getInventory().items) if (!stack.isEmpty() && stack.getItem() instanceof BlockItem)
            carried.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        facts.put("carried_blocks", Map.copyOf(carried));
        facts.put("allowed_scaffold_materials", ScaffoldMaterials.effectiveIds(player));
        // 采用执行器同一选料器，主背包槽位及施工材料预留都会参与，不用库存总数冒充可花垫块。
        var selected = BuildPlacementRegistry.scaffoldChoice(player);
        facts.put("selected_scaffold", selected == null ? Map.of("available", false)
                : Map.of("available", true, "item_id", BuiltInRegistries.ITEM.getKey(selected.item()).toString(),
                        "inventory_slot", selected.inventorySlot()));
        // 起点在岩浆致死邻域内时给出脱困建议(净远离方向与建议撤退点)，失败回执不再是单纯 no path。
        var escape = org.maiwithu.maicraft.behavior.navigation.HazardEscapePolicy.detect(
                player.level(), feet, cell -> player.level().isLoaded(cell));
        if (escape.active()) {
            facts.put("hazard_escape", escape.evidence());
        }
        return Map.copyOf(facts);
    }

    private static List<Integer> position(BlockPos cell) { return List.of(cell.getX(), cell.getY(), cell.getZ()); }
}
