// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.chat;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.client.Minecraft;

/**
 * AI 玩家能用聊天框发哪些斜杠命令。AI 应该像生存玩家一样玩：普通玩家命令（/msg、/home、/help 等）
 * 照常交给原生命令处理，管理员命令（/tp、/give、/gamemode、/time 等）默认对 AI 关闭，
 * 只有玩家在游戏里用 /maicraft commands 放行的才可用。
 * 放行名单保存在 config/maicraft-agent.properties；启动参数 maicraft.chat.allowed_commands
 * 只决定本次启动的初始名单，不写回文件。/maicraft 永远不放行，AI 不能改自己的权限或 Mod 设置。
 */
public final class AgentCommandPolicy {
    /** 启动参数：逗号分隔的管理员命令名（不带 /），* 表示全部；只作用于本次启动。 */
    public static final String PROPERTY = "maicraft.chat.allowed_commands";
    /** 放行全部管理员命令（/maicraft 仍除外）。 */
    public static final String ALL = "*";
    private static final String FILE = "config/maicraft-agent.properties";
    private static final String KEY = "allowedCommands";
    private static final String RESERVED = "maicraft";
    /**
     * 连远程服务器时拿不到服务器命令树，用这份清单兜底判定管理员命令：
     * 原版需要 2 级及以上权限的命令，以及常见服务器插件里的管理员命令。
     */
    private static final Set<String> KNOWN_ADMIN = Set.of(
            "advancement", "attribute", "ban", "ban-ip", "banlist", "bossbar", "clear", "clone", "damage", "data",
            "datapack", "debug", "defaultgamemode", "deop", "difficulty", "effect", "enchant", "execute",
            "experience", "xp", "fill", "fillbiome", "forceload", "function", "gamemode", "gamerule", "give",
            "item", "jfr", "kick", "kill", "locate", "loot", "op", "pardon", "pardon-ip", "particle", "perf",
            "place", "playsound", "publish", "recipe", "reload", "return", "ride", "rotate", "save-all",
            "save-off", "save-on", "say", "schedule", "scoreboard", "seed", "setblock", "setidletimeout",
            "setworldspawn", "spawnpoint", "spectate", "spreadplayers", "stop", "stopsound", "summon", "tag",
            "team", "teleport", "tp", "tellraw", "tick", "time", "title", "transfer", "weather", "whitelist",
            "worldborder",
            "fly", "god", "heal", "feed", "gm", "gmc", "gms", "gmsp", "gma", "tphere", "tpall", "tppos", "tpo",
            "vanish", "v", "sudo", "invsee", "speed", "top", "jump", "thru", "repair", "fix", "smite",
            "lightning", "butcher", "killall", "nuke", "i", "more", "unlimited", "setspawn");
    private static final Set<String> allowed = new TreeSet<>();
    private static Path file;
    private static boolean loaded;
    // 真实客户端里才去问内置服务器的命令树；单元测试不触碰客户端单例
    private static boolean useIntegratedServer;

    private AgentCommandPolicy() {}

    /**
     * 提交前检查：普通聊天与普通玩家命令直接通过；管理员命令只有在放行名单里才通过，否则抛出拒绝原因。
     * 拒绝原因写给模型看，只说明为什么不能用、该怎么正常游玩，不教它怎么给自己开权限。
     */
    public static void check(ChatMessage message) {
        if (!message.command()) return;
        String name = commandName(message.text());
        if (RESERVED.equals(name)) {
            throw new IllegalArgumentException("/maicraft is reserved for the human player: the AI player cannot run "
                    + "MaiCraft client commands or change its own permissions.");
        }
        Set<String> current = allowed();
        if (current.contains(ALL) || current.contains(name) || !isAdminCommand(name)) return;
        throw new IllegalArgumentException("/" + name + " is an administrator command and is closed for the AI player "
                + "by the human player's setting. Play like a survival player: reach places, get items and change the "
                + "world through normal gameplay abilities instead of administrator commands.");
    }

    /**
     * 是否管理员命令：清单里有的直接算；单人或局域网主机时再问内置服务器的原生命令树，
     * 普通玩家（权限 0）用不了的根命令也算，这样模组自带的管理员命令同样识别。
     */
    public static boolean isAdminCommand(String raw) {
        String name = commandName(raw);
        if (KNOWN_ADMIN.contains(name)) return true;
        if (!useIntegratedServer) return false;
        try {
            var minecraft = Minecraft.getInstance();
            var server = minecraft == null ? null : minecraft.getSingleplayerServer();
            if (server == null) return false;
            var node = server.getCommands().getDispatcher().getRoot().getChild(name);
            return node != null && !node.canUse(server.createCommandSourceStack().withPermission(0));
        } catch (RuntimeException unavailable) {
            // 命令树正在重载等读取不到的情况，退回清单判定，不因此拦下普通玩家命令
            return false;
        }
    }

    /** 当前放行的管理员命令名单（只读副本）。 */
    public static synchronized Set<String> allowed() {
        ensureLoaded();
        return Set.copyOf(allowed);
    }

    /**
     * 放行一条管理员命令并保存；返回规范化后的命令名。/maicraft 与空名拒绝。
     * 先改本次运行的名单再写文件：写入失败时调用方提示，但本次运行已经生效。
     */
    public static synchronized String allow(String raw) throws IOException {
        String name = normalizedEntry(raw);
        if (name.isEmpty()) throw new IllegalArgumentException("command name is required");
        if (RESERVED.equals(name)) throw new IllegalArgumentException("/maicraft cannot be allowed for the AI player");
        ensureLoaded();
        allowed.add(name);
        persist();
        return name;
    }

    /** 收回一条命令并保存；名单里本来没有时返回 false。收回 * 只取消"全部放行"，单独放行的仍保留。 */
    public static synchronized boolean deny(String raw) throws IOException {
        ensureLoaded();
        boolean removed = allowed.remove(normalizedEntry(raw));
        if (removed) persist();
        return removed;
    }

    /** 全部收回，回到默认的"管理员命令一律不能发"。 */
    public static synchronized void clear() throws IOException {
        ensureLoaded();
        allowed.clear();
        persist();
    }

    /**
     * 重新读取名单：有启动参数用启动参数，否则读游戏目录下的配置文件。
     * gameDirectory 为 null 时不读写文件、也不查内置服务器（单元测试或尚未进入客户端）。
     */
    public static synchronized void reload(Path gameDirectory) {
        allowed.clear();
        file = gameDirectory == null ? null : gameDirectory.resolve(FILE);
        useIntegratedServer = gameDirectory != null;
        String stored = System.getProperty(PROPERTY);
        if (stored == null && file != null && Files.isRegularFile(file)) {
            Properties values = new Properties();
            try (Reader reader = Files.newBufferedReader(file)) {
                values.load(reader);
                stored = values.getProperty(KEY, "");
            } catch (IOException | IllegalArgumentException unreadable) {
                // 配置读坏时按默认关闭处理：宁可 AI 用不了管理员命令，也不能因为文件损坏把它们放开
                stored = "";
            }
        }
        for (String entry : (stored == null ? "" : stored).split(",")) {
            String name = normalizedEntry(entry);
            if (!name.isEmpty() && !RESERVED.equals(name)) allowed.add(name);
        }
        loaded = true;
    }

    /** 命令名：去掉开头的 /、取第一个词、转小写，带命名空间时取冒号后的名字（/minecraft:tp → tp）。 */
    static String commandName(String text) {
        String body = text == null ? "" : text.strip();
        if (body.startsWith("/")) body = body.substring(1).strip();
        int space = body.indexOf(' ');
        String name = (space >= 0 ? body.substring(0, space) : body).toLowerCase(Locale.ROOT);
        int colon = name.lastIndexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private static String normalizedEntry(String raw) {
        return raw != null && ALL.equals(raw.strip()) ? ALL : commandName(raw);
    }

    // 第一次检查时才绑定游戏目录：客户端已启动就读玩家保存的名单，否则只看启动参数。
    private static void ensureLoaded() {
        if (loaded) return;
        Minecraft minecraft = Minecraft.getInstance();
        reload(minecraft == null ? null : minecraft.gameDirectory.toPath());
    }

    private static void persist() throws IOException {
        if (file == null) return;
        Files.createDirectories(file.getParent());
        Properties values = new Properties();
        values.setProperty(KEY, String.join(",", allowed));
        try (Writer writer = Files.newBufferedWriter(file)) {
            values.store(writer, "MaiCraft: administrator commands the AI player may send (* = all; /maicraft never)");
        }
    }
}
