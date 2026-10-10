// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.survival.CombatSenses;
import org.maiwithu.maicraft.behavior.survival.ThreatAssessment;
import org.maiwithu.maicraft.behavior.survival.WeaponChoice;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/** 战斗任务：点名目标朝它本人走过去、逼近不每刻重新寻路；倒地的算击败，跟丢的不算。 */
class FightTaskTest {

    /** 刻号替身：战斗任务只经感观、点名核对与走位替身读现场。 */
    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            throw new IllegalStateException("战斗任务测试不碰角色对象");
        }
    }

    /** 感观替身：满血拿剑，周围没有别的威胁。 */
    private static final class Senses implements CombatSenses {
        @Override public List<Threat> threats(TickContext context, double radius) { return List.of(); }

        @Override public List<Attacker> recentAttackers(TickContext context) { return List.of(); }

        @Override public CombatProfile profile(TickContext context) {
            return new CombatProfile(20, 10,
                    Optional.of(new WeaponChoice.Picked("minecraft:iron_sword", WeaponChoice.Weapon.SWORD_OR_AXE)), 4);
        }

        @Override public Entity entityById(TickContext context, int entityId) { return null; }
    }

    /** 点名核对替身：e1 是 7 号僵尸，每刻的观察由测试摆。 */
    private static final class Targets implements SeenTargets {
        SeenTargets.Observed now;

        @Override public Locked lock(TickContext context, String observedId) {
            return "e1".equals(observedId) ? new Locked(7, new UUID(0, 7), "minecraft:zombie") : null;
        }

        @Override public Observed observe(TickContext context, int entityId) { return now; }
    }

    /** 走位替身：走到一直在走，出手一下就完；记下起了哪些动作、收尾了几次。 */
    private static final class Moves implements FightMoves {
        final List<String> started = new ArrayList<>();
        int closed;
        /** 地上的掉落物；测试摆，捡起时拿掉。 */
        final List<Drop> drops = new ArrayList<>();
        int carried;

        @Override public Action strike(TickContext context, int entityId) {
            return action("打 " + entityId, ActionStatus.done());
        }

        @Override public Action walkTo(double x, double y, double z) {
            return action("走向 " + x + "," + z, ActionStatus.running());
        }

        @Override public double[] selfPosition(TickContext context) { return new double[] {0, 64, 0}; }

        @Override public List<Drop> dropsNear(TickContext context, double x, double y, double z, double radius) {
            return drops.stream().filter(drop -> Math.hypot(drop.x() - x, drop.z() - z) <= radius).toList();
        }

        @Override public int carriedItemCount(TickContext context) { return carried; }

        private Action action(String what, ActionStatus status) {
            started.add(what);
            return new Action() {
                @Override public ActionStatus tick(TickContext context) { return status; }

                @Override public void close() { closed++; }

                @Override public String describe() { return what; }
            };
        }
    }

    private final Targets targets = new Targets();
    private final Moves moves = new Moves();
    /** 点名的二次确认：refusal 为空就是能打。 */
    private Optional<Problem> refusal = Optional.empty();
    private final FightTask task = new FightTask(
            new FightInput(List.of("e1"), null, FightInput.DEFAULT_RADIUS, null, Permissions.DEFAULT),
            new Senses(), targets, moves, (target, permissions) -> refusal);

    // 推进若干刻；结束了返回结果，没结束返回 null。
    private TaskResult run(int ticks) {
        task.start(new Tick(0));
        for (long t = 0; t < ticks; t++) {
            if (task.tick(new Tick(t)) instanceof TickResult.Finished finished) {
                return finished.result();
            }
        }
        return null;
    }

    @Test
    void litCreeperNearbyIsEvadedBeforeApproachingTheNamedTarget() {
        // 点名打僵尸，旁边一只点着引信的苦力怕离 4 格：先退到 9 格外，不朝僵尸走。
        CombatSenses withCreeper = new CombatSenses() {
            @Override public List<Threat> threats(TickContext context, double radius) {
                return List.of(new Threat(9, new UUID(0, 9), "minecraft:creeper", 4, 64, 0, 4,
                        ThreatAssessment.Kind.EXPLOSIVE, true, true, true));
            }
            @Override public List<Attacker> recentAttackers(TickContext context) { return List.of(); }
            @Override public CombatProfile profile(TickContext context) {
                return new CombatProfile(20, 10,
                        Optional.of(new WeaponChoice.Picked("minecraft:iron_sword", WeaponChoice.Weapon.SWORD_OR_AXE)), 4);
            }
            @Override public Entity entityById(TickContext context, int entityId) { return null; }
        };
        FightTask fight = new FightTask(
                new FightInput(List.of("e1"), null, FightInput.DEFAULT_RADIUS, null, Permissions.DEFAULT),
                withCreeper, targets, moves, (target, permissions) -> refusal);
        targets.now = new SeenTargets.Observed(12, 64, 5, 13, false);
        fight.start(new Tick(0));
        for (long t = 0; t < 10; t++) fight.tick(new Tick(t));

        assertEquals(List.of("走向 -9.0,0.0"), moves.started);
    }

    @Test
    void namedTargetOutOfReachIsApproachedWhereItStandsWithOneWalk() {
        // 僵尸站在 (12, 64, 5) 不动：朝它本人走过去，只开一次走到，不每刻重新寻路。
        targets.now = new SeenTargets.Observed(12, 64, 5, 13, false);
        run(10);

        assertEquals(List.of("走向 12.0,5.0"), moves.started);
        assertEquals(0, moves.closed);
    }

    @Test
    void namedTargetDefeatedThenItsDropIsPickedUpRightThere() {
        // 实机：点名打倒一只羊后结果写"没捡到掉落物"，羊肉留在地上——点名打完直接收场，没走拾荒这一步。
        targets.now = new SeenTargets.Observed(1, 64, 1, 1.5, false);
        task.start(new Tick(0));
        task.tick(new Tick(0));
        task.tick(new Tick(1));
        task.tick(new Tick(2));
        targets.now = new SeenTargets.Observed(1, 64, 1, 1.5, true);
        moves.drops.add(new FightMoves.Drop(42, "minecraft:mutton", 0.5, 64, 0.5));
        TaskResult result = null;
        for (long t = 3; t < 40 && result == null; t++) {
            // 走到跟前两刻后被原版吸进包：地上那件没了，背包多一件。
            if (t == 6) {
                moves.drops.clear();
                moves.carried = 1;
            }
            if (task.tick(new Tick(t)) instanceof TickResult.Finished finished) result = finished.result();
        }

        assertNotNull(result);
        FightTask.FightDetails details = (FightTask.FightDetails) result.details();
        assertEquals(List.of("minecraft:mutton"), details.lootGained(), result.summary());
    }

    @Test
    void namedTargetSeenDyingCountsAsDefeated() {
        // 打到倒地（死亡动画里还查得到、但已不算活着）：这就是击败的证据。
        targets.now = new SeenTargets.Observed(1, 64, 1, 1.5, false);
        task.start(new Tick(0));
        task.tick(new Tick(0));
        task.tick(new Tick(1));
        task.tick(new Tick(2));
        targets.now = new SeenTargets.Observed(1, 64, 1, 1.5, true);
        TaskResult result = run(20);

        assertNotNull(result);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        FightTask.FightDetails details = (FightTask.FightDetails) result.details();
        assertEquals(1, details.defeated().size());
        assertTrue(details.lostTrack().isEmpty());
    }

    @Test
    void namedTargetOwnedBySomeoneNeedsAnotherConfirmationBeforeAnyMove() {
        // 点名的是别人的狗：点了名也要再确认一次，许可没开到 any 就不走过去、不出手。
        refusal = Optional.of(Problem.of(Problem.Kind.NEED_APPROVAL, "是有主的", "把 fight 设为 any"));
        targets.now = new SeenTargets.Observed(1, 64, 1, 1.5, false);
        TaskResult result = run(5);

        assertNotNull(result);
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NEED_APPROVAL, result.problem().kind());
        assertTrue(moves.started.isEmpty(), moves.started.toString());
    }

    @Test
    void namedTargetThatVanishesIsNotCountedAsDefeated() {
        // 打着打着不见了（走远或被别的东西打死）：不算击败，以没做成收场并说明跟丢了。
        targets.now = new SeenTargets.Observed(1, 64, 1, 1.5, false);
        task.start(new Tick(0));
        task.tick(new Tick(0));
        task.tick(new Tick(1));
        task.tick(new Tick(2));
        targets.now = null;
        TaskResult result = run(20);

        assertNotNull(result);
        assertEquals(TaskResult.Status.FAILED, result.status(), result.summary());
        assertEquals(Problem.Kind.TARGET_GONE, result.problem().kind());
        FightTask.FightDetails details = (FightTask.FightDetails) result.details();
        assertTrue(details.defeated().isEmpty(), details.defeated().toString());
        assertEquals(List.of("minecraft:zombie（实体 7）"), details.lostTrack());
    }
}
