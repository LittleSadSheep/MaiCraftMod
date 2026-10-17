// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 无线红石打字机的设置：频率写成"物品 + 物品"，能写名字的键逐个列出。 */
class TypewriterMachineTest {

    @Test
    void 频率写成两样物品() {
        assertEquals(List.of("minecraft:iron_ingot", "minecraft:redstone"),
                TypewriterMachine.parse("minecraft:Iron_Ingot + minecraft:redstone"));
        assertNull(TypewriterMachine.parse("minecraft:iron_ingot"));
        assertNull(TypewriterMachine.parse("铁锭 + 红石"));
        assertEquals("minecraft:iron_ingot + minecraft:air",
                TypewriterMachine.value(List.of("minecraft:iron_ingot", "minecraft:air")));
    }

    @Test
    void 能写名字的键逐个列出() {
        List<Integer> keys = TypewriterKeys.nameable();
        assertTrue(keys.contains((int) 'W') && keys.contains((int) '0') && keys.contains(32) && keys.contains(265));
        assertEquals(26 + 10 + 11, keys.size());
    }
}
