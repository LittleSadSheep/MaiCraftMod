// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ftbquests;

import java.util.List;
import net.minecraft.nbt.CompoundTag;

/** 防止把等级误作经验点、把区域边长误作半径，或将客户端没有的脚本规则补成自创目标。 */
public final class FtbTaskConditionsTest {
    public static void main(String[] args) {
        CompoundTag data = new CompoundTag(); data.putLong("value", Long.MAX_VALUE);
        var levels = FtbTaskConditions.read("ftbquests:xp", data, Long.toString(Long.MAX_VALUE));
        check(levels.get("unit").getAsString().equals("xp_levels")
                && levels.getAsJsonObject("native").get("value").getAsString().equals("9223372036854775807"), "等级与大整数保持原义");
        data.putBoolean("points", true);
        check(FtbTaskConditions.read("ftbquests:xp", data, "30").get("unit").getAsString().equals("xp_points"), "经验点单独标单位");
        data.putIntArray("position", new int[]{-5, 64, 10}); data.putIntArray("size", new int[]{3, 2, 1});
        var location = FtbTaskConditions.read("ftbquests:location", data, "1");
        check(location.getAsJsonArray("max_exclusive").toString().equals("[-2,66,11]")
                && !location.get("ignore_dimension").getAsBoolean(), "负坐标和区域上界遵守原生左闭右开规则");
        data.putString("entityTypeTag", "minecraft:skeletons"); data.putString("nbt_filter", "{Health:1f}");
        var kill = FtbTaskConditions.read("ftbquests:kill", data, "5");
        check(kill.has("entityTypeTag") && kill.has("nbt_filter"), "实体标签和 NBT 附加条件不能丢失");
        data.putString("criterion", "" );
        check(FtbTaskConditions.read("ftbquests:advancement", data, "1").get("whole_advancement").getAsBoolean(), "空 criterion 表示整个进度");
        data.putLong("timer", 40);
        check(FtbTaskConditions.read("ftbquests:observation", data, "1").get("duration_ticks").getAsString().equals("40"), "凝视时长使用游戏刻");
        for (String type : List.of("item", "dimension", "biome", "structure", "stat", "checkmark", "gamestage", "fluid", "forge_energy", "tech_reborn_energy"))
            check(FtbTaskConditions.read("ftbquests:" + type, data, "1").get("interpretation").getAsString().equals("structured"), "原生类型可解释：" + type);
        check(FtbTaskConditions.read("ftbquests:custom", data, "1").get("interpretation").getAsString().equals("server_defined"), "服务器脚本不可伪造解析");
        check(FtbTaskConditions.read("extension:trial", data, "1").get("interpretation").getAsString().equals("native_definition_only"), "扩展字段保留原始定义");
        System.out.println("FtbTaskConditionsTest: passed");
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
