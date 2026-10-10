// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 机器目标落实：把目标对象读成机器所在的那一格，附上那一格现在的方块状态。
 * 只读现场、给结论，不动角色：观察编号查场景表，地标查记过的地点，坐标与脚下直接用。
 * 机器档案与观察编号 m# 还没有接入：指一台机器先指它的组成格（b#）或坐标。
 */
final class MachineTargets {

    /** 解析的一种结局。 */
    sealed interface Resolution {
        /** 落实到了一格；那一格没加载时 state 为 null，调用方按到不了说，不猜里面是什么。 */
        record Found(BlockPos cell, BlockState state, String dimension) implements Resolution {}

        /** 解析不下去，带着现场事实结束。 */
        record DeadEnd(Problem problem) implements Resolution {}
    }

    private final MachineServices services;

    MachineTargets(MachineServices services) {
        this.services = services;
    }

    /** 解析一个目标对象；这一能力的几种写法之外的如实说不支持。 */
    Resolution resolve(Target target) {
        Optional<MachineWorldView.Spot> spot = services.world().playerSpot();
        if (spot.isEmpty()) {
            return new Resolution.DeadEnd(Problem.of(Problem.Kind.UNREACHABLE,
                    "角色现在不在世界里，看不了机器", "先回到世界"));
        }
        String dimension = spot.get().dimension();
        if (target == null || target instanceof Target.Here) {
            return found(spot.get().pos(), dimension);
        }
        if (target instanceof Target.Position position) {
            // 目标在别的维度：跨维度看不了，如实说，不猜那一格有什么。
            if (position.dimension() != null && !position.dimension().equals(dimension)) {
                return new Resolution.DeadEnd(Problem.of(Problem.Kind.UNREACHABLE,
                        "目标在另一个维度（" + position.dimension() + "），跨维度还没有支持"));
            }
            // y 省略时按角色的高度落：机器都在角色附近，角色脚下这一层最说得过去。
            int y = position.y() != null ? position.y() : spot.get().pos().getY();
            return found(new BlockPos(position.x(), y, position.z()), dimension);
        }
        if (target instanceof Target.Seen seen) {
            Optional<WorldPosition> hit = services.seenTargets().positionOf(seen.id());
            // 编号失效（走远、被拆、场景过期）：按"东西不在了"收场，重新观察才指得回来。
            if (hit.isEmpty()) {
                return new Resolution.DeadEnd(Problem.of(Problem.Kind.TARGET_GONE,
                        "观察编号 " + seen.id() + " 指的东西已经不在了", "重新 observe 再指"));
            }
            return found(cellOf(hit.get()), dimension);
        }
        if (target instanceof Target.Landmark landmark) {
            Optional<WorldPosition> hit = services.places().place(landmark.name());
            if (hit.isEmpty()) {
                return new Resolution.DeadEnd(Problem.of(Problem.Kind.NOT_FOUND,
                        "没有叫「" + landmark.name() + "」的记过地点或机器档案", "先用 remember 记下这个地点"));
            }
            return found(cellOf(hit.get()), dimension);
        }
        return new Resolution.DeadEnd(Problem.of(Problem.Kind.UNSUPPORTED,
                "机器还不支持这种目标：" + target.kind()));
    }

    // 读那一格现在的方块状态：没加载的格如实给 null，不按空气算。
    private Resolution.Found found(BlockPos cell, String dimension) {
        BlockState state = services.world().stateAt(cell).orElse(null);
        return new Resolution.Found(cell, state, dimension);
    }

    private static BlockPos cellOf(WorldPosition place) {
        return new BlockPos(place.x(), place.y(), place.z());
    }
}
