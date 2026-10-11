// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.spi.MovingStructure;

/** 会动的结构换成走不得的地方：停着的逐格、读不全的整体、转着的扫盘、平移的往前延长、电梯井、脚下的不算；挡路先等。 */
class ObstacleShapesTest {

    private static final AABB LEFT = new AABB(0, 64, 0, 1, 66, 1);
    private static final AABB RIGHT = new AABB(2, 64, 0, 3, 66, 1);
    private static final AABB BOUNDS = new AABB(0, 64, 0, 3, 66, 1);
    private static final MovingStructure.Motion STILL = new MovingStructure.Motion.Still();

    private static MovingStructure structure(MovingStructure.Motion motion, boolean complete, AABB shaft,
            boolean carriesPlayer) {
        return new MovingStructure("c1", List.of(LEFT, RIGHT), BOUNDS, complete, motion, shaft, carriesPlayer);
    }

    @Test
    void 读全了的停着的结构按逐格碰撞_门洞留着() {
        assertEquals(List.of(LEFT, RIGHT), ObstacleShapes.blocked(structure(STILL, true, null, false), Set.of()));
    }

    @Test
    void 读不全的按整体范围() {
        assertEquals(List.of(BOUNDS), ObstacleShapes.blocked(structure(STILL, false, null, false), Set.of()));
    }

    @Test
    void 转着的按整个扫过的圆盘() {
        MovingStructure.Motion turning = new MovingStructure.Motion.Turning(new Vec3(0.5, 70.5, 0.5), Direction.Axis.Z, 5);
        List<AABB> blocked = ObstacleShapes.blocked(structure(turning, true, null, false), Set.of());
        assertEquals(List.of(new AABB(-4.5, 65.5, 0, 5.5, 75.5, 1)), blocked);
    }

    @Test
    void 平移的沿运动方向延长两秒() {
        MovingStructure.Motion sliding = new MovingStructure.Motion.Sliding(new Vec3(0.25, 0, 0));
        List<AABB> blocked = ObstacleShapes.blocked(structure(sliding, true, null, false), Set.of());
        assertEquals(new AABB(0, 64, 0, 11, 66, 1), blocked.get(0));
        assertEquals(new AABB(2, 64, 0, 13, 66, 1), blocked.get(1));
    }

    @Test
    void 电梯井算障碍_坐这台电梯时放开() {
        AABB shaft = new AABB(0, 60, 0, 3, 90, 1);
        MovingStructure elevator = structure(STILL, true, shaft, false);
        assertTrue(ObstacleShapes.blocked(elevator, Set.of()).contains(shaft));
        assertEquals(List.of(), ObstacleShapes.blocked(elevator, Set.of("c1")));
    }

    @Test
    void 自己站着的结构不当墙() {
        assertEquals(List.of(), ObstacleShapes.blocked(structure(STILL, true, null, true), Set.of()));
    }

    @Test
    void 在动的另列一份_挡路时先等() {
        MovingStructure still = structure(STILL, true, null, false);
        MovingStructure sliding = new MovingStructure("train", List.of(new AABB(10, 64, 10, 11, 66, 12)),
                new AABB(10, 64, 10, 11, 66, 12), true, new MovingStructure.Motion.Sliding(new Vec3(0, 0, 0.5)), null, false);
        NavigationObstacles.View view = NavigationObstacles.convert(List.of(still, sliding), Set.of());
        assertEquals(3, view.all().boxes().size());
        assertEquals(List.of(new AABB(10, 64, 10, 11, 66, 32)), view.moving());
    }

    @Test
    void 前方被正在动的东西挡着_等它过去_最多五秒() {
        List<BlockPos> ahead = List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0), new BlockPos(2, 64, 0));
        List<AABB> train = List.of(new AABB(2, 64, -5, 3, 67, 5));
        MovingObstacleWait wait = new MovingObstacleWait();
        assertTrue(wait.holds(0, ahead, train));
        assertTrue(wait.holds(50, ahead, train));
        // 车过去了：下一次看前方时接着走。
        assertTrue(wait.holds(55, ahead, train), "两次查看之间照旧等");
        assertFalse(wait.holds(60, ahead, List.of()));
        // 又被挡、一直不走：等满 5 秒就交回引擎绕。
        assertTrue(wait.holds(70, ahead, train));
        assertFalse(wait.holds(170, ahead, train));
        assertFalse(wait.holds(180, ahead, train), "等满了同一处不再等");
    }

    @Test
    void 脚下已经在它要扫过的地方_不原地等() {
        List<BlockPos> ahead = List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0));
        assertFalse(new MovingObstacleWait().holds(0, ahead, List.of(new AABB(-1, 64, -1, 2, 66, 2))));
    }
}
