// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ftbquests;

import java.util.List;
import java.util.Objects;

/**
 * FTB 任务书的模组读写接缝：读此刻客户端同步到的任务书与自己队伍的进度。只用 Java 类型描述；
 * 读写端只把 FTB 的东西原样翻出来（连同作者设的"隐藏"开关与 FTB 自己的可见判断），
 * 玩家在书里看得到什么、怎么写给 LLM 看，由联动入口决定。查资料与 quest 能力共用这一份读取。只在客户端线程上调用。
 */
public interface QuestBook {

    /** 此刻的任务书；读不了时写明为什么。 */
    Snapshot read();

    /** 读不读得了。 */
    enum Access {
        /** 读得了。 */
        READABLE,
        /** 角色不在世界里。 */
        NOT_IN_WORLD,
        /** 任务书还没从服务器同步过来，或者这个服务器没有任务书（退服后客户端留着的上一个服务器的书不算）。 */
        NOT_SYNCED,
        /** 服务器禁用了任务书界面：玩家自己也打不开。 */
        BOOK_DISABLED,
        /** 这个队伍的任务书被锁了：玩家自己也打不开。 */
        TEAM_LOCKED
    }

    /**
     * 一次读到的任务书。
     *
     * @param access   读不读得了
     * @param teamName 自己队伍的名字；读不了时为空串
     * @param chapters 全部章节，连同 FTB 判断的对这个队伍可不可见
     */
    record Snapshot(Access access, String teamName, List<Chapter> chapters) {
        public Snapshot {
            Objects.requireNonNull(access, "access");
            teamName = teamName == null ? "" : teamName;
            chapters = List.copyOf(chapters);
        }

        /** 读不了的那种。 */
        public static Snapshot unreadable(Access access) {
            return new Snapshot(access, "", List.of());
        }
    }

    /**
     * 一章。
     *
     * @param id       FTB 的 16 位十六进制编号
     * @param visible  FTB 判断这一章对这个队伍可见
     * @param subtitle 副标题，每行一段
     * @param quests   这一章的条目，连同链接到别章的条目，已去重
     */
    record Chapter(String id, String title, List<String> subtitle, boolean visible, List<Quest> quests) {
        public Chapter {
            subtitle = List.copyOf(subtitle);
            quests = List.copyOf(quests);
        }
    }

    /**
     * 任务书里的一个条目。
     *
     * @param visible                   FTB 判断这个条目对这个队伍可见
     * @param hideDetailsUntilStartable 作者设了"能开始之前不显示详情"
     * @param hideTextUntilComplete     作者设了"完成之前不显示正文"（章节的默认值已算进来）
     * @param description               正文，每行一段
     * @param canStartTasks             前置都满足、现在能开始做
     */
    record Quest(String id, String title, String subtitle, boolean visible, boolean hideDetailsUntilStartable,
            boolean hideTextUntilComplete, List<String> description, boolean completed, boolean started, boolean canStartTasks,
            boolean dependenciesComplete, boolean optional, boolean repeatable, boolean sequentialTasks,
            List<Requirement> requirements, List<Reward> rewards, List<Dependency> dependencies) {
        public Quest {
            description = List.copyOf(description);
            requirements = List.copyOf(requirements);
            rewards = List.copyOf(rewards);
            dependencies = List.copyOf(dependencies);
        }
    }

    /**
     * 条目的一项要求（FTB 叫 task）。
     *
     * @param type          FTB 的要求类型，例如 ftbquests:item、ftbquests:kill
     * @param title         FTB 给这项要求的标题，就是书里显示的那行
     * @param consumesItems 交的时候会把东西收走
     * @param submitButton  书里有没有提交按钮
     * @param acceptedItems 交物品的要求能接受的物品（全部候选）；别的类型为空
     */
    record Requirement(String id, String type, String title, long progress, long required, boolean completed,
            boolean consumesItems, SubmitButton submitButton, List<String> acceptedItems) {
        public Requirement {
            Objects.requireNonNull(submitButton, "submitButton");
            acceptedItems = List.copyOf(acceptedItems);
        }
    }

    /** 书里这项要求有没有提交按钮。 */
    enum SubmitButton {
        /** 有：消耗物品的交物品要求、勾选。 */
        YES,
        /** 没有：做到了自动算。 */
        NO,
        /** 读不出来：脚本定义的要求，按钮开没开只有服务器知道。 */
        UNKNOWN
    }

    /**
     * 条目的一项奖励。
     *
     * @param hidden    被屏蔽了，或作者设成"不可见、自动领取"：书里不显示
     * @param claimed   这个玩家领过了
     * @param canClaim  现在能领
     * @param teamReward 整个队伍共用一份
     * @param table     随机、选择、战利品这类奖池；不是奖池时为 null
     */
    record Reward(String id, String type, String title, boolean hidden, boolean claimed, boolean canClaim,
            boolean teamReward, RewardTable table) {}

    /**
     * 奖池：随机抽、从几样里选一样、战利品、全给。
     *
     * @param showsContents 作者设了显示奖池内容
     * @param choice        是"从几样里选一样"
     * @param options       候选与权重
     */
    record RewardTable(boolean showsContents, boolean choice, List<RewardOption> options) {
        public RewardTable {
            options = List.copyOf(options);
        }
    }

    /** 奖池里的一样：标题与权重。 */
    record RewardOption(String title, float weight) {}

    /** 条目的一个前置：另一个条目或一章。 */
    record Dependency(String id, String title, boolean completed) {}
}
