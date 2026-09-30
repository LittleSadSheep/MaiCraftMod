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
        return "Deposit, withdraw or balance semantic item groups against one loaded block "
                + "container. MaiCraft selects the real container, approaches and opens it in "
                + "first person, derives safe menu sides, performs receipt-confirmed transfers, "
                + "and verifies native transfers. An exact observed block target may be supplied; slots and clicks remain internal.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .enumStr("operation", "Semantic transfer direction.",
                        "deposit", "withdraw", "balance")
                .optionalString("item_id", "One namespaced selected item.")
                .optionalStringArray("item_ids",
                        "A semantic group of namespaced items, such as loot to store.")
                .optionalString("tag", "One namespaced item tag selector.")
                .optionalInteger("count",
                        "Exact total matching count to move; omit to move all source matches.",
                        1, SemanticContainerTaskRecord.MAX_COUNT)
                .optionalInteger("target_count",
                        "Final matching count on the destination side; required by balance.",
                        0, SemanticContainerTaskRecord.MAX_COUNT)
                .optionalString("block_id",
                        "Optional namespaced block type used to filter loaded containers.")
                .optionalString("landmark_label",
                        "Optional remembered place around which to choose the container.")
                .optionalEnum("selection",
                        "Nearest accepts the closest safe match; unique rejects ambiguity.",
                        "nearest", "unique")
                .optionalStringArray("protected_labels",
                        "Remembered places whose containers must not be touched.")
                .optionalInteger("radius", "Bounded loaded-container search radius.", 1,
                        SemanticContainerTaskRecord.MAX_RADIUS)
                // 坐标是目标箱体身份，不是角色必须站进去的位置；仍由原生执行器负责接近和开箱。
                .optionalInteger("x", "Exact observed container X; provide all three axes.", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .optionalInteger("y", "Exact observed container Y; provide all three axes.", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .optionalInteger("z", "Exact observed container Z; provide all three axes.", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .optionalString("dimension", "Exact container dimension; defaults to the current dimension.")
                .optionalBool("may_alter_terrain", "Permit native route preparation while approaching.")
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
