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
        assertEquals(new BlockPos(5, 64, 0), chosen.orElseThrow().cell());
    }

    @Test
    void triedBedsAreNotChosenAgain() {
        BlockPos tried = new BlockPos(1, 64, 0);
        Optional<BedCandidate> chosen = BedChooser.choose(
                List.of(bed(1, 0, false, false, false, 1), bed(9, 0, false, false, false, 9)),
                Set.of(tried), null);
        // 试过不行的床不再试，哪怕它更近。
        assertEquals(new BlockPos(9, 64, 0), chosen.orElseThrow().cell());
    }

    @Test
    void bedsOutsideTheAreaAreIgnoredWhenAreaIsGiven() {
        WorldPosition area = WorldPosition.here(0, 64, 0);
        Optional<BedCandidate> chosen = BedChooser.choose(
                List.of(bed(3, 3, false, false, false, 1),
                        bed(200, 200, false, false, false, 0.5)),
                Set.of(), area);
        // 指定了床区就只在半径内选：再近的远床也不算"这一片"的。
        assertEquals(new BlockPos(3, 64, 3), chosen.orElseThrow().cell());
    }

    @Test
    void nearestUsableBedComesFirst() {
        List<BedCandidate> usable = BedChooser.usable(
                List.of(bed(10, 0, false, false, false, 10),
                        bed(2, 0, false, false, false, 2),
                        bed(5, 0, false, false, false, 5)),
                Set.of(), null);
        assertEquals(3, usable.size());
        assertEquals(new BlockPos(2, 64, 0), usable.getFirst().cell());
        assertTrue(usable.stream().sorted(Comparator.comparingDouble(BedCandidate::distance))
                .toList().equals(usable), "按近到远排好");
    }

    @Test
    void atNightOnlyBedsWithinThirtyTwoBlocksCount() {
        // 实机 10-15：夜里从家走 130 格去睡上一晚野外那张床。夜里只认 32 格以内的床。
        List<BedCandidate> farOnly = List.of(bed(130, 0, false, false, false, 130));
        assertTrue(BedChooser.choose(farOnly, Set.of(), null, BedChooser.NIGHT_WALK_LIMIT_BLOCKS).isEmpty());
        // 不限距离时（公开睡觉）远处那张照样能选。
        assertTrue(BedChooser.choose(farOnly, Set.of(), null).isPresent());
        // 近处有一张就选近处的。
        Optional<BedCandidate> chosen = BedChooser.choose(
                List.of(bed(130, 0, false, false, false, 130), bed(20, 0, false, false, false, 20)),
                Set.of(), null, BedChooser.NIGHT_WALK_LIMIT_BLOCKS);
        assertEquals(new BlockPos(20, 64, 0), chosen.orElseThrow().cell());
    }
}
