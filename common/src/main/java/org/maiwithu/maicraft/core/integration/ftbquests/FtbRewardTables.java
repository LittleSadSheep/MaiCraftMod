// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ftbquests;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.maiwithu.maicraft.server.machine.NativeApi;
import static org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestApi.*;

/** 奖励表只列候选、权重和抽取规则；子奖励另读，既不展开循环引用，也不调用随机生成器。 */
final class FtbRewardTables {
    private static final Set<String> TYPES = Set.of("ftbquests:choice", "ftbquests:random", "ftbquests:loot", "ftbquests:all_table");
    private FtbRewardTables() {}
    static boolean isTable(Object reward) {
        // 扩展奖励也可能继承原生奖池，不能因类型 ID 不认识就用序列化间接遍历其隐藏内容。
        return TYPES.contains(FtbQuestRewards.type(reward)) || NativeApi.is(reward, "dev.ftb.mods.ftbquests.quest.reward.RandomReward");
    }
    static Object visibleTable(Object reward, boolean canChoose) {
        if (!isTable(reward)) return null;
        Object table = call(reward, "getTable");
        // 隐藏提示的选择奖励只有在当前玩家可打开选择界面时才展示候选；其他隐藏奖池保持隐藏。
        return table != null && (flag(table, "shouldShowTooltip") || canChoose && FtbQuestRewards.type(reward).equals("ftbquests:choice")) ? table : null;
    }
    static List<?> rows(Object table) { return (List<?>) call(table, "getWeightedRewards"); }
    static String revision(Object table) {
        JsonArray identity = new JsonArray(); identity.add(id(table));
        for (Object row : rows(table)) {
            JsonObject entry = FtbQuestApi.identity(call(row, "getReward"));
            entry.addProperty("weight", (Number) call(row, "getWeight")); identity.add(entry);
        }
        return digest(identity.toString());
    }
    static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)), 0, 8); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static JsonObject read(Object reward, Object table, String path, int offset) {
        JsonObject out = identity(table); String type = FtbQuestRewards.type(reward), revision = revision(table);
        boolean random = type.equals("ftbquests:random") || type.equals("ftbquests:loot");
        boolean includeEmpty = type.equals("ftbquests:loot");
        double total = ((Number) call(table, "getTotalWeight", includeEmpty)).doubleValue();
        out.addProperty("mode", TYPES.contains(type) ? type.substring(10) : "native_table_definition"); out.addProperty("revision", revision);
        out.addProperty("loot_size", (Number) FtbQuestData.field(table, "lootSize"));
        // 选择奖励固定选一项，全表奖励直接发全部；只有随机模式才使用 lootSize 作为抽取次数。
        if (random) out.addProperty("draws", (Number) FtbQuestData.field(table, "lootSize"));
        else if (type.equals("ftbquests:choice")) out.addProperty("selection_count", 1);
        else if (type.equals("ftbquests:all_table")) out.addProperty("grants_all_entries", true);
        out.addProperty("total_weight", total);
        if (includeEmpty) out.addProperty("empty_weight", (Number) FtbQuestData.field(table, "emptyWeight"));
        List<?> rows = rows(table); JsonArray entries = new JsonArray();
        for (int i = FtbQuestRewards.start(offset, rows.size()); i < Math.min(offset + 40, rows.size()); i++) {
            Object weighted = rows.get(i), child = call(weighted, "getReward"); JsonObject entry = identity(child);
            double weight = ((Number) call(weighted, "getWeight")).doubleValue();
            entry.addProperty("type", FtbQuestRewards.type(child)); entry.addProperty("weight", weight);
            // 原生随机表的零权重项在有效抽奖时自动发放；分母为零时并不会产生任何奖励。
            if (random) {
                entry.addProperty("guaranteed_when_table_rolls", weight == 0 && total > 0);
                entry.addProperty("chance_per_draw", weight > 0 && total > 0 ? weight / total : 0);
            }
            entry.addProperty("path", path + "/" + i + "~" + revision); entries.add(entry);
        }
        out.add("entries", entries); FtbQuestRewards.page(out, path, offset, rows.size());
        return out;
    }
}
