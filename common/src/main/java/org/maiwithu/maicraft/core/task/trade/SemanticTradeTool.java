// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.trade;

import static org.maiwithu.maicraft.core.tools.SemanticParameters.integer;
import static org.maiwithu.maicraft.core.tools.SemanticParameters.strings;
import static org.maiwithu.maicraft.core.tools.SemanticParameters.text;
import static org.maiwithu.maicraft.task.TaskDispatch.ctx;
import static org.maiwithu.maicraft.task.TaskDispatch.setTask;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.Schema;

/**
 * 内部交易入口，只接收想获得什么、最终数量和商人／付款策略。
 * 具体找谁、用哪项报价以及怎样点菜单由 SemanticTradeCompanionTask 处理。
 */
public final class SemanticTradeTool implements MaiCraftTool {
    @Override public String name() { return SemanticTradeTaskRecord.TOOL_NAME; }

    @Override
    public String description() {
        return "Make a final main-inventory item count true through an ordinary loaded villager "
                + "or wandering-trader offer. Declare only output, count, merchant/payment policy "
                + "and protected labels. MaiCraft selects the concrete loaded merchant and offer, "
                + "approaches in first person, uses synchronized menu receipts, and verifies the "
                + "real inventory after every trade.";
    }

    @Override
    // 默认只花绿宝石；其他付款需要名单同时覆盖报价的两种成本，不能把省略解释为任意付款。
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Requested namespaced trade output.")
                // 内部工具仍收最终合计数（取物里的交易来源直接给目标总数）；公开交易能力在语义步骤启动时把它绑定成“再换几件”。
                .integer("count", "Required final main-inventory count. The public maicraft:trade ability binds it as an additional count over the step's starting inventory.", 1,
                        SemanticTradeTaskRecord.MAX_FINAL_COUNT)
                .optionalEnum("merchant_kind", "Allowed merchant family.",
                        "auto", "villager", "wandering_trader")
                .optionalStringArray("allowed_payment_items",
                        "Allowed payment item IDs; omit, null or an empty list permits emerald payments only. Both costs of an offer must be allowed.")
                .optionalStringArray("protected_labels",
                        "Remembered places whose merchants must not be selected.")
                .integer("radius", "Loaded-entity search radius.", 1,
                        SemanticTradeTaskRecord.MAX_RADIUS)
                .build();
    }

    @Override
    // 解析目标和支付策略后，按目标数量给初始时限，再建立持续交易任务；不在工具入口直接买卖。
    public void onGameCall(
            String toolCallId, JsonObject args, LocalPlayer player, Consumer<String> reply) {
        ResourceLocation itemId = resource(args.get("item_id"), "item_id");
        int count = integer(args, "count", 1, 1, SemanticTradeTaskRecord.MAX_FINAL_COUNT);
        List<ResourceLocation> payments = new ArrayList<>();
        for (String value : strings(args.get("allowed_payment_items"),
                "allowed_payment_items")) {
            ResourceLocation id = ResourceLocation.tryParse(value);
            if (id == null) throw new IllegalArgumentException(
                    "allowed_payment_items contains an invalid resource id: " + value);
            payments.add(id);
        }
        List<String> labels = strings(args.get("protected_labels"), "protected_labels");
        int radius = integer(args, "radius", SemanticTradeTaskRecord.DEFAULT_RADIUS,
                1, SemanticTradeTaskRecord.MAX_RADIUS);
        long initialLease = Math.clamp(4L * 60L * 20L + (long) count * 120L,
                4L * 60L * 20L, 30L * 60L * 20L);
        var context = ctx(toolCallId, player);
        var record = new SemanticTradeTaskRecord(
                context.toolCallId(), context.deadline(initialLease), itemId, count,
                SemanticTradeTaskRecord.MerchantKind.parse(text(args, "merchant_kind")),
                payments, labels, radius);
        setTask(player, record, args, reply);
    }

    private static ResourceLocation resource(JsonElement value, String key) {
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException(key + " must be a namespaced item id");
        }
        ResourceLocation id = ResourceLocation.tryParse(value.getAsString());
        if (id == null) throw new IllegalArgumentException(
                key + " must be a namespaced item id");
        return id;
    }
}
