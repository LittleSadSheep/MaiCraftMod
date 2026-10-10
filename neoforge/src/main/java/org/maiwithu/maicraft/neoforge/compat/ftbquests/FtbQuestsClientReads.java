// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.ftbquests;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import dev.ftb.mods.ftbquests.client.ClientQuestFile;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestLink;
import dev.ftb.mods.ftbquests.quest.QuestObject;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.loot.RewardTable;
import dev.ftb.mods.ftbquests.quest.reward.ChoiceReward;
import dev.ftb.mods.ftbquests.quest.reward.RandomReward;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.quest.reward.RewardAutoClaim;
import dev.ftb.mods.ftbquests.quest.reward.RewardClaimType;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

import org.maiwithu.maicraft.compat.ftbquests.QuestBook;

/**
 * FTB 任务的读写端：直接调用 FTB 任务的公开接口，把客户端同步到的任务书与自己队伍的进度原样翻出来；只翻译，不判断。
 *
 * <p>FTB 退出服务器时不清空客户端的任务书对象：进了一个没有任务书的服务器，读到的会是上一个服务器的书。
 * 所以在 NeoForge 的客户端登录事件里记下当时的任务书对象（登录事件早于服务器发来新书），
 * 之后还是这个对象就当没同步过来，换成新对象才算这个服务器的书。
 *
 * <p>这个类不直接实现 QuestBook（QuestBook 的内部类型和 FTB 的类同名，会挡住 FTB 的类），联动清单用 read 的方法引用交出去。
 *
 * <p>提交按钮：消耗物品的交物品要求与勾选有；脚本定义的要求按钮开没开只有服务器知道（FTB 不往客户端的公开接口里写），
 * 给"读不出来"；别的做到了自动算，没有按钮。
 */
public final class FtbQuestsClientReads {

    private volatile WeakReference<ClientQuestFile> leftOver = new WeakReference<>(null);

    public FtbQuestsClientReads() {
        NeoForge.EVENT_BUS.addListener(this::loggedIn);
    }

    // 进服的那一刻，客户端手上的任务书还是上一个服务器的（或者没有）：记下来，之后还是它就不算数。
    private void loggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
        leftOver = new WeakReference<>(ClientQuestFile.INSTANCE);
    }

    /** 此刻的任务书：实现 QuestBook.read。 */
    public QuestBook.Snapshot read() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) return QuestBook.Snapshot.unreadable(QuestBook.Access.NOT_IN_WORLD);
        ClientQuestFile file = ClientQuestFile.INSTANCE;
        if (file == null || file == leftOver.get() || !file.isValid()) return QuestBook.Snapshot.unreadable(QuestBook.Access.NOT_SYNCED);
        TeamData team = file.selfTeamData;
        // 队伍数据还是占位（编号全零）或属于旧任务书：等同步。
        if (team == null || Util.NIL_UUID.equals(team.getTeamId()) || team.getFile() != file) {
            return QuestBook.Snapshot.unreadable(QuestBook.Access.NOT_SYNCED);
        }
        if (file.isDisableGui()) return QuestBook.Snapshot.unreadable(QuestBook.Access.BOOK_DISABLED);
        if (team.isLocked()) return QuestBook.Snapshot.unreadable(QuestBook.Access.TEAM_LOCKED);
        UUID player = minecraft.player.getUUID();
        List<QuestBook.Chapter> chapters = new ArrayList<>();
        file.forAllChapters(chapter -> chapters.add(chapter(chapter, team, player)));
        return new QuestBook.Snapshot(QuestBook.Access.READABLE, team.getName(), chapters);
    }

    // 一章：本章的条目，加上链接到别章的条目（链接本身也要对这个队伍可见），按编号去重。
    private static QuestBook.Chapter chapter(Chapter chapter, TeamData team, UUID player) {
        Map<String, QuestBook.Quest> quests = new LinkedHashMap<>();
        for (Quest quest : chapter.getQuests()) {
            quests.putIfAbsent(quest.getCodeString(), quest(quest, team, player));
        }
        for (QuestLink link : chapter.getQuestLinks()) {
            if (!link.isVisible(team)) continue;
            link.getQuest().ifPresent(quest -> quests.putIfAbsent(quest.getCodeString(), quest(quest, team, player)));
        }
        return new QuestBook.Chapter(chapter.getCodeString(), chapter.getTitle().getString(), chapter.getRawSubtitle(),
                chapter.isVisible(team), List.copyOf(quests.values()));
    }

    private static QuestBook.Quest quest(Quest quest, TeamData team, UUID player) {
        boolean hideText = quest.getHideTextUntilComplete().get(quest.getChapter().isHideTextUntilComplete());
        List<QuestBook.Requirement> requirements = new ArrayList<>();
        for (Task task : quest.getTasks()) requirements.add(requirement(task, team));
        List<QuestBook.Reward> rewards = new ArrayList<>();
        for (Reward reward : quest.getRewards()) rewards.add(reward(reward, team, player));
        List<QuestBook.Dependency> dependencies = new ArrayList<>();
        try (Stream<QuestObject> stream = quest.streamDependencies()) {
            stream.forEach(dependency -> dependencies.add(new QuestBook.Dependency(dependency.getCodeString(),
                    dependency.getTitle().getString(), team.isCompleted(dependency))));
        }
        return new QuestBook.Quest(quest.getCodeString(), quest.getTitle().getString(), quest.getSubtitle().getString(),
                quest.isVisible(team), quest.hideDetailsUntilStartable(), hideText, lines(quest.getDescription()),
                team.isCompleted(quest), team.isStarted(quest), team.canStartTasks(quest), team.areDependenciesComplete(quest),
                quest.isOptional(), quest.canBeRepeated(), quest.getRequireSequentialTasks(), requirements, rewards, dependencies);
    }

    private static QuestBook.Requirement requirement(Task task, TeamData team) {
        String type = task.getType().getTypeId().toString();
        List<String> accepted = task instanceof ItemTask item
                ? item.getValidDisplayItems().stream().map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                        .distinct().toList()
                : List.of();
        QuestBook.SubmitButton button = switch (type) {
            case "ftbquests:item" -> task.consumesResources() ? QuestBook.SubmitButton.YES : QuestBook.SubmitButton.NO;
            case "ftbquests:checkmark" -> QuestBook.SubmitButton.YES;
            case "ftbquests:custom" -> QuestBook.SubmitButton.UNKNOWN;
            default -> QuestBook.SubmitButton.NO;
        };
        return new QuestBook.Requirement(task.getCodeString(), type, task.getTitle().getString(), team.getProgress(task),
                task.getMaxProgress(), team.isCompleted(task), task.consumesResources(), button, accepted);
    }

    private static QuestBook.Reward reward(Reward reward, TeamData team, UUID player) {
        RewardClaimType claim = team.getClaimType(player, reward);
        boolean hidden = team.isRewardBlocked(reward) || reward.getAutoClaimType() == RewardAutoClaim.INVISIBLE;
        QuestBook.RewardTable table = null;
        if (reward instanceof RandomReward random && random.getTable() != null) {
            RewardTable ftbTable = random.getTable();
            table = new QuestBook.RewardTable(ftbTable.shouldShowTooltip(), reward instanceof ChoiceReward,
                    ftbTable.getWeightedRewards().stream()
                            .map(row -> new QuestBook.RewardOption(row.getReward().getTitle().getString(), row.getWeight()))
                            .toList());
        }
        return new QuestBook.Reward(reward.getCodeString(), reward.getType().getTypeId().toString(), reward.getTitle().getString(),
                hidden, claim.isClaimed(), claim.canClaim(), reward.isTeamReward(), table);
    }

    private static List<String> lines(List<Component> components) {
        return components.stream().map(Component::getString).toList();
    }
}
