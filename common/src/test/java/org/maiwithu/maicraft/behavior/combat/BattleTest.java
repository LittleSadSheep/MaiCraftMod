// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.calc.ApproachAvoiding;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 战斗引擎：贴脸退着打、近处走位不寻路、远处绕怪寻路、走不到就换、躲爆炸疾跑、撤离到甩开、退无可退背水一战、能射就射。 */
class BattleTest {

    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            return null;
        }
    }

    private static final class Senses implements CombatSenses {
        List<Threat> threats = new ArrayList<>();
        List<Attacker> attackers = new ArrayList<>();
        double health = 20;

        @Override public List<Threat> threats(TickContext context, double radius) {
            return threats.stream().filter(threat -> threat.distance() <= radius).toList();
        }

        @Override public List<Attacker> recentAttackers(TickContext context) {
            return attackers;
        }

        @Override public CombatProfile profile(TickContext context) {
            return new CombatProfile(health, 0, Optional.of(new WeaponChoice.Picked("minecraft:iron_sword",
                    WeaponChoice.Weapon.SWORD_OR_AXE)), 4);
        }

        @Override public Entity entityById(TickContext context, int entityId) {
            return null;
        }
    }

    private static final class Moves implements BattleMoves {
        boolean bow;
        int strikes;
        int shots;
        final List<Footwork.Step> steps = new ArrayList<>();
        final List<GoalCompiler.Compiled> walks = new ArrayList<>();
        ActionStatus walkStatus = ActionStatus.progressed();

        @Override public Action strike(TickContext context, int entityId) {
            return new Instant(() -> {
                strikes++;
                return ActionStatus.done();
            });
        }

        @Override public Action shoot(TickContext context, int entityId) {
            return new Instant(() -> {
                shots++;
                return ActionStatus.running();
            });
        }

        @Override public boolean canShoot(TickContext context) { return bow; }
        @Override public boolean hasMeleeWeapon(TickContext context) { return true; }
        @Override public boolean attackReady(TickContext context) { return true; }
        @Override public void guard(TickContext context, double distance, boolean aboutToStrike) {}
        @Override public void lowerGuard() {}
        @Override public Footwork.Ground ground(TickContext context) { return (dx, dz) -> Footwork.Footing.FLAT; }
        @Override public void step(TickContext context, int faceEntityId, Footwork.Step step) { steps.add(step); }

        @Override public Action walk(GoalCompiler.Compiled goal) {
            walks.add(goal);
            return new Instant(() -> walkStatus);
        }

        @Override public double[] selfPosition(TickContext context) { return new double[] {0, 64, 0}; }
        @Override public boolean standable(TickContext context, int x, int y, int z) { return true; }
        @Override public String mainHand(TickContext context) { return "minecraft:iron_sword"; }

        Footwork.Step lastStep() {
            return steps.get(steps.size() - 1);
        }
    }

    private record Instant(Supplier<ActionStatus> status) implements Action {
        @Override public ActionStatus tick(TickContext context) { return status.get(); }
        @Override public String describe() { return "替身"; }
    }

    /** 在东边 x 格处、看得见、亮着攻击标记的僵尸。 */
    private static CombatSenses.Threat zombie(int id, double x) {
        return new CombatSenses.Threat(id, UUID.randomUUID(), "minecraft:zombie", x, 64, 0, Math.abs(x),
                ThreatAssessment.Kind.MELEE, false, true, true, 0.3, Math.max(0, Math.abs(x) - 0.3),
                Math.abs(x) < 1.43, 2.02, false);
    }

    private static CombatSenses.Threat creeper(double x, boolean armed) {
        return new CombatSenses.Threat(9, UUID.randomUUID(), "minecraft:creeper", x, 64, 0, Math.abs(x),
                ThreatAssessment.Kind.EXPLOSIVE, armed, true, false, 0.3, Math.abs(x) - 0.3, false, 3.0, false);
    }

    /** 自卫的名单：追我的都打。 */
    private static final Battlefield.Roster ANYONE_AFTER_ME = (threat, attacker, chasing) -> chasing;

    private static Battle battle(Senses senses, Moves moves) {
        return new Battle(senses, moves, ANYONE_AFTER_ME, ThreatAssessment.VIGILANCE_RADIUS);
    }

    @Test
    void tooCloseStrikesAndBacksOffAtTheSameTime() {
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 1.5));
        Moves moves = new Moves();
        Battle.Moment moment = battle(senses, moves).tick(new Tick(0));
        assertEquals(Battle.State.FIGHTING, moment.state());
        assertEquals(1, moves.strikes, "够得着就挥");
        assertTrue(moves.lastStep().dx() < 0, "同一刻往西退出它够得着的地方，实际 " + moves.lastStep());
        assertTrue(moves.walks.isEmpty(), "近处不寻路");
    }

    @Test
    void nearbyTargetIsApproachedByFootworkAndFarOneByPathfinding() {
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 5));
        Moves moves = new Moves();
        battle(senses, moves).tick(new Tick(0));
        assertTrue(moves.lastStep().dx() > 0, "五格外看得见：直接往前凑");
        assertTrue(moves.walks.isEmpty());

        Senses far = new Senses();
        far.threats.add(zombie(1, 12));
        far.threats.add(zombie(2, -12));
        Moves walking = new Moves();
        battle(far, walking).tick(new Tick(0));
        assertEquals(1, walking.walks.size(), "远处交给寻路");
        assertInstanceOf(ApproachAvoiding.class, walking.walks.get(0).goal(), "寻路要绕开别的怪");
    }

    @Test
    void unreachableTargetIsDroppedWhenThereIsNoBow() {
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 12));
        Moves moves = new Moves();
        moves.walkStatus = ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "隔着悬崖"));
        Battle fight = battle(senses, moves);
        fight.tick(new Tick(0));
        Battle.Moment next = fight.tick(new Tick(1));
        assertEquals(Battle.State.FINISHED, next.state(), "走不到、不能射、又没打我：不再追，收手");
        assertTrue(fight.unreachable().contains(1), "记着它走不到，交代给调用方");
    }

    @Test
    void armedCreeperIsEvadedBySprintingBeforeAnythingElse() {
        Senses senses = new Senses();
        senses.threats.add(creeper(3, true));
        senses.health = 4;
        Moves moves = new Moves();
        Battle.Moment moment = battle(senses, moves).tick(new Tick(0));
        assertEquals(Battle.State.EVADING, moment.state(), "血线破了也先躲爆炸");
        assertTrue(moves.lastStep().sprint() && moves.lastStep().dx() < 0, "当刻背离它疾跑，不等寻路");
        assertEquals(0, moves.strikes);
        assertTrue(moves.walks.isEmpty());
    }

    @Test
    void lowHealthRetreatsUntilClearAndReportsEscape() {
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 6));
        senses.health = 4;
        Moves moves = new Moves();
        Battle fight = battle(senses, moves);
        Battle.Moment running = fight.tick(new Tick(0));
        assertEquals(Battle.State.RETREATING, running.state());
        assertEquals(1, moves.walks.size());
        assertTrue(moves.walks.get(0).goal().center().getX() < -20, "往背离僵尸的西边跑，实际 "
                + moves.walks.get(0).goal().center());
        // 回了血也不回头；僵尸被甩到三十二格外才算甩开。
        senses.health = 20;
        assertEquals(Battle.State.RETREATING, fight.tick(new Tick(1)).state());
        senses.threats.clear();
        Battle.Moment escaped = fight.tick(new Tick(2));
        assertEquals(Battle.State.ESCAPED, escaped.state());
        assertEquals("血快见底，先撤离保命", escaped.retreatWhy());
    }

    @Test
    void threeDeadEndsMeanALastStand() {
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 6));
        senses.health = 4;
        Moves moves = new Moves();
        moves.walkStatus = ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "死路"));
        Battle fight = battle(senses, moves);
        Battle.Moment last = null;
        for (int tick = 0; tick < 3; tick++) last = fight.tick(new Tick(tick));
        assertTrue(last.cornered(), "连着三次走不通退无可退");
        assertEquals(Battle.State.FIGHTING, fight.tick(new Tick(3)).state(), "背水一战");
    }

    @Test
    void shootsFromAfarAndFinishesWhenClear() {
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 10));
        Moves moves = new Moves();
        moves.bow = true;
        Battle fight = battle(senses, moves);
        assertEquals(Battle.State.SHOOTING, fight.tick(new Tick(0)).state());
        assertEquals(1, moves.shots);
        senses.threats.clear();
        assertEquals(Battle.State.FINISHED, fight.tick(new Tick(1)).state());
        assertFalse(fight.retreating());
    }
}
