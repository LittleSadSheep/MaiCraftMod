// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 寻路的保护：这个世界的保护判断装进来后，挖路垫路前的检查也要过它；离开世界摘掉。 */
class NavigationProtectionWorldRuleTest {

    @AfterEach
    void 摘掉世界保护() {
        NavigationProtection.installWorldRule(NavigationProtection.CellRule.NONE);
    }

    @Test
    void 世界保护挡住的格子_挖路前的检查也挡() {
        NavigationProtection.installWorldRule((x, y, z) -> x == 3 && y == 64 && z == 3);
        assertTrue(NavigationProtection.protects(new BlockPos(3, 64, 3)));
        assertTrue(NavigationProtection.worldProtects(3, 64, 3));
        assertFalse(NavigationProtection.protects(new BlockPos(4, 64, 3)));
        NavigationProtection.installWorldRule(NavigationProtection.CellRule.NONE);
        assertFalse(NavigationProtection.protects(new BlockPos(3, 64, 3)));
    }
}
