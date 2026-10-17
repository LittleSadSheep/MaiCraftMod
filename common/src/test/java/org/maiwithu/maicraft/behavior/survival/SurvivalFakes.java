// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 生存需求的离线测试替身：不接触真实客户端，按脚本回答处境、记下发出的输入与挖过的格子。
 */
final class SurvivalFakes {

    private SurvivalFakes() {}

    /** 处境构造器：默认是站在地上、头在空气里、氧气满、没被埋的平静处境，测试只改要的那几项。 */
    static final class Situation {
        double health = 20.0;
        double feetY = 64.0;
        float yaw = 37.0f;
        boolean falling;
        boolean overVoid;
        boolean landsInWater;
        boolean landsUnhurt = true;
        boolean headInWater;
        boolean canBreatheUnderwater;
        int air = 300;
        int maxAir = 300;
        int airToSurface = 69;
        boolean stuck;
        BlockPos buried;
        BlockPos ceiling;

        Situation feet(double y) { feetY = y; return this; }
        Situation fallingHard() {
            falling = true;
            landsUnhurt = false;
            return this;
        }
        Situation fallingIntoVoid() { falling = true; overVoid = true; landsUnhurt = false; return this; }
        Situation fallingIntoWater() { falling = true; landsInWater = true; return this; }
        Situation underwater(int airTicks) { headInWater = true; air = airTicks; return this; }
        Situation breathing() { canBreatheUnderwater = true; return this; }
        Situation surfaceNeeds(int airTicks) { airToSurface = airTicks; return this; }
        Situation air(int airTicks) { air = airTicks; return this; }
        Situation buriedAt(BlockPos cell) { stuck = true; buried = cell; return this; }
        Situation underCeiling(BlockPos cell) { ceiling = cell; return this; }

        SurvivalSituation build() {
            return new SurvivalSituation(health, feetY, yaw, falling, overVoid, landsInWater, landsUnhurt,
                    headInWater, canBreatheUnderwater, air, maxAir, airToSurface, stuck, buried, ceiling);
        }
    }

    static Situation calm() {
        return new Situation();
    }

    /** 头在水里、氧气只剩一泡：必须立刻换气。 */
    static SurvivalSituation underwater(double feetY) {
        return calm().feet(feetY).underwater(30).build();
    }

    /** 头在水面上、氧气已经补满。 */
    static SurvivalSituation inAir(double feetY) {
        return calm().feet(feetY).build();
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
            @Override public void applyMovement(PlayerInput.Movement movement, long requestTick) {
                movements++;
                applied.add(movement);
            }
            @Override public void requestLook(float yaw, float pitch, long requestTick) {
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
}
