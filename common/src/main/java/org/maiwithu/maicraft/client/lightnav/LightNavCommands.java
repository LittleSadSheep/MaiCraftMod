// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.lightnav;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Shared client-side command tree for Fabric and NeoForge. */
public final class LightNavCommands {
    private LightNavCommands() {}

    public static <S> LiteralArgumentBuilder<S> tree() {
        return LiteralArgumentBuilder.<S>literal("lightnav")
                .executes(context -> showStatus())
                .then(LiteralArgumentBuilder.<S>literal("observe")
                        .then(RequiredArgumentBuilder.<S, String>argument(
                                "instruction", StringArgumentType.greedyString())
                                .executes(context -> observe(StringArgumentType.getString(context, "instruction")))))
                .then(LiteralArgumentBuilder.<S>literal("status").executes(context -> showStatus()))
                .then(LiteralArgumentBuilder.<S>literal("stop").executes(context -> {
                    LightNavClient.stop();
                    message("实时观察已停止");
                    return 1;
                }));
    }

    private static int observe(String instruction) {
        try {
            LightNavClient.observe(instruction);
            message("已启动实时画面观察（仅显示预测，不移动玩家）。关闭聊天后开始。"
                    + " /maicraft lightnav stop 可停止");
            return 1;
        } catch (RuntimeException failure) {
            message("无法开始观察: " + failure.getMessage());
            return 0;
        }
    }

    private static int showStatus() {
        message(LightNavClient.status());
        return 1;
    }

    static void message(String text) {
        Minecraft.getInstance().gui.getChat().addMessage(
                Component.literal("[LightNav] ").withStyle(ChatFormatting.AQUA)
                        .append(Component.literal(text).withStyle(ChatFormatting.GRAY)));
    }
}
