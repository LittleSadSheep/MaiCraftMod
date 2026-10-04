// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.io.IOException;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;

/**
 * /maicraft port：把内嵌 MCP 传输搬到新端口。只重绑定传输层，正在执行的语义任务继续；
 * 旧连接全部断开，AI 客户端需改连聊天栏回报的实际地址并重新初始化。
 */
public final class MaiCraftPortCommand {
    private MaiCraftPortCommand() {}

    /** 两个加载器共用的命令节点：/maicraft port <0-65535>。 */
    public static <S> LiteralArgumentBuilder<S> node() {
        return LiteralArgumentBuilder.<S>literal("port")
                .then(RequiredArgumentBuilder.<S, Integer>argument("port", IntegerArgumentType.integer(0, 65_535))
                        .executes(context -> rebind(IntegerArgumentType.getInteger(context, "port"))));
    }

    private static int rebind(int configuredPort) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            int actual = ClientRuntime.restartMcp(configuredPort);
            String moved = actual == configuredPort ? "" : "（配置端口 " + configuredPort + " 被占，已让行）";
            feedback(minecraft, "MCP 已重启于 127.0.0.1:" + actual + "/mcp" + moved
                    + "；旧连接已断开，AI 客户端请改连此地址重新初始化。", ChatFormatting.GREEN);
        } catch (IOException | RuntimeException failure) {
            String reason = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            feedback(minecraft, "MCP 重启失败：" + reason
                    + "。当前无监听，换一个端口重试 /maicraft port；详情见 /maicraft status。", ChatFormatting.RED);
        }
        return 1;
    }

    private static void feedback(Minecraft minecraft, String text, ChatFormatting color) {
        minecraft.gui.getChat().addMessage(Component.literal("[MaiCraft] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(text).withStyle(color)));
    }
}
