// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 打龙时的脚和手：躲龙息疾跑出去、射水晶走远射、笼子里射不到就放弃、砍龙头、回血时让吃饭插进来、战况记账。 */
class DragonCombatTest {

    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            return null;
        }
    }

    /** 动手替身：记下迈的步、射的箭（射程）、砍的龙头、走的路；地表、弹道、区块由测试摆。 */
    private static final class Moves implements BattleMoves {
        final List<Footwork.Step> steps = new ArrayList<>();
        final List<Double> shotRanges = new ArrayList<>();
        final List<GoalCompiler.Compiled> walks = new ArrayList<>();
        int headStrikes;
        boolean arc = true;
        boolean loaded = true;
        boolean fires = true;
        DragonGeometry.Surface surface = DragonGeometryTest.island(60);

        @Override public Action strike(TickContext context, int entityId) { return new Instant(ActionStatus::done); }

        @Override public Action strikeHead(TickContext context, int dragonId) {
            return new Instant(() -> {
                headStrikes++;
                return ActionStatus.done();
            });
        }

        @Override public Action shoot(TickContext context, int entityId) { return shoot(context, entityId, 0); }

        @Override public Action shoot(TickContext context, int entityId, double maxRange) {
            shotRanges.add(maxRange);
            return new Shot(fires);
        }

        @Override public boolean arcTo(TickContext context, int entityId, double maxRange) { return arc; }
        @Override public boolean canShoot(TickContext context) { return true; }
        @Override public boolean hasMeleeWeapon(TickContext context) { return true; }
        @Override public boolean attackReady(TickContext context) { return true; }
        @Override public void guard(TickContext context, double distance, boolean aboutToStrike) {}
        @Override public void lowerGuard() {}
        @Override public Footwork.Ground ground(TickContext context) { return (dx, dz) -> Footwork.Footing.FLAT; }
        @Override public void step(TickContext context, int faceEntityId, Footwork.Step step) { steps.add(step); }

        @Override public Action walk(GoalCompiler.Compiled goal) {
            walks.add(goal);
            return new Instant(ActionStatus::progressed);
        }

        @Override public double[] selfPosition(TickContext context) { return new double[] {0, 64, 0}; }
        @Override public boolean standable(TickContext context, int x, int y, int z) { return true; }
        @Override public String mainHand(TickContext context) { return "minecraft:diamond_sword"; }
        @Override public DragonGeometry.Surface surface(TickContext context) { return surface; }
        @Override public boolean loaded(TickContext context, double x, double z) { return loaded; }
    }

    private record Instant(Supplier<ActionStatus> status) implements Action {
        @Override public ActionStatus tick(TickContext context) { return status.get(); }
        @Override public String describe() { return "替身"; }
    }

    /** 一箭：推进一刻就射完，射没射出去由测试定。 */
    private record Shot(boolean fired) implements Action, RangedCombat.Fires {
        @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
        @Override public String describe() { return "射一箭"; }
    }

    private static Battlefield field(double x, double z, double health, DragonArena arena) {
        Battlefield.Me me = new Battlefield.Me(x, 64, z, 0.3, health, 10, 3.0, true, true, true, 7, 4);
        return new Battlefield(me, List.of(), arena);
    }

    private static DragonArena arena(long tick, DragonArena.Dragon dragon, List<DragonArena.Crystal> crystals,
                                     List<DragonArena.Cloud> clouds) {
        return new DragonArena(tick, dragon, crystals, clouds, List.of(), List.of(), DragonGeometryTest.PORTAL, 20, false);
    }

    private static DragonArena.Dragon circling() {
        return DragonGeometryTest.dragon(0, 100, -80, 0, 0, DragonArena.Activity.CIRCLING);
    }

    private final Moves moves = new Moves();
    private final DragonCombat combat = new DragonCombat(moves);

    // 先记战况再按打法做这一刻，和引擎里的次序一样。
    private Battle.Moment tick(long t, Battlefield field) {
        combat.observe(new Tick(t), field);
        DragonTactic.Move move = DragonTactic.decide(field, combat.memory(new Tick(t)));
        return combat.tick(new Tick(t), field, move);
    }

    @Test
    void breathIsLeftAtASprintAndStoppingIsUnsafeMeanwhile() {
        DragonArena breath = arena(0, circling(), List.of(), List.of(new DragonArena.Cloud(0.5, 64, 0, 5)));
        Battle.Moment moment = tick(0, field(0, 0, 20, breath));
        assertEquals(Battle.State.EVADING, moment.state());
        Footwork.Step step = moves.steps.get(moves.steps.size() - 1);
        assertTrue(step.sprint() && step.dx() < 0, "背离云心疾跑：" + step);
        assertEquals(Interruptibility.UNSAFE_TO_STOP, combat.interruptibility());
        assertEquals(1, combat.report().breathDodges());
        tick(1, field(-1, 0, 20, breath));
        assertEquals(1, combat.report().breathDodges(), "同一次躲闪只记一次");
    }

    @Test
    void crystalIsShotFromOutsideTheBlastAndCountedWhenItVanishes() {
        DragonArena.Crystal crystal = new DragonArena.Crystal(5, 40.5, 90, 0.5, false, null, false);
        DragonArena.Crystal other = new DragonArena.Crystal(6, -40.5, 90, 0.5, false, null, false);
        // 站在柱子二十格外：离水晶三十多格、在爆炸范围外，弹道算得出就射，射程放到 48 格。
        Battle.Moment moment = tick(0, field(20.5, 0.5, 20, arena(0, circling(), List.of(crystal, other), List.of())));
        assertEquals(Battle.State.SHOOTING, moment.state());
        assertTrue(moment.fired());
        assertEquals(List.of(DragonGeometry.CRYSTAL_SHOT_RANGE), moves.shotRanges);
        // 射中的那颗没了、区块还加载着：算我射掉的；没射过的另一颗也没了：不算我的。
        tick(1, field(20.5, 0.5, 20, arena(1, circling(), List.of(), List.of())));
        DragonCombat.Report report = combat.report();
        assertEquals(1, report.crystalsDestroyed());
        assertEquals(1, report.crystalsGoneOtherwise());
        assertTrue(report.crystalsLeft().isEmpty(), report.crystalsLeft().toString());
    }

    @Test
    void unloadedCrystalIsReportedAsUnseenNotDestroyed() {
        DragonArena.Crystal crystal = new DragonArena.Crystal(5, 40.5, 90, 0.5, false, null, false);
        tick(0, field(20.5, 0.5, 20, arena(0, circling(), List.of(crystal), List.of())));
        moves.loaded = false;
        tick(1, field(20.5, 0.5, 20, arena(1, circling(), List.of(), List.of())));
        DragonCombat.Report report = combat.report();
        assertEquals(0, report.crystalsDestroyed());
        assertEquals(1, report.crystalsLeft().size());
        assertTrue(report.crystalsLeft().get(0).contains("看不到了"), report.crystalsLeft().toString());
    }

    @Test
    void crystalOutOfPlaceIsApproachedFromAStance() {
        DragonArena.Crystal crystal = new DragonArena.Crystal(5, 40.5, 70, 0.5, false, null, false);
        // 矮柱子（水晶离地六格）脚下五格：在爆炸范围里，不射，走去一圈站位里最近的那个。
        Battle.Moment moment = tick(0, field(36, 0.5, 20, arena(0, circling(), List.of(crystal), List.of())));
        assertEquals(Battle.State.FIGHTING, moment.state());
        assertTrue(moves.shotRanges.isEmpty());
        assertEquals(1, moves.walks.size());
    }

    @Test
    void cagedCrystalWithNoStanceIsGivenUpAndSaysWhy() {
        moves.arc = false;
        moves.surface = (x, z) -> OptionalInt.empty();
        DragonArena.Crystal caged = new DragonArena.Crystal(5, 40.5, 90, 0.5, true, null, false);
        Battle.Moment moment = tick(0, field(20.5, 0.5, 20, arena(0, circling(), List.of(caged), List.of())));
        assertTrue(moment.doing().contains("铁栏笼"), moment.doing());
        assertEquals("铁栏笼挡着，射不进去", combat.memory(new Tick(0)).givenUp().get(5));
        DragonCombat.Report report = combat.report();
        assertEquals(0, report.standingCrystals());
        assertTrue(report.crystalsLeft().get(0).endsWith("铁栏笼挡着，射不进去"), report.crystalsLeft().toString());
    }

    @Test
    void perchedDragonHeadIsStruckFromBesideIt() {
        DragonArena.Dragon perched = DragonGeometryTest.dragon(0.5, 64, 0.5, 0, 0, DragonArena.Activity.WATCHING);
        // 龙头在 (0.5, 64.5, -6)：站在它正前方三格（不碰龙头外扩一格那一圈），够得着就砍。
        Battle.Moment moment = tick(0, field(0.5, -8.6, 20, arena(0, perched, List.of(), List.of())));
        assertTrue(moment.struck(), moment.doing());
        assertEquals(1, moves.headStrikes);
        // 吼到第 30 刻：站在龙头前方要退开，不再砍。
        DragonArena.Dragon roaring = new DragonArena.Dragon(1, "minecraft:ender_dragon", 0.5, 64, 0.5, 0, 0, 0,
                perched.head(), perched.neck(), DragonArena.Activity.ROARING, 30, 150, 200, -1, true, 9);
        Battle.Moment backOff = tick(1, field(0.5, -8.6, 20, arena(1, roaring, List.of(), List.of())));
        assertEquals(1, moves.headStrikes, "龙要喷了不砍");
        assertEquals(Battle.State.EVADING, backOff.state(), backOff.doing());
    }

    @Test
    void restingLetsEatingInAndGivesUpAfterAMinuteWithoutGain() {
        DragonArena calm = arena(0, circling(), List.of(), List.of());
        tick(0, field(25.5, 0.5, 10, calm));
        assertEquals(1, combat.report().rests());
        // 已经站在休息圈上：两个动作之间，吃饭可以插进来。
        assertEquals(Interruptibility.BETWEEN_ACTIONS, combat.interruptibility());
        for (long t = 1; t <= DragonCombat.REST_STALL_TICKS + 1; t++) tick(t, field(25.5, 0.5, 10, calm));
        assertTrue(combat.report().restStalled(), "一分钟血一点没涨");
        assertEquals(1, combat.report().rests(), "同一次回血只记一次");
    }

    @Test
    void deathIsSeenThenTheDragonVanishes() {
        DragonArena.Dragon dying = DragonGeometryTest.dragon(0.5, 80, 0.5, 0, 0, DragonArena.Activity.DYING);
        tick(0, field(10, 0, 20, arena(0, dying, List.of(), List.of())));
        assertTrue(combat.report().dyingSeen());
        assertFalse(combat.report().goneAfterDying());
        combat.observe(new Tick(1), field(10, 0, 20, null));
        assertTrue(combat.report().goneAfterDying());
        assertFalse(combat.report().goneWithoutDying());
    }

    @Test
    void vanishingWithoutDeathIsNotAKill() {
        tick(0, field(10, 0, 20, arena(0, circling(), List.of(), List.of())));
        combat.observe(new Tick(1), field(10, 0, 20, null));
        assertTrue(combat.report().goneWithoutDying());
        assertFalse(combat.report().dyingSeen());
    }
}
