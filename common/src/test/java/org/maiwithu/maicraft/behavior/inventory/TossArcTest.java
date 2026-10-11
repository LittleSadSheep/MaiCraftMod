// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/** 丢东西的抛物线：贴着工作盆站能扔进去，站远了、斜着、顶上压着方块就扔不准；出手前按真朝向再核。 */
class TossArcTest {

    /** 工作盆在原点那一格，盆沿 13/16 格高；脚下 y=-1 一层实心地面。 */
    private static final TossArc.Target BASIN = new TossArc.Target(BlockPos.ZERO, 13 / 16.0);
    private static final double EYE_HEIGHT = 1.62;

    private static TossArc.Obstacles flatGround(Set<BlockPos> extra) {
        return cell -> cell.getY() == -1 || extra.contains(cell) ? List.of(new AABB(cell)) : List.of();
    }

    @Test
    void 贴着盆站_挑得出每种随机偏差都落进盆的角度() {
        // 人贴着盆西侧：身体半宽 0.3，眼睛离盆中心 0.8 格。
        Vec3 eye = new Vec3(-0.3, EYE_HEIGHT, 0.5);
        Optional<TossArc.Aim> aim = TossArc.choose(eye, BASIN, flatGround(Set.of()));
        assertTrue(aim.isPresent());
        assertTrue(aim.get().worstMiss() < 0.47, "最坏的落点也在那一格里");
        assertTrue(aim.get().pitch() > 0, "往下看着扔");
        // 镜头真转到挑好的角度时，按实际视线核一遍也落得进去。
        Vec3 view = TossArc.direction(aim.get().yaw(), aim.get().pitch());
        assertTrue(TossArc.lands(eye, view, BASIN, flatGround(Set.of())));
        // 镜头还抬着头时出手会扔过头。
        assertFalse(TossArc.lands(eye, TossArc.direction(aim.get().yaw(), -30), BASIN, flatGround(Set.of())));
    }

    @Test
    void 斜对角或隔一格站_随机偏差会散出盆外() {
        assertTrue(TossArc.choose(new Vec3(-1.5, EYE_HEIGHT, 0.5), BASIN, flatGround(Set.of())).isEmpty(),
                "隔一格扔不准");
        assertTrue(TossArc.choose(new Vec3(-0.5, EYE_HEIGHT, -0.5), BASIN, flatGround(Set.of())).isEmpty(),
                "斜对角扔不准");
    }

    @Test
    void 盆顶上压着方块_半路撞上就扔不进去() {
        Vec3 eye = new Vec3(-0.3, EYE_HEIGHT, 0.5);
        assertTrue(TossArc.choose(eye, BASIN, flatGround(Set.of(new BlockPos(0, 1, 0)))).isEmpty());
    }

    @Test
    void 平地上站在石磨旁边_落点低一些也挑得出角度() {
        // 石磨外圈的台面 6/16 格高，中间磨盘顶到格顶：东西落到台面以前都还在那一格里。
        TossArc.Target millstone = new TossArc.Target(BlockPos.ZERO, 6 / 16.0);
        assertTrue(TossArc.choose(new Vec3(-0.5, EYE_HEIGHT, 0.5), millstone, flatGround(Set.of())).isPresent());
    }
}
