// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.tools.work;

import static org.maiwithu.maicraft.core.tools.SemanticParameters.bool;
import static org.maiwithu.maicraft.core.tools.SemanticParameters.integer;
import static org.maiwithu.maicraft.core.tools.SemanticParameters.primitiveString;
import static org.maiwithu.maicraft.core.tools.SemanticParameters.rejectUnknown;
import static org.maiwithu.maicraft.core.tools.SemanticParameters.strings;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source;
import org.maiwithu.maicraft.core.task.cook.SemanticCookTaskRecord;

/** 计划与执行读取同一份烹饪要求；最终数量、燃料与取材许可不会在适配途中被截断或忽略。 */
public final class SemanticCookApi {
    private static final Set<String> PARAMETERS = Set.of("item_id", "count", "recipe_preference",
            "allowed_fuels", "allowed_sources", "allow_harm", "protected_labels");

    private SemanticCookApi() {}

    public record Arguments(ResourceLocation itemId, int count, SemanticCookTaskRecord.Preference preference,
                            List<ResourceLocation> fuels, List<Source> sources, boolean allowHarm, List<String> protectedLabels) {}

    public static Arguments parse(JsonObject args) {
        rejectUnknown(args, PARAMETERS, "cook");
        ResourceLocation item = resource(primitiveString(args, "item_id"), "item_id");
        int count = integer(args, "count", 1, 1, SemanticCookTaskRecord.MAX_FINAL_COUNT);
        var preference = SemanticCookTaskRecord.Preference.parse(primitiveString(args, "recipe_preference"));
        List<ResourceLocation> fuels = strings(args.get("allowed_fuels"), "allowed_fuels").stream()
                .map(value -> resource(value, "allowed_fuels")).distinct().toList();
        if (fuels.size() > SemanticCookTaskRecord.MAX_FUEL_ALTERNATIVES)
            throw new IllegalArgumentException("allowed_fuels accepts at most 64 items");
        List<Source> sources = strings(args.get("allowed_sources"), "allowed_sources").stream()
                .map(Source::parse).distinct().toList();
        // 原料再走 cook 会形成递归开炉；明确拒绝这项要求，不能悄悄删掉后继续执行别的策略。
        if (sources.contains(Source.COOK)) throw new IllegalArgumentException("cook inputs cannot recursively use the cook source");
        List<String> labels = strings(args.get("protected_labels"), "protected_labels").stream().distinct().toList();
        if (labels.size() > 64) throw new IllegalArgumentException("protected_labels accepts at most 64 labels");
        return new Arguments(item, count, preference, fuels, sources, bool(args, "allow_harm", false), labels);
    }

    public static SemanticCookTaskRecord newRecord(ToolContext context, JsonObject args) {
        Arguments request = parse(args);
        // 初始时限随总目标增加；真正加工中的活跃炉次仍由执行器根据服务端进度续时。
        long ticks = Math.clamp(5L * 60L * 20L + (long) request.count() * 260L,
                5L * 60L * 20L, 45L * 60L * 20L);
        return new SemanticCookTaskRecord(context.toolCallId(), context.deadline(ticks), request.itemId(),
                request.count(), request.preference(), request.fuels(), request.sources(), request.allowHarm(), request.protectedLabels());
    }

    private static ResourceLocation resource(String value, String key) {
        ResourceLocation id = value == null ? null : ResourceLocation.tryParse(value);
        if (id == null) throw new IllegalArgumentException(key + " needs a valid item resource id");
        return id;
    }
}
