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
    // 计划阶段只检查参数结构；任务是否可见、按钮是否可用，等角色实际执行时按当时的 FTB 状态判断。
    static void validate(Goal goal) {
        if (goal.target() != null) throw new IllegalArgumentException("quest_action uses explicit FTB IDs, not a world target");
        FtbQuestActionRequest.parse(goal.parameters());
    }
    static IntentAction adapt(Goal goal) {
        validate(goal);
        // 适配器只建立一笔动作任务，既不在此发包，也不根据自然语言 outcome 自动选择任务或奖励。
        return new IntentAction.Native(new QuestActionTaskRecord("quest-" + UUID.randomUUID(), FtbQuestActionRequest.parse(goal.parameters())));
    }
    static JsonObject contract() {
        // 读任务书不授权消费；执行时按模型明确选定的按钮发一次请求，回执保留前后事实及真正未知项。
        return JsonParser.parseString("""
                {
                  "summary": "对当前玩家的 FTB Quests 任务书执行一次明确的原生按钮操作，不负责规划或自动推进整章。submit 支持 consumesResources=true 且非 task_screen_only 的物品任务、XP 任务，以及 enableButton=true 的原生自定义任务；不支持自动收集/合成、观察、击杀、流体或能量任务的替代完成。confirm 只支持 ftbquests:checkmark。claim 领取所指定任务的一项可见根奖励：普通、随机、战利品或全表奖励按 FTB 自己的效果执行；未领取的选择奖励必须由调用者指定直接候选 choice_uri，不会自动挑选。全表奖励可能原生发放多项，但本能力不会遍历所有根奖励或自动连续领奖。提交一次可能只推进部分进度，并可能消耗物品或经验；没有 count、费用上限、等待时长或自动重试参数。\\n参数全部放在 goal.parameters；goal.ability 固定为 maicraft:quest_action。goal.target 省略或为 null；goal.preferences 省略或 {}，goal.constraints 省略或 []，goal.children 省略或 []。outcome 只说明意图，不会替代对象编号、选项或变成另一种游戏动作。先用 maicraft://knowledge/ftbquests/index 及其返回的资源取得当前可见 ID。plan 只校验结构，不查询原生按钮是否已解锁；execute 才检查当时的玩家、连接、身体控制权、任务书、队伍和原生按钮条件。\\n以下是完整 plan 参数的结构示例，ID 来自仓库既有 FtbQuestFixture/FtbQuestActionTargetTest，不是当前服务器观察，也不是可跨世界复制的任务编号；运行时必须用本次任务书实际返回的对应 ID 替换。物品示例对应夹具明确关闭 task_screen_only 的场景。{\\"goal\\":{\\"ability\\":\\"maicraft:quest_action\\",\\"outcome\\":\\"向已观察到的消耗型物品任务提交一次\\",\\"target\\":null,\\"parameters\\":{\\"operation\\":\\"submit\\",\\"quest_id\\":\\"FEDCBA9876543210\\",\\"task_id\\":\\"0000000000000003\\"},\\"preferences\\":{},\\"constraints\\":[],\\"children\\":[]}}\\n{\\"goal\\":{\\"ability\\":\\"maicraft:quest_action\\",\\"outcome\\":\\"确认已观察到的手动勾选任务\\",\\"target\\":null,\\"parameters\\":{\\"operation\\":\\"confirm\\",\\"quest_id\\":\\"FEDCBA9876543210\\",\\"task_id\\":\\"0000000000000004\\"},\\"preferences\\":{},\\"constraints\\":[],\\"children\\":[]}}\\n{\\"goal\\":{\\"ability\\":\\"maicraft:quest_action\\",\\"outcome\\":\\"领取已观察到的普通根奖励一次\\",\\"target\\":null,\\"parameters\\":{\\"operation\\":\\"claim\\",\\"quest_id\\":\\"FEDCBA9876543210\\",\\"reward_id\\":\\"000000000000000A\\"},\\"preferences\\":{},\\"constraints\\":[],\\"children\\":[]}}",
                  "accepted_target_kinds": [],
                  "parameters": {
                    "operation": {
                      "type": "string",
                      "description": "goal.parameters.operation：必填字符串，无默认值，精确小写 submit、confirm 或 claim；不裁剪空格。省略、空串、null、false、数字 0 或其他值均无效。一个 goal 只执行一次指定按钮动作。"
                    },
                    "quest_id": {
                      "type": "string",
                      "description": "goal.parameters.quest_id：必填字符串，无默认值。取自当前可见任务书的任务 ID，恰好 16 位十六进制 [0-9A-Fa-f] 且非全零；a-f 会统一大写，不接受 0x 前缀、空格、JSON 数字、null 或 false。它不是章节 ID、子任务 ID，也不是 MaiCraft task_id UUID。"
                    },
                    "task_id": {
                      "type": "string",
                      "description": "goal.parameters.task_id：submit/confirm 必填，claim 时这个字段必须完全省略，写 null 也会被拒绝。格式与 quest_id 相同，必须是该任务自己的有效子任务 ID，不接受数量或注册物品 ID。confirm 仅勾选任务；submit 的实际消费及能否完成由 FTB 判定，没有本参数控制的数量上限。"
                    },
                    "reward_id": {
                      "type": "string",
                      "description": "goal.parameters.reward_id：claim 必填，submit/confirm 时必须完全省略，写 null 也会被拒绝。格式与 quest_id 相同，必须是该任务自己的可见根奖励 ID；不可用奖励表内部候选 ID 代替根奖励 ID。个人/队伍归属、是否已领取及领取许可均读取当前玩家的 FTB 记录。"
                    },
                    "choice_uri": {
                      "type": "string",
                      "description": "goal.parameters.choice_uri：只供 claim 的选择奖励使用，无默认选项。复制该根奖励返回的直接候选 URI，形如 maicraft://knowledge/ftbquests/quest/{大写quest_id}/rewards/{大写reward_id}/{index}~{revision}。index 为 0..999999999 的十进制，无前导零（0 本身允许）；revision 为 16 位小写十六进制。必须与本请求的任务和根奖励一致，不得附带查询、片段或更深的子路径；不接受 null、false、数字或空串。未领取选择奖励在执行期要求此项、版本匹配且索引仍有效；已领取且可访问的选择奖励可省略它，或跳过已通过语法检查的旧引用的版本检查。普通奖励必须省略此项。"
                    }
                  },
                  "accepted_preferences": {},
                  "accepted_hard_constraints": [],
                  "execution_boundary": "整体顺序：结构校验 → 当前连接/身体/队伍/任务书和按钮资格检查 → 读取操作前事实 → 若原生已完成/已领取则直接结算；否则先持久保存父任务和 ftb-quest 唯一预约，再重新准备并核对同一作用范围，最后占用本刻一次原生操作机会并发包。角色等待/执行时释放移动输入，保留现有界面；不强制完成、不打开编辑器、不直接改背包或服务端状态。未安装或未同步时在执行准备阶段失败，动作回执可能是 not_submitted；只读索引的 not_installed 不能当作动作错误码。\\n发送后逐刻观察。若当前 FTB 信号相对提交前不同且 FTB/库存观察继续变化，收尾时点设为当前单调时间加 250 毫秒；达到该时点或发送后 3000 毫秒时结束观察。这不是可配置参数，也不是服务端超时判决；临时抢占保留会话但不重置时钟。FTB 没有请求专属 ACK：success 只表示正常提交到了客户端，或原生目标状态已经满足。即使发包后的观察失效，任务也可能 success=true 并附 outcome_uncertain=true；不能据此声称任务完成或奖励到账。\\n当前成功父任务的 terminal.result 只汇总普通步骤，不能保证含 quest_action 或 outcome_uncertain。task get 默认 last_step.result 保留最后一步的原件；较早任务书步骤请用实际任务 ID 读取 path=/completed_steps/{数组索引}/result/data/quest_action，不能为找回原件重新执行。这是现存终态交付边界，不能用父任务成功摘要代替原生事实。\\n在单步结果 data.quest_action 读取 request、submission_status、submission_attempted、submitted_to_client、ftb_update_observed，以及存在时的 before/after、observed_updates、inventory_changes、observation_problem。submission_attempted 在进入发送调用前置真，不等于已正常发出；ftb_update_observed 只表示共享 FTB 信号变化，可能来自队友。outcome_uncertain=false 也不是请求 ACK 或物品到账证明。库存净数量按 item_id 汇总，组件/变体看完整前后快照；世界掉落、任意服务端命令/脚本效果不在此验证。重复任务重置前观察到的中间变化会保留；没观察到的变化不能推断。\\nexecute 的 request_key 属于外层工具参数（可省略/null；若提供则为 1..128 字符非空字符串），不是 goal.parameters；通信重试必须复用同一个键，丢失回复先用 task 查询既有任务。不要用新的键盲目重发不确定动作。临时抢占保留同一会话；取消、身体对象更换、断线、换队伍或换任务书时停止继续操作并尽力保留已观察事实，不能撤回已发出的请求。重启后重新准备当前对象；已经满足可直接结算，否则已有预约会阻止自动再次发送。同一父步骤改 outcome、编号大小写或整个 choice_uri 都不能获得新的消费身份；显式的另一步/新任务才是新的操作意图，必须在核对原生记录后决定。引用过期且确认尚未提交时只补读对应奖励表；其他真实条件缺失据回执处理，不虚构拒绝原因。"
                }
                """).getAsJsonObject();
    }
}
