// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.world.entity.Entity;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自卫的兜底撤离：攻击推进连续两次 600 刻没有真实进展（攻击通道失灵，出手一直没被确认），
 * 或血量跌破拒战线，就从"再打"升级为"撤离"，以"打不出去，已撤离"如实收场。
 * 一条撤离路线走不通不算没有退路：换方向、隔一秒再试；连续走不通到上限才承认退无可退，
 * 回去背水一战——不再把一次寻路失败当成整场撤离的死刑。
 */
class SelfDefenseRetreatTest {

    /** 空转多少刻转撤离，与卡住判定同一刻数。 */
    private static final long STALL_TICKS = 20L * 30;

    @Test
    void attackChannelThatNeverFiresEscalatesToRetreat() {
        // 出手一直没被游戏确认（推进无进展）：空转满一次再给一次机会，第二次满 600 刻就撤。
        FakeSenses senses = new FakeSenses();
        senses.threats = List.of(zombie(2));
        FakeMoves moves = new FakeMoves();
        moves.strikeOutcome = ActionStatus.running();
        RecordingEvents events = new RecordingEvents();
        SelfDefenseNeed need = new SelfDefenseNeed(senses, moves, events);
        SelfDefenseTask task = (SelfDefenseTask) need.createTask(null);
        task.start(new Tick(0));

        // 两次空转之后第三刻威胁散了：模拟走到怪追不上。
        TickResult result = drive(task, senses, 2L * STALL_TICKS + 3, 4L * STALL_TICKS + 10);

        assertTrue(moves.walkCalls >= 1, "撤离阶段真的迈开腿走了");
        assertTrue(moves.strikeTicks > STALL_TICKS, "撤离之前确实一直在试着出手");
        assertTrue(result instanceof TickResult.Finished finished, "撤离后任务收场，实际 " + result);
        assertEquals(TaskResult.Status.FAILED, ((TickResult.Finished) result).result().status());
        assertTrue(((TickResult.Finished) result).result().summary().contains("已撤离"),
                "收场说明里带着撤离事实");
        assertTrue(task.cannotResumeInPlace(), "人已经不在开打的原地，主任务不能在原地接着做");
        assertTrue(events.messages.stream().anyMatch(m -> m.contains("打不出去，已撤离")),
                "事件里如实报告：" + events.messages);
    }

    @Test
    void healthBelowRetreatLineRetreatsRightAway() {
        // 血快见底：不等空转两次，马上转撤离，一下手都不出。
        FakeSenses senses = new FakeSenses();
        senses.threats = List.of(zombie(2));
        senses.health = 4;
        FakeMoves moves = new FakeMoves();
        RecordingEvents events = new RecordingEvents();
        SelfDefenseNeed need = new SelfDefenseNeed(senses, moves, events);
        SelfDefenseTask task = (SelfDefenseTask) need.createTask(null);
        task.start(new Tick(0));

        TickResult result = drive(task, senses, 3, 10);

        assertEquals(0, moves.strikeCalls, "血快见底不出手");
        assertTrue(moves.walkCalls >= 1, "直接迈开腿撤离");
        assertTrue(result instanceof TickResult.Finished finished, "甩掉威胁后收场，实际 " + result);
        assertTrue(((TickResult.Finished) result).result().summary().contains("已撤离"));
    }

    @Test
    void retreatThatCannotWalkTriesAnotherDirectionBeforeGivingUp() {
        // 撤离路线走不通：不一次失败就判死，换方向重开；连续三次走不通才承认退无可退，回去迎战。
        FakeSenses senses = new FakeSenses();
        senses.threats = List.of(zombie(2));
        FakeMoves moves = new FakeMoves();
        moves.strikeOutcome = ActionStatus.running();
        moves.walkOutcome = ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "四面都是墙"));
        RecordingEvents events = new RecordingEvents();
        SelfDefenseNeed need = new SelfDefenseNeed(senses, moves, events);
        SelfDefenseTask task = (SelfDefenseTask) need.createTask(null);
        task.start(new Tick(0));

        // 两次空转转撤离，再给足三次失败加上各自的每秒一次重开间隔；任务不该收场，而是回头迎战。
        TickResult result = drive(task, senses, Long.MAX_VALUE, 2L * STALL_TICKS + 600);

        assertTrue(result == null, "撤离走不通不该一次失败就收场，实际 " + result);
        assertTrue(moves.walkCalls >= ThreatAssessment.ESCAPE_FAILURES_BEFORE_LAST_STAND,
                "走不通会重开撤离路线，实际走了 " + moves.walkCalls + " 次");
        assertTrue(events.messages.stream().anyMatch(m -> m.contains("退无可退")),
                "连续走不通才声明退无可退：" + events.messages);
        assertTrue(moves.strikeTicks > 0, "退无可退之后回到战斗，就地迎战");
    }

    @Test
    void realMovementWhileRetreatingResetsTheFailureAccount() {
        // 撤离路上有真实位移（甩开了上一个死路）：失败账清零，不会把一路的失败累成退无可退。
        FakeSenses senses = new FakeSenses();
        senses.threats = List.of(zombie(2));
        FakeMoves moves = new FakeMoves();
        moves.strikeOutcome = ActionStatus.running();
        moves.walkOutcome = ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "前面走不过去"));
        RecordingEvents events = new RecordingEvents();
        SelfDefenseNeed need = new SelfDefenseNeed(senses, moves, events);
        SelfDefenseTask task = (SelfDefenseTask) need.createTask(null);
        task.start(new Tick(0));

        // 每次撤离失败后都往前挪：反复走不通也始终不认"退无可退"。
        TickResult result = driveWhileMoving(task, senses, moves, 2L * STALL_TICKS + 800);

        assertTrue(result == null, "一直有真实位移就不该判退无可退，实际 " + result);
        assertTrue(moves.walkCalls > ThreatAssessment.ESCAPE_FAILURES_BEFORE_LAST_STAND,
                "失败账清零后撤离一直在重试，实际走了 " + moves.walkCalls + " 次");
        assertTrue(events.messages.stream().noneMatch(m -> m.contains("退无可退")),
                "有真实位移不该声明退无可退：" + events.messages);
    }


    /**
     * 一刻一刻推进到任务收场为止；到 clearThreatsAtTick 那一刻把威胁清空，模拟"走到怪追不上了"。
     * 到顶还没收场就返回 null，让断言去暴露"该收场没收场"。
     */
    private TickResult drive(SelfDefenseTask task, FakeSenses senses,
                                    long clearThreatsAtTick, long maxTicks) {
        for (long i = 1; i <= maxTicks; i++) {
            if (i == clearThreatsAtTick) {
                senses.threats = List.of();
            }
            TickResult result = task.tick(new Tick(i));
            if (result instanceof TickResult.Finished) {
                return result;
            }
        }
        return null;
    }

    /** 一刻一刻推进且角色一直在挪（每刻挪三格）：模拟撤离路上有真实位移，走到收场为止。 */
    private TickResult driveWhileMoving(SelfDefenseTask task, FakeSenses senses, FakeMoves moves, long maxTicks) {
        for (long i = 1; i <= maxTicks; i++) {
            moves.position[0] += 3;
            TickResult result = task.tick(new Tick(i));
            if (result instanceof TickResult.Finished) {
                return result;
            }
        }
        return null;
    }

    /** 看得见、亮着攻击标记的僵尸：自卫会拿它当目标。 */
    private static CombatSenses.Threat zombie(double distance) {
        return new CombatSenses.Threat(1, UUID.randomUUID(), "minecraft:zombie",
                0, 64, distance, distance, ThreatAssessment.Kind.MELEE, false, true, true);
    }

    /** 战斗感观替身：给固定的威胁与血量，威胁可以中途清空模拟甩掉。 */
    private static final class FakeSenses implements CombatSenses {
        List<CombatSenses.Threat> threats = List.of();
        double health = 20;

        @Override public List<CombatSenses.Threat> threats(TickContext context, double radius) {
            return threats;
        }

        @Override public List<CombatSenses.Attacker> recentAttackers(TickContext context) {
            return List.of();
        }

        @Override public CombatSenses.CombatProfile profile(TickContext context) {
            return new CombatSenses.CombatProfile(health, 0,
                    Optional.of(new WeaponChoice.Picked("minecraft:iron_sword", WeaponChoice.Weapon.SWORD_OR_AXE)),
                    0);
        }

        @Override public Entity entityById(TickContext context, int entityId) { return null; }
    }

    /** 出手与走位的替身：记下被调用的次数，按脚本回答每次的结果。 */
    private static final class FakeMoves implements SelfDefenseTask.CombatMoves {
        ActionStatus strikeOutcome = ActionStatus.done();
        ActionStatus walkOutcome = ActionStatus.done();
        /** 角色所在位置：撤离路上有真实位移的场景由测试用例在外面挪动。 */
        final double[] position = {0, 64, 0};
        int strikeCalls;
        int strikeTicks;
        int walkCalls;

        @Override
        public Action strike(TickContext context, int entityId) {
            strikeCalls++;
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    strikeTicks++;
                    return strikeOutcome;
                }
                @Override public void pause() {}
                @Override public void close() {}
                @Override public String describe() { return "替身的出手"; }
            };
        }

        @Override
        public Action walkTo(double x, double y, double z) {
            walkCalls++;
            return new FixedAction("替身的走到", () -> walkOutcome);
        }

        @Override
        public double[] selfPosition(TickContext context) {
            return new double[] {position[0], position[1], position[2]};
        }
    }

    /** 每刻回答同一个结果的动作替身。 */
    private record FixedAction(String label, Supplier<ActionStatus> outcome) implements Action {
        @Override public ActionStatus tick(TickContext context) { return outcome.get(); }
        @Override public void pause() {}
        @Override public void close() {}
        @Override public String describe() { return label; }
    }

    /** 本刻上下文替身：自卫任务经感观与动作替身读现场，碰不到角色。 */
    private record Tick(long number) implements TickContext {
        @Override public long gameTick() { return number; }
        @Override public PlayerContext player() { return null; }
    }

    /** 记下发过的事件：断言撤离的事实如实出门。 */
    private static final class RecordingEvents implements TaskEventSink {
        final List<String> messages = new ArrayList<>();

        @Override public void publish(TaskEvent.Kind kind, String message) {
            messages.add(message);
        }
    }
}
