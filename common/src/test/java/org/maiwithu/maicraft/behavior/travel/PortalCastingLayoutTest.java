// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.junit.jupiter.api.Test;

/** 浇筑模板：换岸向后仍是十格框、模具不占框格、浇框水在框后方、每格浇时都有已有的点击面。 */
class PortalCastingLayoutTest {

    @Test
    void castingTargetsAgreeWithTheVanillaFrameInEveryShoreDirection() {
        for (Direction shore : Direction.Plane.HORIZONTAL) {
            PortalCastingLayout layout = new PortalCastingLayout(new BlockPos(10, 64, 20), shore);
            Set<BlockPos> frame = new HashSet<>(layout.bottom());
            frame.addAll(layout.upperFrame());
            assertEquals(PortalCastingLayout.FRAME_CELLS, frame.size(), shore + " 十格框");
            assertEquals(new HashSet<>(layout.frame().frame()), frame, shore + " 浇的格就是门框的格");
            // 起手行是门内两格加两侧底脚，正好在池面上那一行。
            assertTrue(layout.startRow().containsAll(List.of(layout.placeholder(), layout.initialWater())));
            assertTrue(frame.contains(layout.cell(-1, 0, 0)) && frame.contains(layout.cell(2, 0, 0)));
            // 模具不占黑曜石的格；浇框水在框后方，不占顶行。
            assertEquals(6, layout.mold().size());
            assertTrue(layout.mold().stream().noneMatch(frame::contains), shore + " 模具不占框格");
            assertFalse(frame.contains(layout.castingWater()));
            assertFalse(layout.mold().contains(layout.castingWater()));
            assertTrue(layout.footprint().containsAll(layout.clearance()));
            assertTrue(layout.footprint().containsAll(layout.bank()));
            assertEquals(6 * 4, layout.bank().size());
        }
    }

    @Test
    void everyCastCellHasAnExistingClickFaceWhenItsTurnComes() {
        for (Direction shore : Direction.Plane.HORIZONTAL) {
            PortalCastingLayout layout = new PortalCastingLayout(new BlockPos(0, 64, 0), shore);
            // 起手已成两侧底脚与底行；之后每格都要借模具、底脚或上一格黑曜石的面。
            Set<BlockPos> supports = new HashSet<>(layout.mold());
            supports.addAll(layout.bottom());
            supports.add(layout.cell(-1, 0, 0));
            supports.add(layout.cell(2, 0, 0));
            for (BlockPos target : layout.upperFrame()) {
                boolean supported = supports.contains(target);
                for (Direction side : Direction.values()) {
                    supported |= supports.contains(target.relative(side));
                }
                assertTrue(supported, shore + " 浇 " + target.toShortString() + " 时有点击面");
                supports.add(target);
            }
        }
    }

    @Test
    void stepsFollowTheHandOrder() {
        PortalCastingLayout layout = new PortalCastingLayout(new BlockPos(0, 64, 0), Direction.NORTH);
        List<PortalCastingLayout.Step> steps = layout.steps();
        assertEquals(PortalCastingLayout.Step.Kind.PLACE, steps.get(0).kind());
        assertEquals(layout.placeholder(), steps.get(0).cell());
        assertEquals(PortalCastingLayout.Step.Kind.POUR_WATER, steps.get(1).kind());
        assertEquals(layout.initialWater(), steps.get(1).cell());
        assertEquals(PortalCastingLayout.Step.Kind.DIG, steps.get(2).kind());
        // 底行两格浇完才收起手水，再砌模具、倒浇框水、浇八格、收水、等退水。
        assertEquals(PortalCastingLayout.Step.Kind.SCOOP_WATER, steps.get(5).kind());
        assertEquals(PortalCastingLayout.Step.Kind.MOLD, steps.get(6).kind());
        assertEquals(PortalCastingLayout.Step.Kind.POUR_WATER, steps.get(7).kind());
        assertEquals(layout.castingWater(), steps.get(7).cell());
        long casts = steps.stream().filter(step -> step.kind() == PortalCastingLayout.Step.Kind.CAST).count();
        assertEquals(PortalCastingLayout.FRAME_CELLS, casts, "十格框每格一步");
        assertEquals(PortalCastingLayout.Step.Kind.DRAIN, steps.get(steps.size() - 1).kind());
        // 整地要清的格：池面以上的框格与净空，不含池面那一行。
        assertTrue(layout.cellsToClear().stream().allMatch(at -> at.getY() > layout.origin().getY()));
    }
}
