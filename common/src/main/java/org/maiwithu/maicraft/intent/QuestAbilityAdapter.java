// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestActionRequest;
import org.maiwithu.maicraft.core.task.quests.QuestActionTaskRecord;

/** 把模型选定的任务或奖励交给单次原生动作，不替模型选择奖励、不把任务进度直接写为完成。 */
final class QuestAbilityAdapter {
    static final String ABILITY = "maicraft:quest_action";
    private QuestAbilityAdapter() {}
    static void validate(Goal goal) {
        if (goal.target() != null) throw new IllegalArgumentException("quest_action uses explicit FTB IDs, not a world target");
        FtbQuestActionRequest.parse(goal.parameters());
    }
    static IntentAction adapt(Goal goal) {
        validate(goal);
        return new IntentAction.Native(new QuestActionTaskRecord("quest-" + UUID.randomUUID(), FtbQuestActionRequest.parse(goal.parameters())));
    }
    static JsonObject contract() {
        // 读任务书不授权消费；执行时按模型明确选定的按钮发一次请求，回执保留前后事实及真正未知项。
        return JsonParser.parseString("""
                {"summary":"Perform one native FTB Quests action selected by the caller. submit handles consuming item tasks, XP tasks and explicitly enabled custom-task buttons; confirm handles checkmarks only. Collection, crafting, observation, kill and task-screen mechanics must happen through their actual game behavior. claim uses the selected reward; choice rewards require an exact direct choice_uri returned by the reward resource. FTB decides consumption, completion and awards. Success means the native request was sent once or the target was already satisfied, not that the quest completed or items arrived. Receipts separately retain FTB progress/claim records, inventory changes, player state and intermediate observed effects. No automatic resubmission after uncertain delivery or restart; reuse execute request_key for transport retries.",
                 "accepted_target_kinds":[],
                 "parameters":{
                   "operation":{"type":"string","description":"Required: submit, confirm or claim. One operation per goal; never automatically claim all rewards."},
                   "quest_id":{"type":"string","description":"Required exact 16-digit hexadecimal quest ID from the visible task book."},
                   "task_id":{"type":"string","description":"Required for submit/confirm: exact task ID belonging to quest_id. Submission may consume the remaining native item/XP requirement."},
                   "reward_id":{"type":"string","description":"Required for claim: exact root reward ID belonging to quest_id. Do not use a nested reward-table entry as a root reward."},
                   "choice_uri":{"type":"string","description":"Required only for a choice reward: exact URI for its direct option returned by the reward table. The LLM chooses; stale options require refreshing only that reward table."}
                 },"accepted_preferences":{},"accepted_hard_constraints":[],
                 "execution_boundary":"Current controlled player, synchronized FTB book/team and native button permissions. No force-complete, editor commands, direct inventory writes or server-state overrides. Read maicraft://knowledge/ftbquests/index for requirements, rewards and live state."}
                """).getAsJsonObject();
    }
}
