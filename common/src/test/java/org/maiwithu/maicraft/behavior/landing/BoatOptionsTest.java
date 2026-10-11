// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * 边掉边放船来不来得及：够得着时放船、下一刻起坐上去、再留一刻等确认，都要在撞上船之前；
 * 掉得越高越快，放船、坐船的那几刻越挤，七八格以上就来不及。
 */
class BoatOptionsTest {

    private final BoatOptions options = new BoatOptions(BlockPos.ZERO, new Vec3(0, 1.62, 0), 4.5, 3.0, 0.6, 1.8,
            null, List.of());

    @Test
    void aShortDropLeavesTimeToPlaceAndBoard() {
        assertTrue(options.airborneWindow(6, 0.08, 0));
    }

    @Test
    void aTallDropIsTooFastToBoardInTime() {
        assertFalse(options.airborneWindow(10, 0.08, 0));
        assertFalse(options.airborneWindow(30, 0.08, 0));
    }

    @Test
    void nonsenseInputsAreRejected() {
        assertFalse(options.airborneWindow(0, 0.08, 0));
        assertFalse(options.airborneWindow(6, 0, 0));
        assertFalse(options.airborneWindow(Double.NaN, 0.08, 0));
    }
}
