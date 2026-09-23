// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ftbquests;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import static org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestApi.*;

/** 以当前玩家读取领取记录；共享奖励继续由 FTB 使用队伍键判定，查看候选不会领取、选择或抽取。 */
final class FtbQuestRewards {
    private FtbQuestRewards() {}
    static String type(Object reward) { return call(call(reward, "getType"), "getTypeId").toString(); }
    static List<Object> visible(Object quest, Object team) {
        List<Object> result = new ArrayList<>();
        for (Object reward : (Iterable<?>) call(quest, "getRewards"))
            if (!flag(team, "isRewardBlocked", reward) && !call(reward, "getAutoClaimType").toString().equalsIgnoreCase("invisible")) result.add(reward);
        return result;
    }
    static JsonObject state(Object reward, Object team, UUID player) {
        JsonObject result = identity(reward); Object claim = call(team, "getClaimType", player, reward);
        result.addProperty("type", type(reward)); result.addProperty("team_reward", flag(reward, "isTeamReward"));
        result.addProperty("claimed", flag(claim, "isClaimed")); result.addProperty("can_claim", flag(claim, "canClaim") && !flag(team, "isRewardBlocked", reward));
        result.addProperty("auto_claim", call(reward, "getAutoClaimType").toString().toLowerCase(Locale.ROOT));
        result.addProperty("exclude_from_claim_all", flag(reward, "getExcludeFromClaimAll"));
        Optional<?> when = (Optional<?>) call(team, "getRewardClaimTime", player, reward);
        when.ifPresent(value -> result.addProperty("claimed_at", ((Date) value).toInstant().toString())); return result;
    }
    static JsonObject read(Object quest, Object team, UUID player, String path, int offset) {
        List<Object> rewards = visible(quest, team); JsonObject out = new JsonObject();
        if (path.isEmpty()) {
            JsonArray entries = new JsonArray();
            JsonArray identities = new JsonArray(); rewards.forEach(reward -> identities.add(identity(reward)));
            out.addProperty("revision", FtbRewardTables.digest(identities.toString()));
            for (Object reward : rewards.subList(start(offset, rewards.size()), Math.min(offset + 40, rewards.size()))) {
                JsonObject row = state(reward, team, player); row.addProperty("path", id(reward)); entries.add(row);
            }
            out.add("entries", entries); page(out, path, offset, rewards.size()); return out;
        }
        String[] parts = path.split("/"); Object reward = rewards.stream().filter(value -> id(value).equals(parts[0])).findFirst().orElse(null);
        if (reward == null) return null;
        JsonObject rootState = state(reward, team, player);
        for (int i = 1; i < parts.length; i++) {
            Object table = FtbRewardTables.visibleTable(reward, i == 1 && rootState.get("can_claim").getAsBoolean());
            if (table == null) return null;
            String[] step = parts[i].split("~");
            if (!FtbRewardTables.revision(table).equals(step[1])) throw new IllegalArgumentException("FTB reward table changed; rediscover its entries");
            List<?> rows = FtbRewardTables.rows(table); int index = Integer.parseInt(step[0]);
            if (index >= rows.size()) return null;
            reward = call(rows.get(index), "getReward");
        }
        // 奖池条目不是独立可领取的任务奖励，始终把领取状态附在根奖励，避免错误建议重复领取。
        out.add("claim", rootState); out.addProperty("claim_scope", parts.length == 1 ? "quest_reward" : "parent_reward");
        JsonObject content = identity(reward); String type = type(reward); content.addProperty("type", type);
        out.add("reward", content);
        if (FtbRewardTables.isTable(type)) {
            Object table = FtbRewardTables.visibleTable(reward, parts.length == 1 && rootState.get("can_claim").getAsBoolean());
            content.addProperty("content_status", table == null ? "hidden_or_unavailable" : "structured");
            if (table != null) out.add("table", FtbRewardTables.read(reward, table, path, offset));
        } else {
            if (offset != 0) throw new IllegalArgumentException("Only reward tables accept offsets");
            try { FtbRewardContents.append(content, reward, type); }
            catch (RuntimeException | LinkageError unavailable) { content.addProperty("content_status", "api_unavailable"); }
        }
        return out;
    }
    static int start(int offset, int size) {
        if (offset < 0 || offset > size || offset > 0 && offset == size) throw new IllegalArgumentException("FTB reward offset out of range"); return offset;
    }
    static void page(JsonObject result, String path, int offset, int total) {
        result.addProperty("path", path); result.addProperty("offset", offset); result.addProperty("total", total);
        if (total - offset > 40) result.addProperty("next_offset", offset + 40);
    }
}
