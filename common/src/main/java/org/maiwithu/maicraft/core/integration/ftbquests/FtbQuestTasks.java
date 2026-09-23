// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ftbquests;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.server.machine.NativeApi;
import static org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestApi.*;

/** 读取任务目标与服务器同步的进度；展示需要收集或消耗什么，但不提交物品、不勾选任务。 */
public final class FtbQuestTasks {
    private FtbQuestTasks() {}

    public static JsonObject read(Object task, Object team) {
        JsonObject result = identity(task);
        String type = call(call(task, "getType"), "getTypeId").toString();
        result.addProperty("type", type);
        // 完成记录以 FTB 为准；达到数量也可能仍受前置、任务顺序或人工确认限制。
        result.addProperty("progress", call(team, "getProgress", task).toString());
        result.addProperty("required", call(task, "getMaxProgress").toString());
        result.addProperty("completed", flag(team, "isCompleted", task));
        result.addProperty("optional_for_progression", flag(task, "isOptionalForProgression", team));
        result.addProperty("consumes_resources", flag(task, "consumesResources"));
        try {
            // 原生写出接口只序列化当前目标定义，保留维度、实体、范围等条件和缺省值语义。
            CompoundTag data = FtbQuestData.definition(task);
            result.addProperty("definition_snbt", data.toString());
            JsonObject conditions = FtbTaskConditions.read(type, data, result.get("required").getAsString());
            result.add("conditions", conditions);
            result.addProperty("formatted_required", call(task, "formatMaxProgress").toString());
            result.addProperty("formatted_progress", call(task, "formatProgress", team, Long.parseLong(result.get("progress").getAsString())).toString());
            if (type.equals("ftbquests:item")) item(result, task, data);
            if (type.equals("ftbquests:fluid")) conditions.addProperty("units_per_bucket",
                    NativeApi.call(null, "dev.architectury.fluid.FluidStack", "bucketAmount").toString());
            if (type.equals("ftbquests:custom")) {
                conditions.addProperty("manual_button_enabled", (Boolean) FtbQuestData.field(task, "enableButton"));
                conditions.addProperty("check_interval_ticks", (Number) FtbQuestData.field(task, "checkTimer"));
            }
            result.addProperty("conditions_status", conditions.get("interpretation").getAsString());
        } catch (RuntimeException | LinkageError unavailable) {
            // 第三方类型或版本接口失配时保留确实读到的名称与进度，不能猜一个物品目标冒充原条件。
            result.addProperty("conditions_status", "api_unavailable");
        }
        return result;
    }

    private static void item(JsonObject result, Object task, CompoundTag data) {
        ItemStack stack = (ItemStack) call(task, "getItemStack");
        result.addProperty("item_id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        result.addProperty("item_name", stack.getHoverName().getString());
        result.addProperty("only_from_crafting", flag(task, "isOnlyFromCrafting"));
        result.addProperty("task_screen_only", flag(task, "isTaskScreenOnly"));
        result.addProperty("match_components", data.contains("match_components") ? data.getString("match_components") : "none");
        // 过滤器可能匹配很多材料；只列展示样例并标明总数，真实匹配仍由 FTB 的任务定义决定。
        List<?> items = (List<?>) call(task, "getValidDisplayItems");
        JsonArray examples = new JsonArray();
        for (Object item : items.subList(0, Math.min(16, items.size()))) {
            ItemStack example = (ItemStack) item;
            JsonObject row = new JsonObject(); row.addProperty("item_id", BuiltInRegistries.ITEM.getKey(example.getItem()).toString());
            row.addProperty("name", example.getHoverName().getString()); examples.add(row);
        }
        result.add("display_examples", examples); result.addProperty("display_example_count", items.size());
        result.addProperty("display_examples_truncated", items.size() > examples.size());
    }
}
