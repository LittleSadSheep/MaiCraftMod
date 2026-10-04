// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * /maicraft cleartask：人工临时接管角色后清空正在执行的任务。
 * 被清任务按取消结算并回传等待中的调用，松开手动控制后 AI 不会继续执行已失位的旧任务。
 */
public final class MaiCraftClearTaskCommand {
    private MaiCraftClearTaskCommand() {}

    /** 两个加载器共用的命令节点：/maicraft cleartask。 */
    public static <S> LiteralArgumentBuilder<S> node() {
        return LiteralArgumentBuilder.<S>literal("cleartask").executes(context -> execute());
    }

    private static int execute() {
        Minecraft minecraft = Minecraft.getInstance();
        ClearOutcome outcome = clearOnClient(minecraft.player);
        feedback(minecraft, outcome.detail(), outcome.tone());
        return outcome.cleared() ? 1 : 0;
    }

    /** 清理结论：cleared 表示是否真的结束了任务；tone 与 detail 直接用于聊天反馈。 */
    record ClearOutcome(boolean cleared, ChatFormatting tone, String detail) {}

    /**
     * 结束当前身体上全部占用槽位的任务（含同步动作与当前任务）。
     * 只做任务与调度层动作，聊天反馈由命令入口负责；测试直接驱动本方法。
     */
    static ClearOutcome clearOnClient(LocalPlayer player) {
        if (player == null) {
            return new ClearOutcome(false, ChatFormatting.YELLOW, "当前不在世界中，没有可清理的任务。");
        }
        List<TaskRecord> active = CompanionTickDispatcher.list();
        if (active.isEmpty()) {
            return new ClearOutcome(false, ChatFormatting.GRAY, "当前没有正在执行的任务。");
        }
        CompanionTickDispatcher.cancelFor(player);
        List<TaskRecord> remaining = CompanionTickDispatcher.list();
        if (!remaining.isEmpty()) {
            // cancelFor 只作用于已绑定的当前身体；刚重生或换世界后调度可能尚未重新绑定。
            return new ClearOutcome(false, ChatFormatting.RED, "清理未生效：任务调度尚未绑定当前身体，请稍后再试。");
        }
        String summary = active.stream()
                .map(record -> record.publicId() + " " + record.describe())
                .collect(Collectors.joining("、"));
        return new ClearOutcome(true, ChatFormatting.GREEN, "已清理 " + active.size()
                + " 个任务（" + summary + "）；等待中的调用会收到取消回执，松开控制后不会继续执行。");
    }

    private static void feedback(Minecraft minecraft, String text, ChatFormatting color) {
        minecraft.gui.getChat().addMessage(Component.literal("[MaiCraft] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(text).withStyle(color)));
    }
}
