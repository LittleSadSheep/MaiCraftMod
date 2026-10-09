// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.Objects;
import java.util.Optional;

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
 * <p>方向挑背对丢出点的水平方向；走到离开丢出点足够远、或到了期限还没走出去（四面被挡）
 * 时动作结束，走不出去按到不了如实上报，让任务在结果里写明"可能被自己捡回"。
 */
public final class ClientStepsAside implements StepsAside {

    /** 每走一格最多给多少刻；走一格正常只要几刻，留出起跳与卡角的余量。 */
    private static final int TICKS_PER_BLOCK = 12;

    private final InputDriver input;

    public ClientStepsAside(InputDriver input) {
        this.input = Objects.requireNonNull(input);
    }

    @Override
    public Optional<Action> stepAway(WorldPosition from, int blocks) {
        if (blocks <= 0) return Optional.empty();
        return Optional.of(new WalkAwayAction(from, blocks, (long) blocks * TICKS_PER_BLOCK + 40));
    }

    /** 朝背对丢出点的方向走到离开够远为止；到期没走到按到不了失败。 */
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
            // 已经离开足够远：目的达到，松开按键收场。
            if (Math.sqrt(dx * dx + dz * dz) >= blocks) {
                input.halt(player);
                return ActionStatus.done();
            }
            if (++walkedTicks > deadlineTicks) {
                input.halt(player);
                return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE,
                        "走不出去：四周都被挡住了，丢出的东西可能被自己捡回", null));
            }
            // 背对丢出点的水平方向；没有水平位移（就在丢出点正下方）时随便朝南走。
            Vec3 away = Math.abs(dx) + Math.abs(dz) < 0.01
                    ? new Vec3(0, 0, 1)
                    : new Vec3(dx, 0, dz).normalize();
            input.stepToward(player, player.getEyePosition().add(away.scale(2)), false);
            return ActionStatus.running();
        }

        @Override
        public String describe() {
            return "从丢出点走开 " + blocks + " 格";
        }
    }
}
