// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** 打法：躲爆炸压过一切、撤离保持到甩开、打不过就撤、挑最急的、能射就射、没得打只走位、打完收手。 */
class TacticTest {

    private static Battlefield.Me me(double health, boolean melee, boolean bow) {
        return new Battlefield.Me(0, 64, 0, 0.3, health, 0, 3.0, true, melee, bow, melee ? 3 : 0, 4);
    }

    private static Battlefield.Foe zombie(int id, double distance) {
        return foe(id, distance, ThreatAssessment.Kind.MELEE, false, true);
    }

    private static Battlefield.Foe creeper(int id, double distance, boolean armed) {
        return foe(id, distance, ThreatAssessment.Kind.EXPLOSIVE, armed, true);
    }

    private static Battlefield.Foe foe(int id, double distance, ThreatAssessment.Kind kind, boolean armed,
                                       boolean chasing) {
        CombatSenses.Threat threat = new CombatSenses.Threat(id, UUID.randomUUID(), "x", distance, 64, 0,
                distance, kind, armed, true, chasing);
        return new Battlefield.Foe(threat, chasing, false, true, false);
    }

    private static Tactic.Plan decide(Battlefield.Me me, Tactic.Memory memory, Battlefield.Foe... foes) {
        return Tactic.decide(new Battlefield(me, List.of(foes)), memory);
    }

    @Test
    void anArmedCreeperInRangeComesFirst() {
        Tactic.Plan plan = decide(me(4, true, false), Tactic.Memory.FRESH, zombie(1, 2), creeper(2, 6, true));
        assertEquals(2, assertInstanceOf(Tactic.EvadeBlast.class, plan).bomb().id(), "血线破了也先躲爆炸");
        // 跑出波及范围（7.5 格）之外就不再躲：有弓就射它，没弓先打别的。
        assertInstanceOf(Tactic.Shoot.class, decide(me(20, true, true), Tactic.Memory.FRESH, creeper(2, 9, true)));
        assertInstanceOf(Tactic.KeepClear.class, decide(me(20, true, false), Tactic.Memory.FRESH, creeper(2, 9, true)),
                "没弓不凑上去，避开它等引信熄");
    }

    @Test
    void retreatsWhenOutmatchedAndKeepsGoingUntilClear() {
        assertInstanceOf(Tactic.Withdraw.class, decide(me(4, true, false), Tactic.Memory.FRESH, zombie(1, 5)));
        Tactic.Memory cornered = new Tactic.Memory(-1, true, true, Set.of(), false, false);
        assertInstanceOf(Tactic.Melee.class, decide(me(4, true, false), cornered, zombie(1, 5)), "退无可退就地迎战");
        Tactic.Memory retreating = new Tactic.Memory(-1, true, false, Set.of(), false, false);
        assertInstanceOf(Tactic.Withdraw.class, decide(me(20, true, false), retreating, zombie(1, 5)),
                "回了血也不回头，跑到甩开");
        assertInstanceOf(Tactic.Withdraw.class, decide(me(20, false, false), Tactic.Memory.FRESH, zombie(1, 5)),
                "空手、对面会还手就撤");
        assertInstanceOf(Tactic.Melee.class, decide(me(20, false, false), Tactic.Memory.FRESH, creeper(1, 5, false)),
                "没点引信的苦力怕不还手，空手也能打");
        assertInstanceOf(Tactic.KeepClear.class, decide(me(8, true, false), Tactic.Memory.FRESH, creeper(1, 5, false)),
                "残血又射不了：不凑上去砍苦力怕，先避开它");
        assertInstanceOf(Tactic.Shoot.class, decide(me(8, true, true), Tactic.Memory.FRESH, creeper(1, 5, false)),
                "残血有弓照射");
    }

    @Test
    void cornedAndOutmatchedPillarsUpUnlessSomethingShoots() {
        Tactic.Memory stuck = new Tactic.Memory(-1, false, true, Set.of(), false, false, true, false);
        assertInstanceOf(Tactic.PillarUp.class, decide(me(4, true, false), stuck, zombie(1, 3)),
                "撤不掉又打不过：就地垫高");
        assertInstanceOf(Tactic.Melee.class, decide(me(4, true, false), stuck, zombie(1, 3),
                foe(2, 10, ThreatAssessment.Kind.RANGED, false, true)), "有骷髅在射：站高了更挨射，不垫");
        Tactic.Memory perched = new Tactic.Memory(-1, false, true, Set.of(), false, false, true, true);
        assertInstanceOf(Tactic.Melee.class, decide(me(4, true, false), perched, zombie(1, 3)), "已经在柱顶上：砍够得着的");
    }

    @Test
    void cornedAtNightDigsInEvenWithShooters() {
        Tactic.Memory night = new Tactic.Memory(-1, false, true, Set.of(), false, false, false, false, true);
        assertInstanceOf(Tactic.DigIn.class, decide(me(4, true, false), night, zombie(1, 3),
                foe(2, 10, ThreatAssessment.Kind.RANGED, false, true)), "封住的坑射不进来");
        assertInstanceOf(Tactic.Melee.class, decide(me(20, true, false), night, zombie(1, 3)), "打得过就不躲");
    }

    @Test
    void atNightOutmatchedDigsInOnceTheChasersAreFarEnough() {
        // 实机（全量测试 10-12）：第一晚空手往黑地里撤，越撤撞上的怪越多，被围死三次。
        Tactic.Memory night = new Tactic.Memory(-1, false, false, Set.of(), false, false, false, false, true);
        assertInstanceOf(Tactic.Withdraw.class, decide(me(20, false, false), night, zombie(1, 3)), "追兵贴着：先拉开距离");
        assertInstanceOf(Tactic.DigIn.class, decide(me(20, false, false), night, zombie(1, 8)), "离得够远：就地挖坑");
        Tactic.Memory fleeing = new Tactic.Memory(-1, true, false, Set.of(), false, false, false, false, true);
        assertInstanceOf(Tactic.DigIn.class, decide(me(20, false, false), fleeing, zombie(1, 8)), "撤着撤着拉开了也挖");
        assertInstanceOf(Tactic.Melee.class, decide(me(20, true, false), night, zombie(1, 8)), "打得过就不躲");
    }

    @Test
    void picksTheMostUrgentAndSticksToTheLockedOne() {
        // 近处的苦力怕比贴脸的僵尸还急。
        assertEquals(2, target(decide(me(20, true, false), Tactic.Memory.FRESH, zombie(1, 2), creeper(2, 6, false))));
        // 认准的那只接着打，近一点的同档不抢。
        Tactic.Memory locked = new Tactic.Memory(5, false, false, Set.of(), false, false);
        assertEquals(5, target(decide(me(20, true, false), locked, zombie(4, 5.5), zombie(5, 6))));
        // 有一只马上要打到我了：换过去。
        assertEquals(4, target(decide(me(20, true, false), locked, zombie(4, 2.5), zombie(5, 6))));
    }

    @Test
    void shootsFarOrExplosiveTargetsAndMeleesClose() {
        assertInstanceOf(Tactic.Shoot.class, decide(me(20, true, true), Tactic.Memory.FRESH, zombie(1, 9)));
        assertInstanceOf(Tactic.Melee.class, decide(me(20, true, true), Tactic.Memory.FRESH, zombie(1, 4)));
        assertInstanceOf(Tactic.Shoot.class, decide(me(20, true, true), Tactic.Memory.FRESH, creeper(1, 5, false)),
                "苦力怕能射就别凑近");
        Tactic.Memory gaveUp = new Tactic.Memory(-1, false, false, Set.of(), true, false);
        assertInstanceOf(Tactic.Melee.class, decide(me(20, true, true), gaveUp, zombie(1, 9)), "弓射不出去交回近战");
    }

    @Test
    void unreachableTargetsAreShotOrSkipped() {
        Tactic.Memory unreachable = new Tactic.Memory(-1, false, false, Set.of(1), false, false);
        assertInstanceOf(Tactic.Shoot.class, decide(me(20, true, true), unreachable, zombie(1, 5)));
        assertInstanceOf(Tactic.KeepClear.class, decide(me(20, true, false), unreachable, zombie(1, 3)),
                "贴到快够得着我了：避开它");
        assertInstanceOf(Tactic.Finished.class, decide(me(20, true, false), unreachable, zombie(1, 5)),
                "隔着坑走不到、又没打我：不为它站着干耗");
    }

    @Test
    void unreachableShooterInRangeIsLeftBehind() {
        // 骷髅走不到跟前、又射得到我，手上没弓：站着避开只会一直挨射，撤出它的射程。
        Tactic.Memory unreachable = new Tactic.Memory(-1, false, false, Set.of(2), false, false);
        Battlefield.Foe skeleton = foe(2, 6, ThreatAssessment.Kind.RANGED, false, true);
        Battlefield.Foe shooting = new Battlefield.Foe(skeleton.threat(), true, true, true, false);
        assertInstanceOf(Tactic.Withdraw.class, decide(me(20, true, false), unreachable, shooting));
        assertInstanceOf(Tactic.Shoot.class, decide(me(20, true, true), unreachable, shooting), "有弓就射回去");
    }

    @Test
    void outnumberedWithdrawsBeforeTheHealthLineBreaks() {
        // 六点血、一身铁甲（有效血量没破拒战线），被四只僵尸围着：按人数算打不过，趁还跑得动先撤。
        Battlefield.Me armoured = new Battlefield.Me(0, 64, 0, 0.3, 6, 17, 3.0, true, true, false, 3, 0);
        assertInstanceOf(Tactic.Withdraw.class, decide(armoured, Tactic.Memory.FRESH,
                zombie(1, 3), zombie(2, 4), zombie(3, 5), zombie(4, 6)));
        // 一只照打。
        assertInstanceOf(Tactic.Melee.class, decide(armoured, Tactic.Memory.FRESH, zombie(1, 3)));
    }

    @Test
    void finishedWhenNothingLeft() {
        assertInstanceOf(Tactic.Finished.class, decide(me(20, true, false), Tactic.Memory.FRESH));
    }

    private static int target(Tactic.Plan plan) {
        return assertInstanceOf(Tactic.Melee.class, plan).target().id();
    }
}
