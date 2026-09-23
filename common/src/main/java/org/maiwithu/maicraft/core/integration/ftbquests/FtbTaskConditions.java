// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ftbquests;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;

/** 按原生类型解释角色应完成的动作，保留精确目标、单位和匹配条件；解析本身不替代 FTB 的完成判定。 */
final class FtbTaskConditions {
    private FtbTaskConditions() {}
    static JsonObject read(String type, CompoundTag data, String required) {
        JsonObject out = new JsonObject(); out.addProperty("type", type); out.addProperty("required", required);
        out.add("native", FtbQuestData.json(data)); out.addProperty("interpretation", "structured");
        switch (type) {
            case "ftbquests:item" -> out.addProperty("action", "collect_or_submit_matching_items");
            case "ftbquests:xp" -> {
                out.addProperty("action", "submit_experience"); out.addProperty("unit", data.getBoolean("points") ? "xp_points" : "xp_levels");
            }
            case "ftbquests:dimension" -> {
                out.addProperty("action", "enter_dimension"); copy(out, data, "dimension");
            }
            case "ftbquests:biome", "ftbquests:structure" -> {
                String key = type.substring(10); out.addProperty("action", "visit_" + key); copy(out, data, key);
                out.addProperty("target_is_tag", data.getString(key).startsWith("#"));
            }
            case "ftbquests:kill" -> {
                out.addProperty("action", "kill_matching_entities"); copy(out, data, "entity", "entityTypeTag", "custom_name", "nbt_filter");
                out.addProperty("unit", "kills");
            }
            case "ftbquests:location" -> location(out, data);
            case "ftbquests:stat" -> {
                out.addProperty("action", "reach_statistic"); copy(out, data, "stat"); out.addProperty("unit", "native_statistic");
            }
            case "ftbquests:checkmark" -> out.addProperty("action", "manual_checkmark");
            case "ftbquests:advancement" -> {
                out.addProperty("action", "obtain_advancement"); copy(out, data, "advancement", "criterion");
                out.addProperty("whole_advancement", data.getString("criterion").isEmpty());
            }
            case "ftbquests:observation" -> {
                out.addProperty("action", "look_at_matching_target"); copy(out, data, "observation_type", "to_observe");
                out.addProperty("duration_ticks", Long.toString(data.getLong("timer"))); out.addProperty("continuous", true);
            }
            case "ftbquests:gamestage" -> {
                out.addProperty("action", "obtain_stage"); copy(out, data, "stage"); out.addProperty("team_stage", data.getBoolean("team_stage"));
            }
            case "ftbquests:fluid" -> {
                out.addProperty("action", "submit_fluid"); out.addProperty("unit", "architectury_fluid_units"); copy(out, data, "fluid");
            }
            case "ftbquests:forge_energy", "ftbquests:tech_reborn_energy" -> {
                out.addProperty("action", "supply_task_screen_energy"); out.addProperty("unit", type.endsWith("forge_energy") ? "FE" : "E");
                out.addProperty("max_input", Long.toString(data.getLong("max_input")));
                out.addProperty("input_limit_configured", data.getLong("max_input") > 0);
            }
            case "ftbquests:custom" -> {
                out.addProperty("action", "server_defined"); out.addProperty("interpretation", "server_defined");
                out.addProperty("detail", "完成条件由服务器脚本提供，客户端仅能读取同步的目标、进度和按钮设置");
            }
            default -> {
                out.addProperty("interpretation", "native_definition_only");
                out.addProperty("detail", "扩展类型的原生定义已保留，尚未映射成 MaiCraft 操作条件");
            }
        }
        return out;
    }
    private static void copy(JsonObject out, CompoundTag data, String... keys) {
        for (String key : keys) if (data.contains(key)) out.add(key, FtbQuestData.json(data.get(key)));
    }
    private static void location(JsonObject out, CompoundTag data) {
        // 角色脚下方块必须在原生的左闭右开长方体内，size 是边长而不是以 position 为中心的半径。
        out.addProperty("action", "enter_block_region"); copy(out, data, "dimension", "position", "size");
        out.addProperty("ignore_dimension", data.getBoolean("ignore_dimension"));
        int[] min = data.getIntArray("position"), size = data.getIntArray("size");
        if (min.length == 3 && size.length == 3) {
            JsonArray end = new JsonArray(); for (int i = 0; i < 3; i++) end.add((long) min[i] + size[i]);
            out.add("max_exclusive", end); out.addProperty("position_sampling", "floor_player_position");
        }
    }
}
