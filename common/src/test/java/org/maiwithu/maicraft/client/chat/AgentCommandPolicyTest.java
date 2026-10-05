// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.chat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * AI 玩家的命令边界：普通玩家命令照常可用，管理员命令默认关闭、由玩家逐条放行，/maicraft 永远不放行。
 * 实测中模型被落脚点卡住后自己搜到聊天能力，连发 /tp 把身体传送到目标格——这里锁住不再发生。
 */
public final class AgentCommandPolicyTest {
    public static void main(String[] args) throws Exception {
        String prior = System.getProperty(AgentCommandPolicy.PROPERTY);
        System.clearProperty(AgentCommandPolicy.PROPERTY);
        Path directory = Files.createTempDirectory("maicraft-agent-commands");
        try {
            defaultsKeepPlayerCommandsAndCloseAdministratorCommands();
            humanPlayerAllowsAndRevokesOneByOne();
            choicesSurviveRestartAndBrokenFileStaysClosed(directory);
            launchPropertySeedsSessionButNeverOpensMaicraft(directory);
        } finally {
            if (prior == null) System.clearProperty(AgentCommandPolicy.PROPERTY);
            else System.setProperty(AgentCommandPolicy.PROPERTY, prior);
            AgentCommandPolicy.reload(null);
        }
        System.out.println("AgentCommandPolicyTest: passed");
    }

    // 默认：聊天和服务器玩家命令（Essentials 的 /tpa、/suicide 等强依赖聊天）照常；管理员命令一律拒绝
    private static void defaultsKeepPlayerCommandsAndCloseAdministratorCommands() {
        AgentCommandPolicy.reload(null);
        for (String text : List.of("大家好", "/tell Steve 我到了", "/msg Alex hi", "/tpa Steve", "/tpaccept",
                "/home base", "/spawn", "/suicide", "/help")) {
            AgentCommandPolicy.check(new ChatMessage(text, 100));
        }
        for (String text : List.of("/tp @s -86 104 27", "/minecraft:give @s iron_ingot", "/gamemode creative",
                "/TIME set day", "/teleport @s 0 64 0", "/tphere Steve", "/fly")) {
            refused(text, "administrator command");
        }
        refused("/maicraft commands allow-all", "reserved for the human player");
        check(AgentCommandPolicy.isAdminCommand("/minecraft:tp"), "namespaced vanilla command is still recognized");
        check(!AgentCommandPolicy.isAdminCommand("tpa"), "teleport requests are an ordinary player command");
    }

    // 玩家用 /maicraft commands 放行或收回：只影响指定的那条，全部放行也不含 /maicraft
    private static void humanPlayerAllowsAndRevokesOneByOne() throws Exception {
        AgentCommandPolicy.reload(null);
        check(AgentCommandPolicy.allow("/TP").equals("tp"), "names are normalized");
        AgentCommandPolicy.check(new ChatMessage("/tp @s 0 64 0", 100));
        refused("/give @s diamond", "administrator command");
        check(AgentCommandPolicy.deny("tp") && !AgentCommandPolicy.deny("tp"), "revoking reports whether it changed");
        refused("/tp @s 0 64 0", "administrator command");
        AgentCommandPolicy.allow(AgentCommandPolicy.ALL);
        AgentCommandPolicy.check(new ChatMessage("/give @s diamond", 100));
        refused("/maicraft dev on", "reserved for the human player");
        try { AgentCommandPolicy.allow("maicraft"); throw new AssertionError("/maicraft was allowed"); }
        catch (IllegalArgumentException expected) { }
        AgentCommandPolicy.clear();
        check(AgentCommandPolicy.allowed().isEmpty(), "clear returns to the default");
        refused("/give @s diamond", "administrator command");
    }

    // 放行名单写进配置文件，重启后保持；文件读坏时按默认关闭，不会因损坏把管理员命令放开
    private static void choicesSurviveRestartAndBrokenFileStaysClosed(Path directory) throws Exception {
        AgentCommandPolicy.reload(directory);
        AgentCommandPolicy.allow("time");
        AgentCommandPolicy.reload(directory);
        check(AgentCommandPolicy.allowed().equals(Set.of("time")), "choices persist across restarts");
        Path file = directory.resolve("config/maicraft-agent.properties");
        Files.writeString(file, "allowedCommands=\\uZZZZ\n");
        AgentCommandPolicy.reload(directory);
        check(AgentCommandPolicy.allowed().isEmpty(), "unreadable file falls back to closed");
    }

    // 启动参数只给本次启动的初始名单，同样不能把 /maicraft 放给 AI
    private static void launchPropertySeedsSessionButNeverOpensMaicraft(Path directory) {
        System.setProperty(AgentCommandPolicy.PROPERTY, "tp, maicraft ,gamemode");
        AgentCommandPolicy.reload(directory);
        check(AgentCommandPolicy.allowed().equals(Set.of("tp", "gamemode")), "launch property seeds the session");
        refused("/maicraft status", "reserved for the human player");
        System.clearProperty(AgentCommandPolicy.PROPERTY);
    }

    private static void refused(String text, String reason) {
        try {
            AgentCommandPolicy.check(new ChatMessage(text, 100));
            throw new AssertionError("command was not refused: " + text);
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains(reason), "refusal explains why: " + expected.getMessage());
        }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
