// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/** 丢东西的抛物线：贴着工作盆站每种偏差都扔得进去；站远了、斜着落不全也给个落进去最多的角度；顶上压着方块就给不出。 */
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
        assertTrue(aim.get().sure(), "每种随机偏差都落进那一格");
        assertTrue(aim.get().spread() < 0.47, "最坏的落点也在那一格里");
        assertTrue(aim.get().pitch() > 0, "往下看着扔");
        // 镜头真转到挑好的角度时，按实际视线数一遍也全落得进去；转到了的判定也认这条视线。
        Vec3 view = TossArc.direction(aim.get().yaw(), aim.get().pitch());
        assertEquals(TossArc.SAMPLES, TossArc.landed(eye, view, BASIN, flatGround(Set.of())));
        assertTrue(aim.get().turnedTo(view));
        // 镜头还抬着头时出手会扔过头，也不算转到了。
        Vec3 raised = TossArc.direction(aim.get().yaw(), -30);
        assertTrue(TossArc.landed(eye, raised, BASIN, flatGround(Set.of())) < TossArc.SAMPLES);
        assertFalse(aim.get().turnedTo(raised));
    }

    @Test
    void 斜对角或隔一格站_落不全也给落进去最多的角度() {
        // 没有一个角度能让每种偏差都落进去；但有偏差落得进去就照给，由调用方说明是碰运气扔的。
        Optional<TossArc.Aim> apart = TossArc.choose(new Vec3(-1.5, EYE_HEIGHT, 0.5), BASIN, flatGround(Set.of()));
        assertFalse(apart.map(TossArc.Aim::sure).orElse(false), "隔一格扔不准");
        apart.ifPresent(aim -> assertTrue(aim.landed() > 0 && aim.landed() < TossArc.SAMPLES));
        Optional<TossArc.Aim> diagonal = TossArc.choose(new Vec3(-0.5, EYE_HEIGHT, -0.5), BASIN, flatGround(Set.of()));
        assertFalse(diagonal.map(TossArc.Aim::sure).orElse(false), "斜对角扔不准");
    }

    @Test
    void 盆顶上压着方块_一种偏差都落不进去才给不出() {
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
