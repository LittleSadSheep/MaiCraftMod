// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ftbquests;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import static org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestApi.*;

/** 只解析普通玩家当前能执行的原生按钮；观察、击杀等自动判定任务不能通过提交消息跳过真实玩法。 */
record FtbQuestActionTarget(FtbQuestActionRequest request, Object quest, Object subject, String packet, int choiceIndex,
                            boolean satisfied) {
    static FtbQuestActionTarget resolve(Object file, Object team, UUID player, FtbQuestActionRequest request) {
        if (flag(file, "isDisableGui") || flag(team, "isLocked")) throw new IllegalStateException("FTB quest book is disabled or locked");
        Object quest = call(file, "getQuest", FtbQuestActionRequest.number(request.questId()));
        if (quest == null || !visible(file, team, quest)) throw new IllegalStateException("FTB quest is not currently visible");
        if (flag(quest, "hideDetailsUntilStartable") && !flag(team, "canStartTasks", quest)) throw new IllegalStateException("FTB quest details are not unlocked");
        boolean claim = request.operation().equals("claim"); Object subject = null;
        for (Object value : (Iterable<?>) call(quest, claim ? "getRewards" : "getTasks"))
            if (id(value).equals(request.subjectId()) && flag(value, "isValid")) { subject = value; break; }
        if (subject == null) throw new IllegalStateException("The requested object does not belong to this FTB quest");
        String type = call(call(subject, "getType"), "getTypeId").toString();
        if (!claim) {
            if (request.operation().equals("confirm")) {
                if (!type.equals("ftbquests:checkmark")) throw new IllegalArgumentException("confirm only applies to native checkmark tasks");
            } else switch (type) {
                case "ftbquests:item" -> {
                    if (!flag(subject, "consumesResources") || flag(subject, "isTaskScreenOnly"))
                        throw new IllegalStateException("This item task has no enabled book submission button; use its native collection/crafting or task-screen mechanic");
                }
                case "ftbquests:xp" -> { /* 原生经验按钮允许提交现有经验，不预测经验是否足够完成任务。 */ }
                case "ftbquests:custom" -> {
                    if (!Boolean.TRUE.equals(FtbQuestData.field(subject, "enableButton"))) throw new IllegalStateException("Custom task has no enabled submission button");
                }
                default -> throw new IllegalArgumentException("This task requires its actual game mechanic, not manual submission");
            }
            boolean done = flag(team, "isCompleted", subject);
            if (!done && !flag(team, "canStartTasks", quest)) throw new IllegalStateException("FTB does not allow starting this quest yet");
            return new FtbQuestActionTarget(request, quest, subject, "SubmitTaskMessage", -1, done);
        }
        if (!FtbQuestRewards.visible(quest, team).contains(subject)) throw new IllegalStateException("FTB reward is blocked or invisible");
        Object claimType = call(team, "getClaimType", player, subject); boolean done = flag(claimType, "isClaimed");
        if (!done && !flag(claimType, "canClaim")) throw new IllegalStateException("FTB has not made this reward claimable");
        int index = -1; String packet = "ClaimRewardMessage";
        if (type.equals("ftbquests:choice")) {
            // 根奖励已经领取时不再要求旧选择引用仍有效，也不据此声称当时领到了本次指定的选项。
            if (done) return new FtbQuestActionTarget(request, quest, subject, "ClaimChoiceRewardMessage", -1, true);
            if (request.choiceUri() == null) throw new IllegalArgumentException("A choice reward requires an explicit choice_uri");
            String step = request.choiceUri().substring(request.choiceUri().lastIndexOf('/') + 1);
            Object table = call(subject, "getTable"); String[] parts = step.split("~");
            if (table == null || !FtbRewardTables.revision(table).equals(parts[1])) throw new IllegalStateException("Choice reference expired; read this reward table again");
            index = Integer.parseInt(parts[0]);
            if (index >= FtbRewardTables.rows(table).size()) throw new IllegalStateException("Choice no longer exists");
            packet = "ClaimChoiceRewardMessage";
        } else if (request.choiceUri() != null) throw new IllegalArgumentException("Only a choice reward accepts choice_uri");
        return new FtbQuestActionTarget(request, quest, subject, packet, index, done);
    }
    private static boolean visible(Object file, Object team, Object quest) {
        if (!flag(quest, "isValid") || !flag(quest, "isVisible", team)) return false;
        if (flag(call(quest, "getChapter"), "isVisible", team)) return true;
        // 隐藏章节的任务也可能从可见章节的链接进入，与只读目录保持同一玩家范围。
        List<Object> chapters = new ArrayList<>(); call(file, "forAllChapters", (Consumer<Object>) chapters::add);
        for (Object chapter : chapters) if (flag(chapter, "isVisible", team))
            for (Object link : (Iterable<?>) call(chapter, "getQuestLinks"))
                if (flag(link, "isVisible", team) && ((Optional<?>) call(link, "getQuest")).orElse(null) == quest) return true;
        return false;
    }
}
