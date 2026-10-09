// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 生存需求的离线测试替身：不接触真实客户端，按脚本回答处境、记下发出的输入与挖过的格子。
 */
final class SurvivalFakes {

    private SurvivalFakes() {}

    /** 测试用处境：直接给字段值，不读游戏。 */
    static SurvivalSituation situation(double fallDistance, double health, double feetY, float facingYaw,
                                       boolean headInWater, int airBubbles, boolean drowning,
                                       boolean stuck, BlockPos buriedCell, boolean overVoid) {
        return new SurvivalSituation(fallDistance, health, feetY, facingYaw,
                headInWater, airBubbles, drowning, stuck, buriedCell, overVoid);
    }

    static SurvivalSituation underwater(double feetY) {
        return situation(0, 20.0, feetY, 37.0f, true, 1, false, false, null, false);
    }

    static SurvivalSituation inAir(double feetY) {
        return situation(0, 20.0, feetY, 37.0f, false, 10, false, false, null, false);
    }

    /** 处境读取器替身：每刻给一份脚本里的处境（同一刻内重复读取得到同一份），用完后重复最后一份。 */
    static SurvivalSituation.SituationReader scripted(SurvivalSituation... situations) {
        Deque<SurvivalSituation> queue = new ArrayDeque<>(List.of(situations));
        SurvivalSituation last = situations[situations.length - 1];
        return new SurvivalSituation.SituationReader() {
            private long lastTick = Long.MIN_VALUE;
            private SurvivalSituation current;

            @Override
            public SurvivalSituation read(TickContext context) {
                long tick = context.gameTick();
                if (tick != lastTick) {
                    lastTick = tick;
                    current = queue.isEmpty() ? last : queue.poll();
                }
                return current;
            }
        };
    }

    /** 角色上下文替身：记录每刻发出的移动与转头指令。 */
    static final class TestPlayer implements PlayerContext {
        final PlayerInput input = new FakeInput();
        long tick;
        int movements;
        final List<PlayerInput.Movement> applied = new ArrayList<>();
        final List<float[]> looks = new ArrayList<>();

        TestPlayer nextTick() { tick++; return this; }

        @Override public LocalPlayer localPlayer() { return null; }
        @Override public ClientLevel level() { return null; }
        @Override public ClientPacketListener connection() { return null; }
        @Override public InteractionSender interactionSender() { return null; }
        @Override public MenuActions menuActions() { return null; }
        @Override public PlayerInput input() { return input; }
        @Override public long clientTick() { return tick; }
        @Override public boolean isCurrent() { return true; }
        @Override public boolean canInteractThisTick() { return true; }
        @Override public boolean tryClaimInteraction() { return true; }

        /** 输入替身：只记录，不写进任何玩家。 */
        final class FakeInput implements PlayerInput {
            @Override public boolean automationOwnsControls() { return true; }
            @Override public void applyMovement(PlayerInput.Movement movement, long leaseTickRevision) {
                movements++;
                applied.add(movement);
            }
            @Override public void requestLook(float yaw, float pitch, long leaseTickRevision) {
                looks.add(new float[]{yaw, pitch});
            }
            @Override public void clearLook() {}
            @Override public void releaseAll() {}
            @Override public void halt(LocalPlayer player) {}
            @Override public void lookAt(LocalPlayer player, Vec3 point) {}
        }
    }

    /** 每刻上下文替身：把测试角色递给任务。 */
    record TestTick(TestPlayer player) implements TickContext {
        @Override public long gameTick() { return player.tick; }
    }

    /** 挖掘动作替身：按次序回答每次推进的结果，记下被挖的格子与是否已收尾。 */
    static final class FakeBreaking implements BlockBreaking {
        final List<ActionStatus> script;
        final List<BlockPos> dug = new ArrayList<>();
        boolean closed;
        private BlockPos cell;
        private int calls;

        FakeBreaking(ActionStatus... script) {
            this.script = List.of(script);
        }

        @Override public void aimAt(BlockPos target) { cell = target; }

        @Override public ActionStatus tick(TickContext context) {
            dug.add(cell);
            if (script.isEmpty()) return ActionStatus.running();
            ActionStatus status = script.get(Math.min(calls, script.size() - 1));
            calls++;
            return status;
        }

        @Override public void close() { closed = true; }

        @Override public String describe() { return "测试挖掘"; }
    }

    /** 动作替身：按脚本做满指定刻数后完成或失败。 */
    static final class FakeCushion implements Action {
        private final long finishAfterTicks;
        private final Problem failure;
        long ticks;

        FakeCushion(long finishAfterTicks, Problem failure) {
            this.finishAfterTicks = finishAfterTicks;
            this.failure = failure;
        }

        static FakeCushion succeedingAfter(long ticks) { return new FakeCushion(ticks, null); }

        static FakeCushion failing() {
            return new FakeCushion(2, Problem.of(Problem.Kind.REFUSED_BY_GAME, "测试失败"));
        }

        @Override public ActionStatus tick(TickContext context) {
            ticks++;
            if (ticks < finishAfterTicks) return ActionStatus.running();
            return failure == null ? ActionStatus.done() : ActionStatus.failed(failure);
        }

        @Override public String describe() { return "测试放水"; }
    }
}
