// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ftbquests;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongPredicate;
import java.util.function.Supplier;

import org.maiwithu.maicraft.ability.quest.spi.QuestBookOperations;
import org.maiwithu.maicraft.ability.quest.spi.QuestBookStatus;
import org.maiwithu.maicraft.ability.quest.spi.QuestView;
import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.ftbquests.QuestBook.Access;
import org.maiwithu.maicraft.compat.ftbquests.QuestBook.Quest;
import org.maiwithu.maicraft.compat.ftbquests.QuestBook.Requirement;
import org.maiwithu.maicraft.compat.ftbquests.QuestBook.Reward;
import org.maiwithu.maicraft.compat.ftbquests.QuestBook.Snapshot;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * FTB 任务书操作的联动实现：读用任务书读取接缝（与查资料共用一份），发包用发包接缝，
 * 合成 quest 能力要的 QuestBookOperations。每一下读写都经联动入口：模组停用、接口对不上时
 * 读如实说用不了、条目读不到、发送答"没发出去"，不冒充成功。
 *
 * <p>条目只给玩家在书里看得见的：不可见的章节与条目不给，编号对不上就按"没有"回答。
 * 奖池（随机抽、战利品这类）的子奖励在客户端读不到单独的编号，奖池只留在根奖励里，
 * 候选编号只给"选一样"的选择奖励——按界面的顺序从 1 起编，领的时候换回序号发出去。
 */
public final class FtbQuestBookOperations implements QuestBookOperations {

    private final CompatModule module;
    private final QuestBook book;
    private final QuestBookActions actions;
    private final Supplier<PlayerContext> context;

    public FtbQuestBookOperations(CompatModule module, QuestBook book, QuestBookActions actions,
            Supplier<PlayerContext> context) {
        this.module = Objects.requireNonNull(module, "module");
        this.book = Objects.requireNonNull(book, "book");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override public QuestBookStatus status() {
        Snapshot snapshot = module.call("读任务书", book::read);
        if (snapshot.access() == Access.READABLE) return QuestBookStatus.ready();
        return QuestBookStatus.unusable("任务书用不了：" + QuestBookPages.reason(snapshot.access()));
    }

    @Override public Optional<QuestView> quest(String questId) {
        Snapshot snapshot = module.call("读任务书", book::read);
        if (snapshot.access() != Access.READABLE) return Optional.empty();
        for (var chapter : QuestBookPages.visibleChapters(snapshot)) {
            for (Quest quest : chapter.quests()) {
                if (quest.id().equalsIgnoreCase(questId)) return Optional.of(view(quest));
            }
        }
        return Optional.empty();
    }

    @Override public boolean submit(String questId, String requirementId) {
        return send(requirementId, actions::submitTask);
    }

    @Override public boolean confirm(String questId, String requirementId) {
        // 提交物品与手动勾选在 FTB 是同一个包：服务器按要求的类型各自结算，这里照样发。
        return send(requirementId, actions::submitTask);
    }

    @Override public boolean claim(String questId, String rewardId, String choice) {
        if (choice == null) return send(rewardId, actions::claimReward);
        // 候选编号是给界面顺序起的序号（从 1 起）：换回 FTB 要的序号发出去。
        int choiceIndex;
        try {
            choiceIndex = Integer.parseInt(choice.trim()) - 1;
        } catch (NumberFormatException notAnIndex) {
            return false;
        }
        return send(rewardId, id -> actions.claimChoiceReward(id, choiceIndex));
    }

    /** 发一次包：编号先换成 FTB 的 long（换不成的发不出去，也不占交互机会），再占本刻唯一的一次交互机会交读写端。 */
    private boolean send(String objectId, LongPredicate sending) {
        long id;
        try {
            id = Long.parseUnsignedLong(objectId.trim(), 16);
        } catch (NumberFormatException notAnId) {
            return false;
        }
        PlayerContext current = context.get();
        if (current == null || !current.tryClaimInteraction()) return false;
        return module.call("发任务书请求", () -> sending.test(id));
    }

    /** 条目现在的样子：只留看得见的要求与奖励，选择奖励展开候选。 */
    private static QuestView view(Quest quest) {
        List<QuestView.Requirement> requirements = new ArrayList<>();
        for (Requirement requirement : quest.requirements()) {
            requirements.add(new QuestView.Requirement(requirement.id(), shortKind(requirement.type()),
                    requirement.completed(), submittable(requirement), isManualCheck(requirement),
                    requirement.acceptedItems(), remaining(requirement), howCompleted(requirement)));
        }
        List<QuestView.Reward> rewards = new ArrayList<>();
        List<String> nested = new ArrayList<>();
        for (Reward reward : quest.rewards()) {
            if (reward.hidden()) continue;
            List<QuestView.Choice> choices = new ArrayList<>();
            if (reward.table() != null && reward.table().choice()) {
                // 候选按界面的顺序从 1 起编：LLM 回答编号，领的时候换回序号。
                for (int i = 0; i < reward.table().options().size(); i++) {
                    choices.add(new QuestView.Choice(String.valueOf(i + 1),
                            reward.table().options().get(i).title()));
                }
            }
            rewards.add(new QuestView.Reward(reward.id(), reward.claimed(), reward.canClaim(),
                    List.copyOf(choices)));
        }
        return new QuestView(quest.id().toUpperCase(Locale.ROOT), quest.title(),
                quest.canStartTasks(), lockedReason(quest), requirements, rewards, nested);
    }

    /** 还差几件（或几次）：进度与要多少都是队伍数据里同步来的。 */
    private static long remaining(Requirement requirement) {
        return Math.max(0, requirement.required() - requirement.progress());
    }

    /** 有提交按钮的才交得了：消耗物品的物品要求与勾选有；脚本定义的要求按钮开没开客户端读不到，照样让服务器结算。 */
    private static boolean submittable(Requirement requirement) {
        return requirement.submitButton() == QuestBook.SubmitButton.YES
                || requirement.type().equals("ftbquests:custom");
    }

    /** 手动勾选的要求就是勾选型。 */
    private static boolean isManualCheck(Requirement requirement) {
        return requirement.type().equals("ftbquests:checkmark");
    }

    private static String lockedReason(Quest quest) {
        if (quest.canStartTasks()) return "";
        return quest.dependenciesComplete() ? "被屏蔽或还没到开始的条件" : "前置还没都完成";
    }

    /** 原生类型去掉命名空间，如 item、checkmark；给判断与结果看的短写法。 */
    private static String shortKind(String type) {
        int slash = type.indexOf(':');
        return slash < 0 ? type : type.substring(slash + 1);
    }

    /** 这条要求怎么完成的一句话：拒绝"不是交物品的"时写清出路。 */
    private static String howCompleted(Requirement requirement) {
        return switch (requirement.type()) {
            case "ftbquests:item" -> requirement.consumesItems()
                    ? "把要交的物品交上去" : "身上有这些物品就算（不收走）";
            case "ftbquests:xp" -> "交够经验等级";
            case "ftbquests:dimension" -> "到 " + requirement.title() + " 这个维度";
            case "ftbquests:biome" -> "到 " + requirement.title() + " 这个群系";
            case "ftbquests:structure" -> "走到 " + requirement.title();
            case "ftbquests:kill" -> "击杀指定的目标";
            case "ftbquests:location" -> "走到那块区域里";
            case "ftbquests:stat" -> "把这项统计做上去";
            case "ftbquests:checkmark" -> "在书里勾一下";
            case "ftbquests:advancement" -> "拿到那个进度";
            case "ftbquests:observation" -> "看着指定的东西";
            case "ftbquests:gamestage" -> "拿到那个阶段";
            case "ftbquests:fluid" -> "交流体";
            case "ftbquests:forge_energy" -> "交能量";
            case "ftbquests:custom" -> "由服务器按作者写的脚本判定";
            default -> "按类型 " + requirement.type() + " 的规矩完成";
        };
    }
}
