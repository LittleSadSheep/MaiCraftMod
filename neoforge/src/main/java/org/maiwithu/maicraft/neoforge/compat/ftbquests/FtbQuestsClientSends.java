// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.ftbquests;

import dev.ftb.mods.ftbquests.net.ClaimChoiceRewardMessage;
import dev.ftb.mods.ftbquests.net.ClaimRewardMessage;
import dev.ftb.mods.ftbquests.net.SubmitTaskMessage;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

import org.maiwithu.maicraft.compat.ftbquests.QuestBookActions;

/**
 * FTB 任务书的发包读写端：把一次提交、勾选、领取翻成 FTB 自己的网络包发出去；只翻译，不判断。
 * 包和玩家在书里点按钮发出的是同一批（提交与勾选同为 SubmitTaskMessage，FTB 在服务器上按要求的类型结算）。
 * 直接引用 FTB 的类，所以只在联动清单确认装了、版本在范围内之后才会被加载。
 */
public final class FtbQuestsClientSends implements QuestBookActions {

    // 连接不在（换服、退世界的间隙）就发不出去：如实答假，调用方按"没发出去"收场。
    @Override public boolean submitTask(long requirementId) {
        if (!connected()) return false;
        PacketDistributor.sendToServer(new SubmitTaskMessage(requirementId));
        return true;
    }

    @Override public boolean claimReward(long rewardId) {
        if (!connected()) return false;
        // 要通知：和玩家自己点"领取"一样，服务器照常给奖励入包的提示。
        PacketDistributor.sendToServer(new ClaimRewardMessage(rewardId, true));
        return true;
    }

    @Override public boolean claimChoiceReward(long rewardId, int choiceIndex) {
        if (!connected()) return false;
        PacketDistributor.sendToServer(new ClaimChoiceRewardMessage(rewardId, choiceIndex));
        return true;
    }

    private static boolean connected() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && minecraft.getConnection() != null;
    }
}
