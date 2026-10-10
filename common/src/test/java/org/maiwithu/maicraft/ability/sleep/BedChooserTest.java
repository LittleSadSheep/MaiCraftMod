// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/** 选床的离线场景：占用、受保护、旁边有怪的床被排除；床区限定生效；按离角色近的排序。 */
class BedChooserTest {

    private static BedCandidate bed(int x, int z, boolean occupied, boolean protectedLand, boolean hostileNear,
            double distance) {
        return new BedCandidate(new BlockPos(x, 64, z), occupied, protectedLand, hostileNear, distance);
    }

    @Test
    void occupiedProtectedAndThreatenedBedsAreExcluded() {
        Optional<BedCandidate> chosen = BedChooser.choose(
                List.of(bed(2, 0, true, false, false, 2),
                        bed(3, 0, false, true, false, 3),
                        bed(4, 0, false, false, true, 4),
                        bed(5, 0, false, false, false, 5)),
                Set.of(), null);
        // 只有最后一张三关全过：被占用、受保护、旁边有怪的都不算能用的床。
        assertEquals(new BlockPos(5, 64, 0), chosen.orElseThrow().head());
    }

    @Test
    void triedBedsAreNotChosenAgain() {
        BlockPos tried = new BlockPos(1, 64, 0);
        Optional<BedCandidate> chosen = BedChooser.choose(
                List.of(bed(1, 0, false, false, false, 1), bed(9, 0, false, false, false, 9)),
                Set.of(tried), null);
        // 试过不行的床不再试，哪怕它更近。
        assertEquals(new BlockPos(9, 64, 0), chosen.orElseThrow().head());
    }

    @Test
    void bedsOutsideTheAreaAreIgnoredWhenAreaIsGiven() {
        WorldPosition area = WorldPosition.here(0, 64, 0);
        Optional<BedCandidate> chosen = BedChooser.choose(
                List.of(bed(3, 3, false, false, false, 1),
                        bed(200, 200, false, false, false, 0.5)),
                Set.of(), area);
        // 指定了床区就只在半径内选：再近的远床也不算"这一片"的。
        assertEquals(new BlockPos(3, 64, 3), chosen.orElseThrow().head());
    }

    @Test
    void nearestUsableBedComesFirst() {
        List<BedCandidate> usable = BedChooser.usable(
                List.of(bed(10, 0, false, false, false, 10),
                        bed(2, 0, false, false, false, 2),
                        bed(5, 0, false, false, false, 5)),
                Set.of(), null);
        assertEquals(3, usable.size());
        assertEquals(new BlockPos(2, 64, 0), usable.getFirst().head());
        assertTrue(usable.stream().sorted(Comparator.comparingDouble(BedCandidate::distance))
                .toList().equals(usable), "按近到远排好");
    }
}
