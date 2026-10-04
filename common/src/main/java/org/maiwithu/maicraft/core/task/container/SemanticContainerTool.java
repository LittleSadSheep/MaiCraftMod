// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.container;

import static org.maiwithu.maicraft.core.tools.SemanticParameters.bool;
import static org.maiwithu.maicraft.core.tools.SemanticParameters.integer;
import static org.maiwithu.maicraft.core.tools.SemanticParameters.optionalInteger;
import static org.maiwithu.maicraft.core.tools.SemanticParameters.strings;
import static org.maiwithu.maicraft.core.tools.SemanticParameters.text;
import static org.maiwithu.maicraft.task.TaskDispatch.ctx;
import static org.maiwithu.maicraft.task.TaskDispatch.setTask;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.Schema;

/**
 * 内部语义容器入口，接受“存什么、取什么、要多少和选哪个区域的容器”，不要求调用方提供槽号。
 * 参数转成任务单后，后续每刻执行和结果确认由容器任务负责。
 */
public final class SemanticContainerTool implements MaiCraftTool {
    @Override public String name() { return SemanticContainerTaskRecord.TOOL_NAME; }

    @Override
    public String description() {
        // 定向存取先选一只箱子、走近开菜单，再按真实槽位搬运和关页；找材料的多箱调查由取物父任务负责。
        return "Internal executor for one loaded block container: select, approach, open or reuse its native menu, "
                + "classify the main-inventory and container sides, plan the requested aggregate quantity, "
                + "confirm each native transfer, then close the menu. Public maicraft:manage_container "
                + "supplies goal.parameters and goal.target; its adapter converts coordinates to x/y/z/dimension "
                + "and remembered targets to landmark_label here. Do not send public callers slot numbers or click plans. "
                + "deposit moves matching carried items into the container; withdraw moves them into the first 36 "
                + "player inventory slots; balance makes that carried aggregate equal target_count. "
                + "count is an additional transfer amount. target_count is instead the final container minimum "
                + "for deposit, carried minimum for withdraw, and exact carried amount for balance. "
                + "Omitting both amounts means all source matches for deposit/withdraw, never for balance. "
                + "Selection and search do not guarantee stock, space, access or a supported menu. "
                + "Inspect moved_count/moved_items, goal_satisfied, outcome_partial, outcome_uncertain and "
                + "last_native_transfer together; confirmed moves survive failure and are not rolled back. "
                + "A failed child can retain confirmed partial units only in last_native_transfer.data.moved_counts, "
                + "even when the parent moved_count is zero and outcome_partial is false. "
                + "This public one-container flow does not inherit automatic acquisition's three-state visit order or fixed 32-block origin.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        // 这里描述适配后的内部任务单；公开调用的位置在 goal.target，物品与数量在 goal.parameters。
        return Schema.object()
                .enumStr("operation", "Required direction: deposit into container, withdraw into main inventory, or balance main inventory to target_count; no public default.",
                        "deposit", "withdraw", "balance")
                .optionalString("item_id", "One item ID; merged and deduplicated with item_ids. The nonempty ID group is mutually exclusive with tag.")
                .optionalStringArray("item_ids",
                        "Accepted item IDs aggregated into one quantity, not a quantity per ID. ID/tag selection does not filter item components.")
                .optionalString("tag", "One live item tag such as minecraft:planks, without #. Alternative to a nonempty item_id/item_ids group; unknown tags fail during survey.")
                .optionalInteger("count",
                        "Additional aggregate units to move, 1..4096. Exclusive with target_count. If both are omitted/null, deposit/withdraw attempt all matching source items; an empty source fails.",
                        1, SemanticContainerTaskRecord.MAX_COUNT)
                .optionalInteger("target_count",
                        "0..4096, exclusive with count. deposit: container total at least this amount; withdraw: main-inventory total at least this amount; balance: main-inventory total exactly this amount. Required by balance; zero is valid, including balance=0 to put away all carried matches.",
                        0, SemanticContainerTaskRecord.MAX_COUNT)
                .optionalString("block_id",
                        "Optional live block ID filter. Does not assert that the block exposes a usable native inventory; AE2 virtual storage uses its dedicated path.")
                .optionalString("landmark_label",
                        "Remembered same-dimension place used as the radius center; matching custom container names are preferred within that area. Missing labels fail instead of falling back to the player.")
                .optionalEnum("selection",
                        "Default unique requires one candidate; nearest takes the first candidate by matching name then distance from the search center. Public target.kind/relation=nearest is also compiled to nearest. This is not a search across inventories.",
                        "nearest", "unique")
                .optionalStringArray("protected_labels",
                        "Remembered labels to preserve, default empty. Same-dimension landmarks exclude a 12-block sphere; matching custom names are excluded too. Every supplied label must be remembered.")
                .optionalInteger("radius", "Search radius in blocks, default 32, range 1..64, measured in 3D from player or landmark. An exact target bypasses this candidate-radius search; it is not a travel leash.", 1,
                        SemanticContainerTaskRecord.MAX_RADIUS)
                // 坐标是目标箱体身份，不是角色必须站进去的位置；仍由原生执行器负责接近和开箱。
                .optionalInteger("x", "Exact observed container X; provide all three axes.", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .optionalInteger("y", "Exact observed container Y; provide all three axes.", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .optionalInteger("z", "Exact observed container Z; provide all three axes.", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .optionalString("dimension", "Exact container dimension; defaults to the current dimension.")
                .optionalBool("may_alter_terrain", "Default false, including null. True permits native route preparation while approaching; it does not bypass access, item or slot checks.")
                .build();
    }

    @Override
    // 合并 item_id 与 item_ids、解析标签和数量，创建语义容器任务；具体是否能找到容器由任务调查。
    public void onGameCall(
            String toolCallId, JsonObject args, LocalPlayer player, Consumer<String> reply) {
        LinkedHashSet<ResourceLocation> ids = new LinkedHashSet<>();
        String one = text(args, "item_id");
        if (one != null) ids.add(resource(one, "item_id"));
        for (String value : strings(args.get("item_ids"), "item_ids")) {
            ids.add(resource(value, "item_ids"));
        }
        String tagText = text(args, "tag");
        ResourceLocation tag = tagText == null ? null : resource(tagText, "tag");
        String blockText = text(args, "block_id");
        ResourceLocation block = blockText == null ? null : resource(blockText, "block_id");
        Integer count = optionalInteger(args, "count", 1, SemanticContainerTaskRecord.MAX_COUNT);
        Integer targetCount = optionalInteger(
                args, "target_count", 0, SemanticContainerTaskRecord.MAX_COUNT);
        List<String> labels = strings(args.get("protected_labels"), "protected_labels");
        int radius = integer(args, "radius", SemanticContainerTaskRecord.DEFAULT_RADIUS,
                1, SemanticContainerTaskRecord.MAX_RADIUS);
        var context = ctx(toolCallId, player);
        var record = new SemanticContainerTaskRecord(
                context.toolCallId(), context.deadline(10L * 60L * 20L),
                SemanticContainerTaskRecord.Operation.parse(text(args, "operation")),
                new ArrayList<>(ids), tag, count, targetCount, block,
                text(args, "landmark_label"),
                SemanticContainerTaskRecord.Selection.parse(text(args, "selection")),
                labels, radius);
        // 精确目标也复用严格整数契约；null 等同缺席，部分坐标不得被默认为别的箱子。
        Integer x = optionalInteger(args, "x", Integer.MIN_VALUE, Integer.MAX_VALUE);
        Integer y = optionalInteger(args, "y", Integer.MIN_VALUE, Integer.MAX_VALUE);
        Integer z = optionalInteger(args, "z", Integer.MIN_VALUE, Integer.MAX_VALUE);
        if (x != null || y != null || z != null) {
            if (x == null || y == null || z == null) throw new IllegalArgumentException("exact container requires x, y and z");
            String dimension = text(args, "dimension");
            record.at(new BlockPos(x, y, z), dimension == null ? player.level().dimension().location().toString() : dimension);
        }
        record.mayAlterTerrain = bool(args, "may_alter_terrain", false);
        setTask(player, record, args, reply);
    }

    private static ResourceLocation resource(String value, String key) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) throw new IllegalArgumentException(key + " must use a namespaced id");
        return id;
    }
}
