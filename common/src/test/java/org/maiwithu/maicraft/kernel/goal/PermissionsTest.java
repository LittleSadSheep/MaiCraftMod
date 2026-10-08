// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 许可的合并：这次任务给的覆盖只改写了的字段，没写的落回默认，地标取并集。
 */
class PermissionsTest {

    @Test
    void 什么都不覆盖_就是默认值() {
        var merged = Permissions.DEFAULT.mergedWith(null, null, null, null, null, null);
        assertEquals(Permissions.DEFAULT, merged);
    }

    @Test
    void 只改写了的字段_其余保持默认() {
        var merged = Permissions.DEFAULT.mergedWith(
                Permissions.BlockChanges.TEMPORARY, null, null, Permissions.AnimalKilling.NONE, null, null);
        assertEquals(Permissions.BlockChanges.TEMPORARY, merged.changeBlocks());
        assertEquals(Permissions.AnimalKilling.NONE, merged.killAnimals());
        assertEquals(Permissions.Fight.HOSTILE_MOBS, merged.fight());
        assertEquals(Permissions.SurvivalNeeds.ON, merged.survivalNeeds());
        assertTrue(!merged.useRareItems());
    }

    @Test
    void 地标取并集_不是替换() {
        var withLandmarks = Permissions.DEFAULT.mergedWith(
                null, null, null, null, null, Set.of("家", "磨坊"));
        assertEquals(Set.of("家", "磨坊"), withLandmarks.protectedLandmarks());
        var more = withLandmarks.mergedWith(null, null, null, null, null, Set.of("磨坊", "矿洞"));
        assertEquals(Set.of("家", "磨坊", "矿洞"), more.protectedLandmarks());
    }
}
