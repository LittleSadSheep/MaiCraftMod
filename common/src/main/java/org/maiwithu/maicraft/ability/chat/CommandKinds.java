// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.chat;

import java.util.Locale;
import java.util.Set;

/**
 * 游戏命令分两种，全仓只有这一份判断：普通玩家命令（/msg、/home、/help、/spawn 这类谁都能用的）
 * 和管理员命令（/tp、/give、/gamemode、/time 这类要权限的）。角色像生存玩家一样玩：
 * 普通命令照发，管理员命令默认不碰；/maicraft 永远留给人，角色不能改自己的权限或 Mod 设置。
 *
 * <p>认管理员命令有两条路：一份写死的名单（原版要二级权限的命令，加常见服务器插件的管理员命令），
 * 再问服务器的命令树（单人或局域网主机时拿得到，普通玩家用不了的根命令也算，模组自带的管理员命令
 * 这样也认得出）。连远程服务器时拿不到命令树，只按名单认。纯函数，不碰游戏。
 */
final class CommandKinds {
    /** MaiCraft 自己的客户端命令：永远不放行，不让角色改自己的权限。 */
    static final String RESERVED = "maicraft";

    /** 原版需要二级及以上权限的命令，以及常见服务器插件里的管理员命令；连远程服务器拿不到命令树时靠它兜底。 */
    static final Set<String> KNOWN_ADMIN = Set.of(
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

    private CommandKinds() {}

    /** 命令名：去掉开头的 /、取第一个词、转小写，带命名空间时取冒号后的名字（/minecraft:tp → tp）。 */
    static String commandName(String text) {
        String body = text == null ? "" : text.strip();
        if (body.startsWith("/")) body = body.substring(1).strip();
        int space = body.indexOf(' ');
        String name = (space >= 0 ? body.substring(0, space) : body).toLowerCase(Locale.ROOT);
        int colon = name.lastIndexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    /** 是 MaiCraft 自己的客户端命令：留给人，角色一律不发。 */
    static boolean reserved(String text) {
        return RESERVED.equals(commandName(text));
    }

    /** 是管理员命令：名单里有的直接算；不在名单里再问服务器的命令树（拿不到就不算）。 */
    static boolean admin(String text, KnowsAdminCommands tree) {
        String name = commandName(text);
        if (name.isEmpty()) return false;
        return KNOWN_ADMIN.contains(name) || tree.adminOnly(name);
    }
}
