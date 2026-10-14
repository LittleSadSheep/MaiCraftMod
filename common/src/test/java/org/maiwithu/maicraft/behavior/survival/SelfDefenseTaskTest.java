// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.combat.BattleMoves;
import org.maiwithu.maicraft.behavior.combat.CombatSenses;
import org.maiwithu.maicraft.behavior.combat.Footwork;
import org.maiwithu.maicraft.behavior.combat.ThreatAssessment;
import org.maiwithu.maicraft.behavior.combat.WeaponChoice;
import org.maiwithu.maicraft.behavior.inventory.PicksUpDrops;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 自卫插曲：打完观察一阵再走回打点；撤离甩开记完成、人不在原地不让主任务悄悄续上；
 * 不追出打点 16 格去打没在打我的；回不去打点如实说停在哪；走不到又不打我的半分钟内不再为它插进来。
 */
class SelfDefenseTaskTest {

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

        @Override public List<Attacker> recentAttackers(TickContext context) { return attackers; }

        @Override public CombatProfile profile(TickContext context) {
            return new CombatProfile(health, 0, Optional.of(new WeaponChoice.Picked("minecraft:iron_sword",
                    WeaponChoice.Weapon.MELEE)), 4);
        }

        @Override public Entity entityById(TickContext context, int entityId) { return null; }
    }

    private static final class Moves implements BattleMoves {
        final double[] at = {0, 64, 0};
        int strikes;
        int walks;
        ActionStatus walkStatus = ActionStatus.progressed();
        boolean night;

        @Override public boolean night(TickContext context) { return night; }

        @Override public Action strike(TickContext context, int entityId) {
            return new Fixed(() -> {
                strikes++;
                return ActionStatus.done();
            });
        }

        @Override public Action shoot(TickContext context, int entityId) { return new Fixed(ActionStatus::running); }
        @Override public boolean canShoot(TickContext context) { return false; }
        @Override public boolean hasMeleeWeapon(TickContext context) { return true; }
        @Override public boolean attackReady(TickContext context) { return true; }
        @Override public void guard(TickContext context, double distance, boolean aboutToStrike) {}
        @Override public void lowerGuard() {}
        @Override public Footwork.Ground ground(TickContext context) { return (dx, dz) -> Footwork.Footing.FLAT; }
        @Override public void step(TickContext context, int faceEntityId, Footwork.Step step) {}

        @Override public Action walk(GoalCompiler.Compiled goal) {
            walks++;
            return new Fixed(() -> walkStatus);
        }

        @Override public double[] selfPosition(TickContext context) { return at.clone(); }
        @Override public boolean standable(TickContext context, int x, int y, int z) { return true; }
        @Override public String mainHand(TickContext context) { return "minecraft:iron_sword"; }
    }

    private record Fixed(Supplier<ActionStatus> status) implements Action {
        @Override public ActionStatus tick(TickContext context) { return status.get(); }
        @Override public String describe() { return "替身"; }
    }

    private static final class Events implements TaskEventSink {
        final List<String> messages = new ArrayList<>();

        @Override public void publish(TaskEvent.Kind kind, String message) {
            messages.add(kind + " " + message);
        }
    }

    /** 东边 x 格处、看得见、亮着攻击标记的僵尸。 */
    private static CombatSenses.Threat zombie(int id, double x, double distance) {
        return new CombatSenses.Threat(id, UUID.randomUUID(), "minecraft:zombie", x, 64, 0, distance,
                ThreatAssessment.Kind.MELEE, false, true, true);
    }

    private static TickResult run(SelfDefenseTask task, long from, long until) {
        TickResult result = null;
        for (long tick = from; tick < until; tick++) {
            result = task.tick(new Tick(tick));
            if (result instanceof TickResult.Finished) return result;
        }
        return result;
    }

    @Test
    void fightsThenWatchesThenReturnsToTheSpot() {
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 2, 2));
        Moves moves = new Moves();
        Events events = new Events();
        SelfDefenseNeed need = new SelfDefenseNeed(senses, moves, events);
        SelfDefenseTask task = (SelfDefenseTask) need.createTask(new Tick(0));
        task.start(new Tick(0));
        run(task, 0, 3);
        assertTrue(moves.strikes > 0, "够得着就打");
        senses.threats.clear();
        TickResult result = run(task, 3, 3 + SelfDefenseTask.WATCH_TICKS + 5);
        TaskResult finished = assertInstanceOf(TickResult.Finished.class, result).result();
        assertEquals(TaskResult.Status.DONE, finished.status());
        assertTrue(finished.summary().contains("回到打点"));
        assertFalse(task.cannotResumeInPlace());
        assertTrue(events.messages.get(0).contains("插入自卫"), events.messages.toString());
        // 收场交代拿什么打、挥中几下（替身世界里看不到实体倒下，不写打掉了谁）。
        String last = events.messages.get(events.messages.size() - 1);
        assertTrue(last.contains("拿 minecraft:iron_sword 挥中 " + moves.strikes + " 下"), last);
        var details = assertInstanceOf(SelfDefenseTask.DefenseDetails.class, finished.details());
        assertEquals(moves.strikes, details.hits());
        assertEquals("minecraft:iron_sword", details.weapon());
    }

    @Test
    void afterAFightItPicksUpWhatTheFightDroppedBeforeWalkingBack() {
        // 开打时脚边已经躺着 7 号：不是这一仗的。打完观察一阵没新威胁，捡这一仗新掉出来的（替身两刻捡完），再回打点。
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 2, 2));
        Moves moves = new Moves();
        Events events = new Events();
        List<Set<Integer>> asked = new ArrayList<>();
        int[] pickTicks = {0};
        PicksUpDrops drops = new PicksUpDrops() {
            @Override public Set<Integer> nearby() { return Set.of(7); }

            @Override public Action pickUpNewSince(Set<Integer> before) {
                asked.add(before);
                return new Fixed(() -> ++pickTicks[0] >= 2 ? ActionStatus.done() : ActionStatus.progressed());
            }
        };
        SelfDefenseNeed need = new SelfDefenseNeed(senses, moves, events).pickingUp(drops);
        SelfDefenseTask task = (SelfDefenseTask) need.createTask(new Tick(0));
        task.start(new Tick(0));
        run(task, 0, 3);
        senses.threats.clear();
        TickResult result = run(task, 3, 3 + SelfDefenseTask.WATCH_TICKS + 10);
        assertEquals(TaskResult.Status.DONE, assertInstanceOf(TickResult.Finished.class, result).result().status());
        assertEquals(List.of(Set.of(7)), asked, "只捡一次，开打前就躺着的不算");
        assertEquals(2, pickTicks[0]);
    }

    @Test
    void escapingCountsAsHandledAndKeepsTheMainTaskParked() {
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 6, 6));
        senses.health = 4;
        Moves moves = new Moves();
        Events events = new Events();
        SelfDefenseNeed need = new SelfDefenseNeed(senses, moves, events);
        SelfDefenseTask task = (SelfDefenseTask) need.createTask(new Tick(0));
        task.start(new Tick(0));
        run(task, 0, 2);
        assertEquals(1, moves.walks, "血快见底，迈开腿撤");
        moves.at[0] = -30;
        senses.threats.clear();
        TaskResult finished = assertInstanceOf(TickResult.Finished.class, run(task, 2, 5)).result();
        assertEquals(TaskResult.Status.DONE, finished.status(), "甩开了追兵就是这次被攻击处理好了");
        assertTrue(finished.summary().contains("已撤离") && finished.summary().contains("血快见底"), finished.summary());
        assertTrue(task.cannotResumeInPlace(), "人已经不在开打的原地");
    }

    @Test
    void doesNotChaseOutOfTheLeashUnlessItIsHittingMe() {
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 3, 3));
        Moves moves = new Moves();
        SelfDefenseNeed need = new SelfDefenseNeed(senses, moves, TaskEventSink.NONE);
        SelfDefenseTask task = (SelfDefenseTask) need.createTask(new Tick(0));
        task.start(new Tick(0));
        task.tick(new Tick(0));
        // 追着打远了：人到了打点东边 10 格，又一只在打点 22 格外冲我吼，但没动手——守住，不追。
        moves.at[0] = 10;
        CombatSenses.Threat far = zombie(2, 22, 12);
        senses.threats.set(0, far);
        int before = moves.walks;
        run(task, 1, 4);
        assertEquals(before, moves.walks, "出了追击圈、又没打我的不追");
        // 它真打了我：照打，不受追击圈限制。
        senses.attackers.add(new CombatSenses.Attacker(far.uuid(), "minecraft:zombie", 4, "被攻击"));
        run(task, 4, 6);
        assertTrue(moves.walks > before, "正压着我打的照追");
    }

    @Test
    void aNewThreatOnTheWayBackIsFoughtNotIgnored() {
        // 打完往回走（离打点 8 格，路还在走），又冲过来一只僵尸：回去应对，不闷头往回走。
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 2, 2));
        Moves moves = new Moves();
        SelfDefenseNeed need = new SelfDefenseNeed(senses, moves, new Events());
        SelfDefenseTask task = (SelfDefenseTask) need.createTask(new Tick(0));
        task.start(new Tick(0));
        task.tick(new Tick(0));
        senses.threats.clear();
        moves.at[0] = 8;
        run(task, 1, SelfDefenseTask.WATCH_TICKS + 5);
        int strikesBefore = moves.strikes;
        senses.threats.add(zombie(2, 9, 1));
        run(task, SelfDefenseTask.WATCH_TICKS + 5, SelfDefenseTask.WATCH_TICKS + 10);
        assertTrue(moves.strikes > strikesBefore, "走回去的路上又被追上：照打");
    }

    @Test
    void cannotWalkBackSaysWhereItStopped() {
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 2, 2));
        Moves moves = new Moves();
        Events events = new Events();
        SelfDefenseNeed need = new SelfDefenseNeed(senses, moves, events);
        SelfDefenseTask task = (SelfDefenseTask) need.createTask(new Tick(0));
        task.start(new Tick(0));
        task.tick(new Tick(0));
        senses.threats.clear();
        moves.at[0] = 8;
        moves.walkStatus = ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "掉进坑里了"));
        TaskResult finished = assertInstanceOf(TickResult.Finished.class,
                run(task, 1, SelfDefenseTask.WATCH_TICKS + 10)).result();
        assertTrue(finished.summary().contains("回不去"), finished.summary());
        assertTrue(task.cannotResumeInPlace());
        assertTrue(events.messages.stream().anyMatch(m -> m.contains("主任务已暂停")), events.messages.toString());
    }

    /** 东边 x 格处、刚打过角色的另一位玩家。 */
    private static CombatSenses.Threat player(int id, UUID uuid, double x) {
        return new CombatSenses.Threat(id, uuid, "minecraft:player", x, 64, 0, Math.abs(x),
                ThreatAssessment.Kind.PLAYER, false, true, false, 0.3, Math.abs(x) - 0.3, Math.abs(x) < 3.3, 3.3, false);
    }

    @Test
    void aStrangerHittingMeIsNotHitBackAndTheMainTaskWaits() {
        Senses senses = new Senses();
        UUID stranger = UUID.randomUUID();
        senses.threats.add(player(8, stranger, 2.5));
        senses.attackers.add(new CombatSenses.Attacker(stranger, "minecraft:player", 0, "player"));
        Moves moves = new Moves();
        Events events = new Events();
        SelfDefenseNeed need = new SelfDefenseNeed(senses, moves, events);
        assertEquals(Urgency.NOW, need.urgency(new Tick(0)), "他够得着我：立刻退开");
        SelfDefenseTask task = (SelfDefenseTask) need.createTask(new Tick(0));
        task.start(new Tick(0));
        run(task, 0, 5);
        assertEquals(0, moves.strikes, "没点名的玩家不自动还手");
        // 他停手、走了：不走回去接着干活，停下来等 LLM 决定。
        senses.threats.clear();
        senses.attackers.clear();
        TaskResult finished = assertInstanceOf(TickResult.Finished.class, run(task, 5, 10)).result();
        assertTrue(finished.summary().contains("没有还手"), finished.summary());
        assertTrue(task.cannotResumeInPlace(), "主任务暂停等指示");
        assertTrue(events.messages.stream().anyMatch(m -> m.contains("fight 点名")), events.messages.toString());
    }

    @Test
    void cornedAtNightDigsInUntilDawnThenGoesBack() {
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 6, 6));
        senses.health = 4;
        Moves moves = new Moves();
        moves.walkStatus = ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "死路"));
        moves.night = true;
        int[] dug = {0};
        // 挖三填一的替身：挖坑封顶熬三刻就天亮。
        NightfallNeed.BurrowMoves shelter = () -> new Task() {
            int ticks;

            @Override public TickResult tick(TickContext context) {
                dug[0]++;
                return ++ticks < 3 ? TickResult.RUNNING
                        : TickResult.finished(TaskResult.done("熬过了夜，回到了地面"));
            }

            @Override public TaskResult close(CloseReason reason) { return TaskResult.done("收尾"); }
            @Override public void pause() {}
            @Override public String describe() { return "挖三填一"; }
        };
        SelfDefenseNeed need = new SelfDefenseNeed(senses, moves, TaskEventSink.NONE, uuid -> false, shelter);
        SelfDefenseTask task = (SelfDefenseTask) need.createTask(new Tick(0));
        task.start(new Tick(0));
        run(task, 0, 6);
        assertTrue(dug[0] > 0, "撤不掉又打不过、天黑：挖坑自保");
        // 天亮爬回地面，怪也散了：观察一阵、走回打点收场。
        senses.threats.clear();
        TaskResult finished = assertInstanceOf(TickResult.Finished.class,
                run(task, 6, 6 + SelfDefenseTask.WATCH_TICKS + 10)).result();
        assertEquals(TaskResult.Status.DONE, finished.status(), finished.summary());
    }

    @Test
    void trustedPlayersHittingMeOnlyMakeAnEvent() {
        Senses senses = new Senses();
        UUID friend = UUID.randomUUID();
        senses.threats.add(player(8, friend, 2.5));
        senses.attackers.add(new CombatSenses.Attacker(friend, "minecraft:player", 0, "player"));
        SelfDefenseNeed need = new SelfDefenseNeed(senses, new Moves(), TaskEventSink.NONE, friend::equals);
        assertNull(need.urgency(new Tick(0)), "自家人打我多半是玩笑，不停工");
    }

    @Test
    void unreachableFoeThatNeverHitMeIsIgnoredForAWhile() {
        // 隔着坑冲我吼的僵尸：自卫走不到它，收场后半分钟内它不动手就不再为它插进来。
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 10, 10));
        Moves moves = new Moves();
        moves.walkStatus = ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "隔着坑"));
        SelfDefenseNeed need = new SelfDefenseNeed(senses, moves, TaskEventSink.NONE);
        assertEquals(Urgency.SOON, need.urgency(new Tick(0)));
        SelfDefenseTask task = (SelfDefenseTask) need.createTask(new Tick(0));
        task.start(new Tick(0));
        TickResult result = run(task, 0, SelfDefenseTask.WATCH_TICKS + 10);
        assertInstanceOf(TickResult.Finished.class, result);
        assertNull(need.urgency(new Tick(SelfDefenseTask.WATCH_TICKS + 20)), "记着它走不到，不再插");
        assertEquals(Urgency.SOON,
                need.urgency(new Tick(SelfDefenseNeed.IGNORE_UNREACHABLE_TICKS + 100)), "过了半分钟重新看");
    }
}
