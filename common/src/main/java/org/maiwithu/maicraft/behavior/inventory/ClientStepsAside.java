// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.player.InputDriver;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 走开几步执行接缝的读端：丢完东西后朝远离丢出点的方向走几步，
 * 走路用角色的移动入口逐刻续发，不寻路——拾取冷却面前几步开阔地就够了。
 *
 * <p>方向挑背对丢出点的水平方向；刚丢出的落点登记（{@link DropAvoidance}）也在这条路上生效：
 * 每一步都检查要踩进的格子，附近是自己刚丢的东西就换一个方向绕开，走完的标准同样是
 * 离开丢出点够远且不再踩着任何登记落点。到了期限还没走出去（四面被挡）时动作结束，
 * 走不出去按到不了如实上报，让任务在结果里写明"可能被自己捡回"。
 */
public final class ClientStepsAside implements StepsAside {

    /** 每走一格最多给多少刻；走一格正常只要几刻，留出起跳与卡角的余量。 */
    private static final int TICKS_PER_BLOCK = 12;
    /** 方向挑选时的候选偏转角（度）：正后方被落点占住时逐档侧绕，直到背对半圈。 */
    private static final int[] DEVIATION_DEGREES = {0, 45, -45, 90, -90, 135, -135, 180};

    private final InputDriver input;
    private final DropAvoidance avoidance;

    public ClientStepsAside(InputDriver input, DropAvoidance avoidance) {
        this.input = Objects.requireNonNull(input);
        this.avoidance = Objects.requireNonNull(avoidance);
    }

    @Override
    public Optional<Action> stepAway(WorldPosition from, int blocks) {
        if (blocks <= 0) return Optional.empty();
        return Optional.of(new WalkAwayAction(from, blocks, (long) blocks * TICKS_PER_BLOCK + 40));
    }

    /**
     * 从背对丢出点的方向开始，按候选偏转角挑一个"下一步踩不进登记落点"的方向；
     * 全部候选都通向落点时如实回原方向硬走，到期走不出去由期限如实报失败。
     * {@code stepIntoDrop} 回答"朝这个方向迈一步会不会踩进刚丢的落点"，只读登记与几何，离线可测。
     */
    static Vec3 steppingDirection(double dx, double dz, Predicate<Vec3> stepIntoDrop) {
        Vec3 away = Math.abs(dx) + Math.abs(dz) < 0.01
                ? new Vec3(0, 0, 1)
                : new Vec3(dx, 0, dz).normalize();
        for (int degrees : DEVIATION_DEGREES) {
            Vec3 candidate = rotateY(away, degrees);
            if (!stepIntoDrop.test(candidate)) return candidate;
        }
        return away;
    }

    /** 水平向量绕竖轴旋转给定角度；角度制方便对着候选偏转表读。 */
    private static Vec3 rotateY(Vec3 flat, double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vec3(flat.x * cos + flat.z * sin, 0.0, -flat.x * sin + flat.z * cos);
    }

    /** 朝背对丢出点的方向走到离开够远、且不再踩着刚丢的落点为止；到期没走到按到不了失败。 */
    private final class WalkAwayAction implements Action {
        private final WorldPosition from;
        private final int blocks;
        private final long deadlineTicks;
        private long walkedTicks;

        WalkAwayAction(WorldPosition from, int blocks, long deadlineTicks) {
            this.from = from;
            this.blocks = blocks;
            this.deadlineTicks = deadlineTicks;
        }

        @Override
        public ActionStatus tick(TickContext context) {
            var player = context.player().localPlayer();
            double dx = player.getX() - (from.x() + 0.5);
            double dz = player.getZ() - (from.z() + 0.5);
            WorldPosition standing = WorldPosition.here(
                    (int) Math.floor(player.getX()),
                    (int) Math.floor(player.getY()),
                    (int) Math.floor(player.getZ()));
            // 已经离开足够远、脚下也不在自己刚丢的落点里：目的达到，松开按键收场。
            if (Math.sqrt(dx * dx + dz * dz) >= blocks && !avoidance.shouldAvoid(standing, context.gameTick())) {
                input.halt(player);
                return ActionStatus.done();
            }
            if (++walkedTicks > deadlineTicks) {
                input.halt(player);
                return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE,
                        "走不出去：四周都被挡住了，丢出的东西可能被自己捡回", null));
            }
            // 背对丢出点，下一步要踩进的格子落在刚丢的落点里就侧绕开，别两秒后把自己丢的吸回来。
            Vec3 away = steppingDirection(dx, dz, direction -> {
                double stepX = player.getX() + direction.x * 1.5;
                double stepZ = player.getZ() + direction.z * 1.5;
                WorldPosition nextCell = WorldPosition.here(
                        (int) Math.floor(stepX), standing.y(), (int) Math.floor(stepZ));
                return avoidance.shouldAvoid(nextCell, context.gameTick());
            });
            input.stepToward(player, player.getEyePosition().add(away.scale(2)), false);
            return ActionStatus.running();
        }

        @Override
        public String describe() {
            return "从丢出点走开 " + blocks + " 格";
        }
    }
}
