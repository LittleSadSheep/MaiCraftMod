// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.io.IOException;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.maiwithu.maicraft.client.chat.AgentCommandPolicy;

/**
 * /maicraft commands：玩家在游戏里决定 AI 能用哪些管理员命令。
 * 普通玩家命令（/tell、/tpa、/home、/suicide 等）AI 本来就能用；管理员命令（/tp、/give 等）默认关闭，
 * 在这里逐条放行或收回。命令只有玩家自己打才有效——AI 发出的 /maicraft 一律被拒绝。
 */
public final class MaiCraftAgentCommandsCommand {
    private MaiCraftAgentCommandsCommand() {}

    /** 查看或修改的结论：changed 表示名单是否变了；tone 与 detail 直接用于聊天反馈。 */
    record Outcome(boolean changed, ChatFormatting tone, String detail) {}

    /**
     * 两个加载器共用的命令节点：
     * /maicraft commands 查看；allow &lt;命令名&gt; 放行一条；allow-all 全部放行；deny &lt;命令名&gt; 收回；clear 全部收回。
     */
    public static <S> LiteralArgumentBuilder<S> node() {
        return LiteralArgumentBuilder.<S>literal("commands").executes(context -> respond(MaiCraftAgentCommandsCommand::status))
                .then(LiteralArgumentBuilder.<S>literal("allow")
                        .then(RequiredArgumentBuilder.<S, String>argument("name", StringArgumentType.word())
                                .executes(context -> respond(() -> allow(StringArgumentType.getString(context, "name"))))))
                .then(LiteralArgumentBuilder.<S>literal("allow-all")
                        .executes(context -> respond(() -> allow(AgentCommandPolicy.ALL))))
                .then(LiteralArgumentBuilder.<S>literal("deny")
                        .then(RequiredArgumentBuilder.<S, String>argument("name", StringArgumentType.word())
                                .executes(context -> respond(() -> deny(StringArgumentType.getString(context, "name"))))))
                .then(LiteralArgumentBuilder.<S>literal("clear")
                        .executes(context -> respond(MaiCraftAgentCommandsCommand::clear)));
    }

    /** 当前状态：普通玩家命令照常可用，列出已放行的管理员命令。 */
    static Outcome status() {
        return new Outcome(false, ChatFormatting.GRAY, describe(AgentCommandPolicy.allowed()));
    }

    /** 放行一条管理员命令（或 * 全部）；/maicraft 与空名拒绝，写盘失败时本次运行仍生效。 */
    static Outcome allow(String raw) {
        try {
            String name = AgentCommandPolicy.allow(raw);
            String target = AgentCommandPolicy.ALL.equals(name) ? "全部管理员命令（/maicraft 除外）" : "/" + name;
            return new Outcome(true, ChatFormatting.GREEN, "已允许 AI 使用 " + target + "。" + describe(AgentCommandPolicy.allowed()));
        } catch (IllegalArgumentException refused) {
            return new Outcome(false, ChatFormatting.RED, "不能放行：/maicraft 只能由你自己使用，命令名也不能为空。");
        } catch (IOException unsaved) {
            return new Outcome(true, ChatFormatting.YELLOW, "本次运行已放行，但保存配置失败：" + unsaved.getMessage());
        }
    }

    /** 收回一条（或 * 取消"全部放行"）；名单里没有时如实说明。 */
    static Outcome deny(String raw) {
        try {
            boolean removed = AgentCommandPolicy.deny(raw);
            String detail = removed ? "已收回。" : "名单里本来就没有这条。";
            return new Outcome(removed, removed ? ChatFormatting.GREEN : ChatFormatting.GRAY,
                    detail + describe(AgentCommandPolicy.allowed()));
        } catch (IOException unsaved) {
            return new Outcome(true, ChatFormatting.YELLOW, "本次运行已收回，但保存配置失败：" + unsaved.getMessage());
        }
    }

    /** 全部收回，回到默认：管理员命令一律不能发。 */
    static Outcome clear() {
        try {
            AgentCommandPolicy.clear();
            return new Outcome(true, ChatFormatting.GREEN, "已全部收回。" + describe(Set.of()));
        } catch (IOException unsaved) {
            return new Outcome(true, ChatFormatting.YELLOW, "本次运行已全部收回，但保存配置失败：" + unsaved.getMessage());
        }
    }

    private static String describe(Set<String> allowed) {
        String granted = allowed.isEmpty() ? "无"
                : allowed.contains(AgentCommandPolicy.ALL) ? "全部（/maicraft 除外）"
                : "/" + String.join("、/", allowed);
        return "AI 可用普通玩家命令（如 /tell、/tpa、/home）；管理员命令默认关闭，已放行：" + granted + "。";
    }

    private static int respond(Supplier<Outcome> action) {
        Outcome outcome = action.get();
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.gui.getChat().addMessage(Component.literal("[MaiCraft] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(outcome.detail()).withStyle(outcome.tone())));
        return outcome.changed() ? 1 : 0;
    }
}
