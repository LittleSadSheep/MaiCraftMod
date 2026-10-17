// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ftbquests;

/**
 * FTB 任务书的发包接缝：把一次提交、勾选、领取发给服务器。只用 Java 类型；
 * 读写端直接发 FTB 自己的网络包，不判断该不该发——该不该发由 quest 能力按任务书的样子决定，
 * 本刻的交互机会由联动入口在发包前占用。FTB 对这些包没有专门的回应，发出去就算发出去了。
 */
public interface QuestBookActions {

    /**
     * 发一次"提交要求"的包。提交物品与手动勾选走的是同一个包，FTB 在服务器上按要求的类型各自结算。
     *
     * @param requirementId 要求的编号（客户端编号的原始 long 形态）
     * @return 包发出去了为真；连接不在时发不出去，为假
     */
    boolean submitTask(long requirementId);

    /** 发一次"领取奖励"的包；发不出去为假。 */
    boolean claimReward(long rewardId);

    /**
     * 发一次"领取选择奖励"的包。
     *
     * @param choiceIndex 候选在奖池里的序号，从 0 起：与 FTB 自己的选择界面发给服务器的序号同源
     */
    boolean claimChoiceReward(long rewardId, int choiceIndex);
}
