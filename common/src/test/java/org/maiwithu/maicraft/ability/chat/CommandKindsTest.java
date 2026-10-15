// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 命令种类：普通玩家命令与管理员命令怎么分，/maicraft 永远留给人。 */
class CommandKindsTest {
    @Test
    void 命令名去斜杠取第一个词并去命名空间() {
        assertEquals("tp", CommandKinds.commandName("/tp Steve"));
        assertEquals("tp", CommandKinds.commandName("  /minecraft:TP @s ~ ~ ~"));
        assertEquals("msg", CommandKinds.commandName("msg Steve 你好"));
        assertEquals("", CommandKinds.commandName("/"));
    }

    @Test
    void 名单里的算管理员命令_普通玩家命令不算() {
        assertTrue(CommandKinds.admin("/give @s diamond", KnowsAdminCommands.NONE));
        assertTrue(CommandKinds.admin("/gamemode creative", KnowsAdminCommands.NONE));
        assertTrue(CommandKinds.admin("/minecraft:tp Steve", KnowsAdminCommands.NONE));
        assertFalse(CommandKinds.admin("/msg Steve 你好", KnowsAdminCommands.NONE));
        assertFalse(CommandKinds.admin("/home", KnowsAdminCommands.NONE));
        assertFalse(CommandKinds.admin("/help", KnowsAdminCommands.NONE));
        assertFalse(CommandKinds.admin("/", KnowsAdminCommands.NONE));
    }

    @Test
    void 名单外的问服务器命令树() {
        KnowsAdminCommands tree = name -> name.equals("ftbteams");
        assertTrue(CommandKinds.admin("/ftbteams create 队", tree));
        assertFalse(CommandKinds.admin("/sethome", tree));
    }

    @Test
    void maicraft留给人() {
        assertTrue(CommandKinds.reserved("/maicraft commands allow tp"));
        assertTrue(CommandKinds.reserved("/MaiCraft"));
        assertFalse(CommandKinds.reserved("/msg maicraft 你好"));
    }
}
