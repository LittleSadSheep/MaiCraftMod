// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 自卫临时任务：出手与追击沿用进行中的动作，换做法才收尾旧的；点着引信的苦力怕先躲，没点的照常打。 */
class SelfDefenseTaskTest {

    /** 刻号替身：自卫任务只经感观与走位替身读现场，不碰角色对象。 */
    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            throw new IllegalStateException("自卫任务测试不碰角色对象");
        }
    }

    /** 感观替身：威胁由测试逐刻摆。 */
    private static final class Senses implements CombatSenses {
        List<Threat> threats = List.of();

        @Override public List<Threat> threats(TickContext context, double radius) { return threats; }

        @Override public List<Attacker> recentAttackers(TickContext context) { return List.of(); }

        @Override public CombatProfile profile(TickContext context) {
            return new CombatProfile(20, 0, Optional.empty(), 0);
        }

        @Override public Entity entityById(TickContext context, int entityId) { return null; }
    }

    /** 走位替身：每个动作一直在做，记下起了几次、收尾了几次。 */
    private static final class Moves implements SelfDefenseTask.CombatMoves {
        final List<String> started = new ArrayList<>();
        int closed;

        @Override public Action strike(TickContext context, int entityId) { return running("打 " + entityId); }

        @Override public Action walkTo(double x, double y, double z) { return running("走向 " + x + "," + z); }

        @Override public double[] selfPosition(TickContext context) { return new double[] {0, 64, 0}; }

        private Action running(String what) {
            started.add(what);
            return new Action() {
                @Override public ActionStatus tick(TickContext context) { return ActionStatus.running(); }

                @Override public void close() { closed++; }

                @Override public String describe() { return what; }
            };
        }
    }

    private static CombatSenses.Threat foe(ThreatAssessment.Kind kind, double x, double distance, boolean armed) {
        // 看得见、正要动手：算正在威胁角色的怪。
        return new CombatSenses.Threat(7, UUID.randomUUID(), "minecraft:zombie", x, 64, 0, distance, kind, armed, true, true);
    }

    @Test
    void chasingAFoeThatStaysPutStartsOneWalk() {
        // 敌人站在 8 格外不动：只开一次走到，不每刻重新寻路。
        Senses senses = new Senses();
        senses.threats = List.of(foe(ThreatAssessment.Kind.MELEE, 8, 8, false));
        Moves moves = new Moves();
        SelfDefenseNeed need = new SelfDefenseNeed(senses, moves, TaskEventSink.NONE);
        SelfDefenseTask task = new SelfDefenseTask(senses, moves, TaskEventSink.NONE, need);
        task.start(new Tick(0));
        for (long t = 0; t < 10; t++) task.tick(new Tick(t));

        assertEquals(1, moves.started.size(), moves.started.toString());
        assertEquals(0, moves.closed);
    }

    @Test
    void foeComingIntoReachSwitchesFromWalkToStrikeAndClosesTheWalk() {
        Senses senses = new Senses();
        senses.threats = List.of(foe(ThreatAssessment.Kind.MELEE, 8, 8, false));
        Moves moves = new Moves();
        SelfDefenseNeed need = new SelfDefenseNeed(senses, moves, TaskEventSink.NONE);
        SelfDefenseTask task = new SelfDefenseTask(senses, moves, TaskEventSink.NONE, need);
        task.start(new Tick(0));
        task.tick(new Tick(0));
        senses.threats = List.of(foe(ThreatAssessment.Kind.MELEE, 2, 2, false));
        for (long t = 1; t < 5; t++) task.tick(new Tick(t));

        assertEquals(List.of("走向 8.0,0.0", "打 7"), moves.started);
        assertEquals(1, moves.closed, "换成出手前要收尾追击的走到");
    }

    @Test
    void unlitCreeperIsStruckLikeAnyFoe() {
        // 实机：没点引信的苦力怕走过来，角色"守在原地"等它贴脸点着炸了。没点引信的就是一只普通怪，打一下再退。
        Senses senses = new Senses();
        senses.threats = List.of(foe(ThreatAssessment.Kind.EXPLOSIVE, 2, 2, false));
        Moves moves = new Moves();
        SelfDefenseNeed need = new SelfDefenseNeed(senses, moves, TaskEventSink.NONE);
        SelfDefenseTask task = new SelfDefenseTask(senses, moves, TaskEventSink.NONE, need);
        task.start(new Tick(0));
        for (long t = 0; t < 5; t++) task.tick(new Tick(t));

        assertEquals(List.of("打 7"), moves.started);
    }

    @Test
    void litCreeperWithinFuseRangeIsEvadedNotStruck() {
        // 点着引信、离 5 格：先退到 9 格外等它熄，不出手也不追。
        Senses senses = new Senses();
        senses.threats = List.of(foe(ThreatAssessment.Kind.EXPLOSIVE, 5, 5, true));
        Moves moves = new Moves();
        SelfDefenseNeed need = new SelfDefenseNeed(senses, moves, TaskEventSink.NONE);
        SelfDefenseTask task = new SelfDefenseTask(senses, moves, TaskEventSink.NONE, need);
        task.start(new Tick(0));
        for (long t = 0; t < 5; t++) task.tick(new Tick(t));

        assertEquals(List.of("走向 -9.0,0.0"), moves.started);
    }

    @Test
    void litCreeperBeyondFuseRangeIsLeftToDefuse() {
        // 点着引信但已经在 8 格外：凑近它只会接着膨胀，原地等它熄了再打。
        Senses senses = new Senses();
        senses.threats = List.of(foe(ThreatAssessment.Kind.EXPLOSIVE, 8, 8, true));
        Moves moves = new Moves();
        SelfDefenseNeed need = new SelfDefenseNeed(senses, moves, TaskEventSink.NONE);
        SelfDefenseTask task = new SelfDefenseTask(senses, moves, TaskEventSink.NONE, need);
        task.start(new Tick(0));
        for (long t = 0; t < 5; t++) task.tick(new Tick(t));

        assertEquals(List.of(), moves.started);
    }
}
