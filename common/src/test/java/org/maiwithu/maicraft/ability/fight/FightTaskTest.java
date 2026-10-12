// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.combat.Footwork;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.combat.CombatSenses;
import org.maiwithu.maicraft.behavior.combat.ThreatAssessment;
import org.maiwithu.maicraft.behavior.combat.WeaponChoice;
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

    /** 感观替身：满血拿剑，周围没有别的威胁；点名的僵尸按核对替身此刻的观察给身板。 */
    private final class Senses implements CombatSenses {
        @Override public List<Threat> threats(TickContext context, double radius) { return List.of(); }

        @Override public Optional<Threat> living(TickContext context, int entityId) {
            return namedZombie(entityId);
        }

        @Override public List<Attacker> recentAttackers(TickContext context) { return List.of(); }

        @Override public CombatProfile profile(TickContext context) {
            return new CombatProfile(20, 10,
                    Optional.of(new WeaponChoice.Picked("minecraft:iron_sword", WeaponChoice.Weapon.MELEE)), 4);
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
        /** 此刻主手里拿着什么；测试摆。 */
        String held = "minecraft:iron_sword";

        @Override public String mainHand(TickContext context) {
            return held;
        }

        final List<String> started = new ArrayList<>();
        int closed;
        /** 开打前就躺在身边的掉落物；测试摆。 */
        final Set<Integer> lyingBefore = new HashSet<>();
        /** 捡掉落物时交给它的"不碰"名单。 */
        Set<Integer> pickupSkipped;
        /** 服务器说本玩家收进包的（开打后才冒出来的）；测试摆。 */
        final List<Gained> gained = new ArrayList<>();

        @Override public Action strike(TickContext context, int entityId) {
            return action("打 " + entityId, ActionStatus.done());
        }

        @Override public Action walk(GoalCompiler.Compiled goal) {
            BlockPos center = goal.goal().center();
            return action("走向 " + center.getX() + "," + center.getZ(), ActionStatus.running());
        }

        final List<Footwork.Step> steps = new ArrayList<>();

        @Override public Action shoot(TickContext context, int entityId) { return action("射 " + entityId, ActionStatus.running()); }
        @Override public boolean canShoot(TickContext context) { return false; }
        @Override public boolean hasMeleeWeapon(TickContext context) { return true; }
        @Override public boolean attackReady(TickContext context) { return true; }
        @Override public void guard(TickContext context, double distance, boolean aboutToStrike) {}
        @Override public void lowerGuard() {}
        @Override public Footwork.Ground ground(TickContext context) { return (dx, dz) -> Footwork.Footing.FLAT; }
        @Override public void step(TickContext context, int faceEntityId, Footwork.Step step) { steps.add(step); }
        @Override public boolean standable(TickContext context, int x, int y, int z) { return true; }

        @Override public double[] selfPosition(TickContext context) { return new double[] {0, 64, 0}; }

        @Override public long dropCursor(TickContext context) { return 0; }

        @Override public Set<Integer> dropsLyingBefore(TickContext context, long cursor) { return lyingBefore; }

        @Override public Action pickUpNewSince(Set<Integer> before) {
            pickupSkipped = before;
            return action("捡掉落物", ActionStatus.done());
        }

        @Override public List<Gained> pickedUpSince(TickContext context, long cursor) { return gained; }

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

    // 点名的 7 号僵尸此刻的身板：核对替身说它在哪、看不看得见；倒下或不见了就不在场上。
    private Optional<CombatSenses.Threat> namedZombie(int entityId) {
        SeenTargets.Observed now = targets.now;
        if (entityId != 7 || now == null || now.dead()) return Optional.empty();
        return Optional.of(new CombatSenses.Threat(7, new UUID(0, 7), "minecraft:zombie", now.x(), now.y(), now.z(),
                now.distance(), ThreatAssessment.Kind.MELEE, false, now.visible(), false));
    }
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
        // 点名打僵尸，旁边一只点着引信的苦力怕离 4 格：当刻背离它疾跑，不朝僵尸走。
        CombatSenses withCreeper = new CombatSenses() {
            @Override public List<Threat> threats(TickContext context, double radius) {
                return List.of(new Threat(9, new UUID(0, 9), "minecraft:creeper", 4, 64, 0, 4,
                        ThreatAssessment.Kind.EXPLOSIVE, true, true, true));
            }
            @Override public List<Attacker> recentAttackers(TickContext context) { return List.of(); }
            @Override public CombatProfile profile(TickContext context) {
                return new CombatProfile(20, 10,
                        Optional.of(new WeaponChoice.Picked("minecraft:iron_sword", WeaponChoice.Weapon.MELEE)), 4);
            }
            @Override public Entity entityById(TickContext context, int entityId) { return null; }
            @Override public Optional<Threat> living(TickContext context, int entityId) { return namedZombie(entityId); }
        };
        FightTask fight = new FightTask(
                new FightInput(List.of("e1"), null, FightInput.DEFAULT_RADIUS, null, Permissions.DEFAULT),
                withCreeper, targets, moves, (target, permissions) -> refusal);
        targets.now = new SeenTargets.Observed(12, 64, 5, 13, false);
        fight.start(new Tick(0));
        for (long t = 0; t < 10; t++) fight.tick(new Tick(t));

        assertTrue(moves.started.isEmpty(), "先跑后算，不等寻路：" + moves.started);
        assertTrue(moves.steps.get(0).sprint() && moves.steps.get(0).dx() < 0, "背离苦力怕疾跑：" + moves.steps);
    }

    @Test
    void namedTargetOutOfReachIsApproachedWhereItStandsWithOneWalk() {
        // 僵尸站在 (12, 64, 5) 不动：朝它本人走过去，只开一次走到，不每刻重新寻路。
        targets.now = new SeenTargets.Observed(12, 64, 5, 13, false);
        run(10);

        assertEquals(List.of("走向 12,5"), moves.started);
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
        // 开打前脚边就躺着一件别人丢的：不是这一仗的，不去碰；羊肉是这一仗掉的，服务器说收进了包。
        moves.lyingBefore.add(41);
        moves.gained.add(new FightMoves.Gained("minecraft:mutton", 1));
        TaskResult result = null;
        for (long t = 3; t < 40 && result == null; t++) {
            if (task.tick(new Tick(t)) instanceof TickResult.Finished finished) result = finished.result();
        }

        assertNotNull(result);
        assertTrue(moves.started.contains("捡掉落物"), "拾荒交给共用的捡掉落物：" + moves.started);
        assertEquals(Set.of(41), moves.pickupSkipped, "开打前就躺着的不碰");
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
