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
                    WeaponChoice.Weapon.MELEE)), 4);
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
        BattleMoves.Posture posture = BattleMoves.Posture.GROUNDED;
        boolean canPillar;
        int pillars;
        boolean night;
        /** 出手被拒（原生攻击没得到确认）。 */
        boolean rejecting;
        /** 交互入口被上一下举盾占着：出手抛"还在等确认"。 */
        boolean busy;
        int keptAim;
        int lowered;
        /** 换武器还要几刻换完；开始换的次数；真收尾的次数。 */
        int swapTicks;
        int armStarts;
        int armStops;
        /** 起跳、上升时镜头压在出手点上的次数；准星在不在它身上；出手一直没结果（还在瞄、还在等充能）。 */
        int aimedStrike;
        boolean crosshair;
        boolean strikeHangs;

        @Override public void arm(TickContext context, double distance) {
            if (swapTicks > 0 || distance <= 4) return;
            armStarts++;
            swapTicks = 3;
        }

        @Override public boolean finishArming(TickContext context) {
            if (swapTicks <= 0) return false;
            swapTicks--;
            return true;
        }

        @Override public void stopArming() {
            armStops++;
            swapTicks = 0;
        }

        @Override public void stepAimingStrike(TickContext context, int entityId, Footwork.Step step) {
            aimedStrike++;
            steps.add(step);
        }

        @Override public boolean crosshairOn(TickContext context, int entityId) { return crosshair; }

        @Override public boolean night(TickContext context) { return night; }

        @Override public void stepKeepingAim(TickContext context, Footwork.Step step) {
            keptAim++;
            steps.add(step);
        }

        @Override public boolean canPillar(TickContext context) { return canPillar; }

        @Override public Action pillarUp(TickContext context) {
            pillars++;
            return new Instant(ActionStatus::done);
        }

        @Override public BattleMoves.Posture posture(TickContext context) { return posture; }

        @Override public Action strike(TickContext context, int entityId) {
            return new Instant(() -> {
                if (busy) throw new IllegalStateException("a native action is already awaiting confirmation");
                if (strikeHangs) return ActionStatus.running();
                strikes++;
                return rejecting ? ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME, "没确认"))
                        : ActionStatus.done();
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
        @Override public void lowerGuard() { lowered++; }
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
        // 回了血也不回头；僵尸被甩到四十格外才算甩开。
        senses.health = 20;
        assertEquals(Battle.State.RETREATING, fight.tick(new Tick(1)).state());
        senses.threats.clear();
        // 僵尸一时看不见：它最后露面在 6 格外，接着跑；三秒都没再露面才算甩开。
        Battle.Moment escaped = fight.tick(new Tick(2));
        int t = 2;
        while (escaped.state() == Battle.State.RETREATING && t < 200) escaped = fight.tick(new Tick(++t));
        assertTrue(t - 2 >= Retreat.REMEMBER_TICKS, "看不见后又跑了 " + (t - 2) + " 刻");
        assertEquals(Battle.State.ESCAPED, escaped.state());
        assertEquals("血快见底，先撤离保命", escaped.retreatWhy());
    }

    @Test
    void aPursuerAFewBlocksBehindDoesNotStopTheRun() {
        // 血少撤离，僵尸跟在三格外、还够不着我：接着跑、盾放下，不停下来退着还手（人跑得比僵尸快）。
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 3));
        senses.health = 4;
        Moves moves = new Moves();
        Battle.Moment running = battle(senses, moves).tick(new Tick(0));
        assertEquals("撤离：血快见底，先撤离保命", running.doing());
        assertEquals(1, moves.walks.size());
        assertEquals(0, moves.strikes);
        assertTrue(moves.lowered > 0, "跑的时候盾放下，举着盾疾跑不了");
    }

    @Test
    void aPursuerHiddenBehindAHillIsStillBehindMe() {
        // 撤离途中追兵隔着坡一时看不见、没亮攻击标记（这一刻不算"在追"），但还在十二格外：不算甩开，接着跑。
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 6));
        senses.health = 4;
        Moves moves = new Moves();
        Battle fight = battle(senses, moves);
        assertEquals(Battle.State.RETREATING, fight.tick(new Tick(0)).state());
        senses.threats.clear();
        senses.threats.add(new CombatSenses.Threat(1, UUID.randomUUID(), "minecraft:zombie", 12, 64, 0, 12,
                ThreatAssessment.Kind.MELEE, false, false, false));
        for (int t = 1; t <= Retreat.REMEMBER_TICKS + 20; t++) {
            assertEquals(Battle.State.RETREATING, fight.tick(new Tick(t)).state(), "第 " + t + " 刻");
        }
    }

    @Test
    void aBusyInteractionSkipsTheSwingInsteadOfEndingTheFight() {
        // 被追上（贴着我）要还手，交互入口还被举盾占着：这一刀不挥，仗照打，不抛错。
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 1));
        senses.health = 4;
        Moves moves = new Moves();
        moves.busy = true;
        Battle.Moment caught = battle(senses, moves).tick(new Tick(0));
        assertEquals("撤离途中被追上，退着还手", caught.doing());
        assertFalse(caught.struck());
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
    void jumpsWhenAlmostChargedAndStrikesOnTheWayDown() {
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 2.6));
        Moves moves = new Moves();
        Battle fight = battle(senses, moves);
        // 站在地上、头顶有空、充能过半：起跳，这一刻不砍。
        moves.posture = new BattleMoves.Posture(true, false, true, 0.6f);
        fight.tick(new Tick(0));
        assertTrue(moves.lastStep().jump(), "起跳");
        assertEquals(0, moves.strikes);
        // 往上升时不砍，往下落时砍（原版暴击）。
        moves.posture = new BattleMoves.Posture(false, false, false, 0.9f);
        fight.tick(new Tick(1));
        assertEquals(0, moves.strikes, "还在往上升");
        moves.posture = new BattleMoves.Posture(false, true, false, 1f);
        fight.tick(new Tick(2));
        assertEquals(1, moves.strikes, "下落时砍");
        // 跳不了（头顶有方块）：站着砍。
        moves.posture = new BattleMoves.Posture(true, false, false, 1f);
        fight.tick(new Tick(3));
        assertEquals(2, moves.strikes);
    }

    @Test
    void standingInTheBandButOutOfReachClosesInThenGivesUp() {
        // 它在坑里低一截：中心距 3 格在带里，眼睛到它碰撞箱却有 3.4 格，够不着——往里凑，凑到底还不行就换。
        Senses senses = new Senses();
        senses.threats.add(new CombatSenses.Threat(1, UUID.randomUUID(), "minecraft:zombie", 3, 63, 0, 3.2,
                ThreatAssessment.Kind.MELEE, false, true, true, 0.3, 3.4, false, 2.02, false));
        Moves moves = new Moves();
        Battle fight = battle(senses, moves);
        fight.tick(new Tick(0));
        assertTrue(moves.lastStep().standing(), "一开始在带里站着");
        boolean closedIn = false;
        for (int tick = 1; tick < 20; tick++) {
            fight.tick(new Tick(tick));
            closedIn |= moves.lastStep().dx() > 0;
        }
        assertTrue(closedIn, "够不着就往里凑");
        for (int tick = 20; tick < 80; tick++) fight.tick(new Tick(tick));
        assertTrue(fight.unreachable().contains(1), "凑到底还够不着：当它走不到");
    }

    @Test
    void swingingLeavesTheAimToTheStrike() {
        // 出手那一刻脚照迈，但镜头留给出手的瞄点（它跟着对方横移），走位不把准星拽回身体中心。
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 1.5));
        Moves moves = new Moves();
        battle(senses, moves).tick(new Tick(0));
        assertEquals(1, moves.keptAim);
        assertTrue(moves.lastStep().dx() < 0, "照样往外退");
    }

    @Test
    void aRejectedStrikeTriesAnotherAngleThenGivesUp() {
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 2.6));
        Moves moves = new Moves();
        moves.rejecting = true;
        Battle fight = battle(senses, moves);
        fight.tick(new Tick(0));
        // 刚才那个站位被拒：在带子里绕开它换个方位。
        fight.tick(new Tick(1));
        assertTrue(Math.abs(moves.lastStep().dz()) > 0.5, "绕到侧面再出手，实际 " + moves.lastStep());
        fight.tick(new Tick(2));
        assertTrue(fight.unreachable().contains(1), "连着被拒三次：这只打不出去，换下一只");
    }

    @Test
    void cornedAtNightAsksForShelter() {
        Senses senses = new Senses();
        // 追兵贴着（挖坑来不及）：先撤，撤不掉才就地挖。
        senses.threats.add(zombie(1, 5));
        senses.health = 4;
        Moves moves = new Moves();
        moves.walkStatus = ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "死路"));
        moves.night = true;
        Battle fight = battle(senses, moves);
        fight.offerShelter();
        for (int tick = 0; tick < 3; tick++) fight.tick(new Tick(tick));
        assertEquals(Battle.State.NEEDS_SHELTER, fight.tick(new Tick(3)).state(), "天黑、退无可退、打不过：挖坑自保");
        assertEquals(Battle.State.FIGHTING, fight.tick(new Tick(4)).state(), "一仗只要一次，回来了就背水一战");
    }

    @Test
    void meleeBandFollowsBothSidesReachAndTheCooldown() {
        Battlefield.Me me = new Battlefield.Me(0, 64, 0, 0.3, 20, 0, 3.0, true, true, false, 3, 4);
        Footwork.Band ready = Battle.meleeBand(me, zombie(1, 3), true);
        assertEquals(2.02, ready.inner(), 1e-9, "内沿是僵尸够得着我");
        assertEquals(3.15, ready.outer(), 1e-9, "外沿是我稳稳够得着它");
        Footwork.Band cooling = Battle.meleeBand(me, zombie(1, 3), false);
        assertTrue(cooling.inner() > ready.inner(), "冷却中退到它够不着外");
        // 打玩家：两边手一样长，冷却好了才凑进去（3.15 内），没好就退到他够不着的地方（3.3 外）等。
        CombatSenses.Threat player = new CombatSenses.Threat(5, UUID.randomUUID(), "minecraft:player", 3, 64, 0, 3,
                ThreatAssessment.Kind.MELEE, false, true, false, 0.3, 2.7, false, 3.3, false);
        assertTrue(Battle.meleeBand(me, player, true).outer() <= 3.15);
        assertTrue(Battle.meleeBand(me, player, false).inner() > 3.3);
        // 苦力怕守在点火距离（3 格）外砍。
        assertTrue(Battle.meleeBand(me, creeper(3.2, false), true).inner() > CombatGeometry.CREEPER_IGNITE);
    }

    @Test
    void cornedAndOutmatchedPillarsUpThenStandsStillOnTop() {
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 6));
        senses.health = 4;
        Moves moves = new Moves();
        moves.walkStatus = ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "死路"));
        moves.canPillar = true;
        Battle fight = battle(senses, moves);
        for (int tick = 0; tick < 3; tick++) fight.tick(new Tick(tick));
        assertTrue(fight.cornered());
        Battle.Moment up = fight.tick(new Tick(3));
        assertEquals(1, moves.pillars, "退无可退：就地垫高");
        assertTrue(up.doing().contains("柱顶"), up.doing());
        // 站在柱顶：僵尸贴上来也不挪脚，只砍够得着的。
        senses.threats.set(0, zombie(1, 2.5));
        moves.steps.clear();
        fight.tick(new Tick(4));
        assertTrue(moves.lastStep().standing(), "一挪就掉下去");
        assertEquals(1, moves.strikes);
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

    /** 在东边 x 格处的僵尸：看不看得见、亮没亮攻击标记由调用方定。 */
    private static CombatSenses.Threat zombie(int id, double x, boolean visible, boolean aggressive) {
        return new CombatSenses.Threat(id, new UUID(0, id), "minecraft:zombie", x, 64, 0, Math.abs(x),
                ThreatAssessment.Kind.MELEE, false, visible, aggressive, 0.3, Math.max(0, Math.abs(x) - 0.3),
                Math.abs(x) < 1.43, 2.02, false);
    }

    @Test
    void aChaserWhoseAttackMarkFlickersIsNotDeclaredGone() {
        // 十三格外的僵尸找不到路，攻击标记一秒一闪；它还在往我这边挪：一直是追兵，不"逼近↔打完了"来回切。
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 13, true, true));
        Moves moves = new Moves();
        Battle fight = battle(senses, moves);
        assertEquals(Battle.State.FIGHTING, fight.tick(new Tick(0)).state());
        double x = 13;
        for (int t = 1; t <= 100; t++) {
            x -= 0.05;
            senses.threats.set(0, zombie(1, x, true, t % 20 == 0));
            assertEquals(Battle.State.FIGHTING, fight.tick(new Tick(t)).state(), "第 " + t + " 刻");
        }
        // 它掉头走了、标记也暗着：满三秒才算打完，不是下一刻就收手。
        int t = 101;
        Battle.Moment moment;
        do {
            x += 0.05;
            senses.threats.set(0, zombie(1, x, true, false));
            moment = fight.tick(new Tick(t++));
        } while (moment.state() == Battle.State.FIGHTING && t < 400);
        assertEquals(Battle.State.FINISHED, moment.state());
        assertTrue(t - 101 >= StillChasing.LET_GO_TICKS, "走开后又看了 " + (t - 101) + " 刻");
    }

    @Test
    void aChaserWanderingAtTheEdgeOfSightIsStillWatched() {
        // 追过我的那只走到视野半径外一点点：还在边上晃，接着看着它，不当它走了。
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 13.5, true, true));
        Moves moves = new Moves();
        Battle fight = battle(senses, moves);
        fight.tick(new Tick(0));
        senses.threats.set(0, zombie(1, 14.6, true, false));
        assertEquals(Battle.State.FIGHTING, fight.tick(new Tick(1)).state(), "出了视野一格：还在看着");
        // 死了（不在场上了）：当场打完，不等三秒。
        senses.threats.clear();
        assertEquals(Battle.State.FINISHED, fight.tick(new Tick(2)).state());
    }

    @Test
    void aWeaponSwapOnceStartedIsFinishedEvenAfterTheFightEnds() {
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 8));
        Moves moves = new Moves();
        Battle fight = battle(senses, moves);
        fight.tick(new Tick(0));
        assertEquals(1, moves.armStarts, "还远着：路上先换武器");
        // 下一刻它死了、这一仗打完了：换到一半的接着换完，不收掉。
        senses.threats.clear();
        for (int t = 1; t <= 5; t++) assertEquals(Battle.State.FINISHED, fight.tick(new Tick(t)).state());
        assertEquals(0, moves.swapTicks, "打完了也接着换完");
        assertEquals(0, moves.armStops, "打完了不收掉换到一半的武器");
        fight.close();
        assertEquals(1, moves.armStops, "这一仗真结束才收尾");
    }

    @Test
    void noSwingWhileTheWeaponIsStillBeingSwapped() {
        // 换武器开了个头，它就冲到了跟前：换完再砍，不拿着半路的手乱挥，也不重开一次换武器。
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 8));
        Moves moves = new Moves();
        Battle fight = battle(senses, moves);
        fight.tick(new Tick(0));
        senses.threats.set(0, zombie(1, 2.6));
        fight.tick(new Tick(1));
        assertEquals(0, moves.strikes, "还在换：这一刻不挥");
        for (int t = 2; t <= 5; t++) fight.tick(new Tick(t));
        assertTrue(moves.strikes > 0, "换完就砍");
        assertEquals(1, moves.armStarts);
    }

    @Test
    void takeOffAndRiseAimAtTheStrikePoint() {
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 2.6));
        Moves moves = new Moves();
        Battle fight = battle(senses, moves);
        moves.posture = new BattleMoves.Posture(true, false, true, 0.6f);
        fight.tick(new Tick(0));
        moves.posture = new BattleMoves.Posture(false, false, false, 0.7f);
        fight.tick(new Tick(1));
        assertEquals(2, moves.aimedStrike, "起跳、往上升时镜头都压在出手点上");
    }

    @Test
    void twoLeapsWithoutASwingThenStandsAndSwingsForAWhile() {
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 2.6));
        Moves moves = new Moves();
        moves.strikeHangs = true;
        Battle fight = battle(senses, moves);
        int t = 0;
        for (int leap = 0; leap < 2; leap++) {
            moves.posture = new BattleMoves.Posture(true, false, true, 0.6f);
            fight.tick(new Tick(t++));
            assertTrue(moves.lastStep().jump(), "第 " + (leap + 1) + " 跳");
            moves.posture = new BattleMoves.Posture(false, false, false, 0.7f);
            fight.tick(new Tick(t++));
            moves.posture = new BattleMoves.Posture(false, true, false, 0.8f);
            fight.tick(new Tick(t++));
        }
        // 两跳都一刀没砍出去，落地：这一阵站着砍，不再起跳。
        int swingsBefore = moves.keptAim;
        moves.posture = new BattleMoves.Posture(true, false, true, 0.6f);
        for (int i = 0; i < 20; i++) {
            fight.tick(new Tick(t++));
            assertFalse(moves.lastStep().jump(), "站着砍的这一阵不跳");
        }
        assertTrue(moves.keptAim > swingsBefore, "站着出手");
    }

    @Test
    void landingWithAFullBarAndTheCrosshairOnItStrikesStanding() {
        // 斧子充能跟不上一跳：往下落时还没满，落地满了、准星正在它身上——站着直接砍，不再起跳。
        Senses senses = new Senses();
        senses.threats.add(zombie(1, 2.6));
        Moves moves = new Moves();
        moves.strikeHangs = true;
        moves.crosshair = true;
        Battle fight = battle(senses, moves);
        moves.posture = new BattleMoves.Posture(true, false, true, 0.6f);
        fight.tick(new Tick(0));
        moves.posture = new BattleMoves.Posture(false, true, false, 0.8f);
        fight.tick(new Tick(1));
        moves.strikeHangs = false;
        moves.posture = new BattleMoves.Posture(true, false, true, 1f);
        fight.tick(new Tick(2));
        assertFalse(moves.lastStep().jump(), "落地满条：不再起跳");
        assertEquals(1, moves.strikes, "站着直接砍");
        // 砍完接着跳劈。
        moves.posture = new BattleMoves.Posture(true, false, true, 0.6f);
        fight.tick(new Tick(3));
        assertTrue(moves.lastStep().jump());
    }
}
