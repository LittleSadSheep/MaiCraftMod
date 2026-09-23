// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ftbquests;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import java.util.UUID;
import java.util.Date;
import java.util.Optional;
import java.util.stream.Stream;
import static org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestApi.*;

/** 展开玩家点选的任务；先检查详情和正文门槛，再读前置与目标，避免绕过任务书的剧情解锁。 */
final class FtbQuestDetails {
    private FtbQuestDetails() {}

    static JsonObject summary(Object quest, Object team, UUID player) {
        JsonObject result = identity(quest);
        result.addProperty("completed", flag(team, "isCompleted", quest));
        result.addProperty("started", flag(team, "isStarted", quest));
        result.addProperty("can_start_tasks", flag(team, "canStartTasks", quest));
        result.addProperty("dependencies_satisfied", flag(team, "areDependenciesComplete", quest));
        result.addProperty("details_visible", !flag(quest, "hideDetailsUntilStartable") || flag(team, "canStartTasks", quest));
        // 目录只读当前玩家的领取状态，不为筛选“可领奖”提前解析物品或遍历奖励表。
        if (result.get("details_visible").getAsBoolean()) {
            int count = 0, claimable = 0, unclaimed = 0;
            for (Object reward : FtbQuestRewards.visible(quest, team)) {
                Object claim = call(team, "getClaimType", player, reward); count++;
                if (flag(claim, "canClaim")) claimable++;
                if (!flag(claim, "isClaimed")) unclaimed++;
            }
            result.addProperty("visible_reward_count", count); result.addProperty("claimable_reward_count", claimable);
            result.addProperty("unclaimed_reward_count", unclaimed);
        }
        return result;
    }

    static JsonObject read(Object quest, Object team, UUID player, Set<String> visibleIds) {
        JsonObject result = summary(quest, team, player);
        if (!result.get("details_visible").getAsBoolean()) return result;
        Object chapter = call(quest, "getChapter");
        boolean showText = !flag(call(quest, "getHideTextUntilComplete"), "get", flag(chapter, "isHideTextUntilComplete"))
                || flag(team, "isCompleted", quest);
        result.addProperty("subtitle", text(call(quest, "getSubtitle")));
        result.addProperty("text_visible", showText);
        if (showText) {
            result.add("description", lines(call(quest, "getDescription")));
            // 富文本与外部指南保留作者原文，供模型理解；读取这些字段不会执行链接或里面的指令。
            result.add("description_raw", lines(call(quest, "getRawDescription")));
            result.addProperty("guide_page", call(quest, "getGuidePage").toString());
        }
        result.addProperty("progression_mode", call(quest, "getProgressionMode").toString());
        result.addProperty("dependency_requirement", dependencyRequirement(quest));
        result.addProperty("min_required_dependencies", (Number) call(quest, "getMinRequiredDependencies"));
        result.addProperty("sequential_tasks", flag(quest, "getRequireSequentialTasks"));
        result.addProperty("optional", flag(quest, "isOptional"));
        result.addProperty("repeatable", flag(quest, "canBeRepeated"));
        result.addProperty("repeat_after_ms", call(team, "getMilliSecondsUntilRepeatable", quest).toString());
        result.addProperty("completion_count", (Number) call(team, "getCompletionCount", quest));
        // 只取 FTB 已登记的开始和完成时间，不把本次读取时间当作任务发生时间。
        long questId = ((Number) call(quest, "getId")).longValue();
        for (String event : new String[]{"Started", "Completed"}) {
            Optional<?> time = (Optional<?>) call(team, "get" + event + "Time", questId);
            time.ifPresent(value -> result.addProperty(event.toLowerCase() + "_at", ((Date) value).toInstant().toString()));
        }
        JsonArray dependencies = new JsonArray(); int hidden = 0;
        // 隐藏前置只报告数量，不通过标题或 URI 泄露另一个尚未向玩家开放的章节。
        try (Stream<?> stream = (Stream<?>) call(quest, "streamDependencies")) {
            for (Object dependency : stream.toList()) {
                if (!visibleIds.contains(id(dependency))) { hidden++; continue; }
                JsonObject row = identity(dependency);
                row.addProperty("kind", call(call(dependency, "getObjectType"), "getId").toString());
                row.addProperty("started", flag(team, "isStarted", dependency));
                row.addProperty("completed", flag(team, "isCompleted", dependency)); dependencies.add(row);
            }
        }
        result.add("dependencies", dependencies); result.addProperty("hidden_dependency_count", hidden);
        JsonArray tasks = new JsonArray();
        boolean previousCompleted = true; int index = 0;
        // 顺序任务只报告前一项是否满足门槛；材料是否匹配、能否提交仍由该原生任务判定。
        for (Object task : (Iterable<?>) call(quest, "getTasks")) {
            JsonObject row = FtbQuestTasks.read(task, team); row.addProperty("sequence_index", index++);
            row.addProperty("sequence_requirement_satisfied", !result.get("sequential_tasks").getAsBoolean() || previousCompleted);
            tasks.add(row); previousCompleted = flag(team, "isCompleted", task);
        }
        result.add("tasks", tasks);
        result.add("rewards", FtbQuestRewards.read(quest, team, player, "", 0));
        return result;
    }
}
