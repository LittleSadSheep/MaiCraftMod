// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/** 龙的打法：躲龙息压过一切、躲冲撞与火球、贴脸的交回普通打法、龙死收经验、血线回血、水晶先后、趴下砍头、飞着射、等龙落。 */
class DragonTacticTest {

    private static Battlefield.Me me(double health, boolean melee, boolean bow) {
        return new Battlefield.Me(0, 64, 20, 0.3, health, 10, 3.0, true, melee, bow, melee ? 7 : 0, 4);
    }

    /** 在 (x, y, z)、每刻速度 (vx, 0, vz)、在干 activity、干了 ticks 刻、离我 distance 的龙；龙头在身子北边。 */
    private static DragonArena.Dragon dragon(double x, double y, double z, double vx, double vz,
                                             DragonArena.Activity activity, int ticks, int healing, double distance) {
        AABB head = new AABB(x - 0.5, y, z - 7.0, x + 0.5, y + 1, z - 6.0);
        AABB neck = new AABB(x - 1.5, y, z - 7.0, x + 1.5, y + 3, z - 4.0);
        return new DragonArena.Dragon(1, "minecraft:ender_dragon", x, y, z, vx, 0, vz, head, neck, activity, ticks,
                150, 200, healing, true, distance);
    }

    private static DragonArena.Dragon circling(double distance) {
        return dragon(0, 100, -distance, 0, 0, DragonArena.Activity.CIRCLING, 0, -1, distance);
    }

    private static DragonArena.Dragon perched(DragonArena.Activity activity, int ticks) {
        return dragon(0.5, 64, 0.5, 0, 0, activity, ticks, -1, 20);
    }

    private static DragonArena.Crystal crystal(int id, double x, double z) {
        return new DragonArena.Crystal(id, x, 90, z, false, null, false);
    }

    private static DragonArena arena(long tick, DragonArena.Dragon dragon, List<DragonArena.Crystal> crystals,
                                     List<DragonArena.Cloud> clouds, List<DragonArena.Fireball> fireballs) {
        return new DragonArena(tick, dragon, crystals, clouds, fireballs, List.of(),
                new DragonArena.Portal(0.5, 64, 0.5, false), 20, false);
    }

    private static DragonTactic.Move decide(Battlefield.Me me, DragonArena arena, DragonTactic.Memory memory,
                                            Battlefield.Foe... foes) {
        return DragonTactic.decide(new Battlefield(me, List.of(foes), arena), memory);
    }

    private static Battlefield.Foe zombie(int id, double distance) {
        CombatSenses.Threat threat = new CombatSenses.Threat(id, UUID.randomUUID(), "x", 0, 64, 20 + distance,
                distance, ThreatAssessment.Kind.MELEE, false, true, true);
        return new Battlefield.Foe(threat, true, false, true, false);
    }

    @Test
    void standingInBreathComesBeforeEverything() {
        DragonArena breath = arena(0, perched(DragonArena.Activity.WATCHING, 0), List.of(crystal(5, 40, 0)),
                List.of(new DragonArena.Cloud(0, 64, 20, 5)), List.of());
        assertInstanceOf(DragonTactic.AvoidCloud.class, decide(me(4, true, true), breath, DragonTactic.Memory.FRESH,
                zombie(7, 1)), "血低、怪贴脸也先跑出龙息");
        // 龙吼到第 30 刻：按龙头朝向预先算的那一口也算。站在龙头正前方的先跑开。
        DragonArena roar = arena(0, perched(DragonArena.Activity.ROARING, 30), List.of(), List.of(), List.of());
        Battlefield.Me inFront = new Battlefield.Me(0.5, 64, -9, 0.3, 20, 10, 3.0, true, true, true, 7, 4);
        assertInstanceOf(DragonTactic.AvoidCloud.class, DragonTactic.decide(new Battlefield(inFront, List.of(), roar),
                DragonTactic.Memory.FRESH));
    }

    @Test
    void chargesAndFireballsAreDodgedAndBracedWhenTooLate() {
        // 龙从西边 40 格冲过来，27 刻后从我身上扫过：先躲，压过射水晶。
        DragonArena charge = arena(0, dragon(-40, 70, 20, 1.5, 0, DragonArena.Activity.CHARGING, 0, -1, 40),
                List.of(crystal(5, 40, 0)), List.of(), List.of());
        DragonTactic.DodgeSweep dodge = assertInstanceOf(DragonTactic.DodgeSweep.class,
                decide(me(20, true, true), charge, DragonTactic.Memory.FRESH));
        assertFalse(dodge.brace());
        // 只剩 5 刻、副手有盾：跑不开了，举盾。没盾照样跑。
        DragonArena close = arena(0, dragon(-7.5, 70, 20, 1.5, 0, DragonArena.Activity.CHARGING, 0, -1, 8),
                List.of(), List.of(), List.of());
        DragonTactic.Memory shield = new DragonTactic.Memory(false, Map.of(), Map.of(), true);
        assertTrue(assertInstanceOf(DragonTactic.DodgeSweep.class, decide(me(20, true, true), close, shield)).brace());
        assertFalse(assertInstanceOf(DragonTactic.DodgeSweep.class,
                decide(me(20, true, true), close, DragonTactic.Memory.FRESH)).brace());
        DragonArena fireball = arena(0, circling(60), List.of(), List.of(),
                List.of(new DragonArena.Fireball(9, 0, 84.9, 0, 0, -0.5, 0.5)));
        assertInstanceOf(DragonTactic.DodgeFireball.class, decide(me(20, true, true), fireball, DragonTactic.Memory.FRESH));
    }

    @Test
    void aFoeAboutToHitMeIsHandedBackToTheOrdinaryTactic() {
        DragonArena calm = arena(0, circling(60), List.of(), List.of(), List.of());
        assertNull(decide(me(20, true, true), calm, DragonTactic.Memory.FRESH, zombie(7, 1)));
        // 普通打法接手：近身打它。
        Tactic.Plan plan = Tactic.decide(new Battlefield(me(20, true, false), List.of(zombie(7, 1)), calm),
                Tactic.Memory.FRESH);
        assertInstanceOf(Tactic.Melee.class, plan);
        // 还远着的不交回：接着打龙。
        assertInstanceOf(DragonTactic.WaitForPerch.class, decide(me(20, true, false), calm, DragonTactic.Memory.FRESH,
                zombie(7, 10)));
    }

    @Test
    void dyingDragonMeansCollectEvenWhenHurt() {
        DragonArena dying = arena(0, dragon(0, 80, 0, 0, 0, DragonArena.Activity.DYING, 10, -1, 25),
                List.of(crystal(5, 40, 0)), List.of(), List.of());
        assertInstanceOf(DragonTactic.Collect.class, decide(me(5, true, true), dying, DragonTactic.Memory.FRESH));
    }

    @Test
    void restsBelowTwelveAndComesBackAtEighteen() {
        DragonArena calm = arena(0, circling(60), List.of(crystal(5, 40, 0)), List.of(), List.of());
        assertInstanceOf(DragonTactic.Rest.class, decide(me(12, true, true), calm, DragonTactic.Memory.FRESH));
        assertInstanceOf(DragonTactic.ShootCrystal.class, decide(me(14, true, true), calm, DragonTactic.Memory.FRESH),
                "没在回血、血 14 照打");
        DragonTactic.Memory resting = new DragonTactic.Memory(true, Map.of(), Map.of(), false);
        assertInstanceOf(DragonTactic.Rest.class, decide(me(17, true, true), calm, resting), "回到 18 才回来");
        assertInstanceOf(DragonTactic.ShootCrystal.class, decide(me(18, true, true), calm, resting));
    }

    @Test
    void healingCrystalFirstThenNearestAndGivenUpOnesAreSkipped() {
        DragonArena.Crystal near = crystal(5, 10, 20);
        DragonArena.Crystal far = crystal(6, 40, -30);
        DragonArena arena = arena(0, dragon(0, 100, -60, 0, 0, DragonArena.Activity.CIRCLING, 0, 6, 80),
                List.of(near, far), List.of(), List.of());
        assertEquals(6, assertInstanceOf(DragonTactic.ShootCrystal.class,
                decide(me(20, true, true), arena, DragonTactic.Memory.FRESH)).crystal().id(), "正给龙回血的先射");
        DragonArena noBeam = arena(0, circling(80), List.of(near, far), List.of(), List.of());
        assertEquals(5, assertInstanceOf(DragonTactic.ShootCrystal.class,
                decide(me(20, true, true), noBeam, DragonTactic.Memory.FRESH)).crystal().id(), "其余近的先");
        DragonTactic.Memory gaveUp = new DragonTactic.Memory(false, Map.of(5, "铁栏笼挡着"), Map.of(), false);
        assertEquals(6, assertInstanceOf(DragonTactic.ShootCrystal.class,
                decide(me(20, true, true), noBeam, gaveUp)).crystal().id());
        DragonTactic.Memory allGone = new DragonTactic.Memory(false, Map.of(5, "铁栏笼挡着", 6, "射不到"), Map.of(), false);
        assertInstanceOf(DragonTactic.WaitForPerch.class, decide(me(20, true, false), noBeam, allGone),
                "都放弃了：去打龙");
        assertInstanceOf(DragonTactic.WaitForPerch.class, decide(me(20, true, false), noBeam, DragonTactic.Memory.FRESH),
                "没有能射的：水晶不硬打（缺弓箭由任务说）");
    }

    @Test
    void crystalsWithBystandersWaitOrAreGivenUp() {
        DragonArena.Crystal guarded = new DragonArena.Crystal(5, 10, 90, 20, false, "爆炸范围里有别的玩家 Steve", false);
        DragonArena.Crystal other = crystal(6, 40, -30);
        DragonArena both = arena(0, circling(80), List.of(guarded, other), List.of(), List.of());
        assertEquals(6, assertInstanceOf(DragonTactic.ShootCrystal.class,
                decide(me(20, true, true), both, DragonTactic.Memory.FRESH)).crystal().id(), "爆炸范围里有别人的先跳过");
        DragonArena alone = arena(0, circling(80), List.of(guarded), List.of(), List.of());
        DragonTactic.SkipCrystal skipped = assertInstanceOf(DragonTactic.SkipCrystal.class,
                decide(me(20, true, true), alone, DragonTactic.Memory.FRESH));
        assertEquals(5, skipped.crystal().id(), "只剩它还有别人：放弃并写明");
        assertEquals("爆炸范围里有别的玩家 Steve", skipped.why(), "结果里写明挡着的是谁");
        // 爆炸范围碰到记住的区域：和有别人一样先射别的，只剩它时放弃并写明是哪块地盘。
        DragonArena.Crystal nearBase = new DragonArena.Crystal(8, 10, 90, 20, false, "爆炸范围碰到记住的区域「末地小屋」", false);
        assertEquals(6, assertInstanceOf(DragonTactic.ShootCrystal.class, decide(me(20, true, true),
                arena(0, circling(80), List.of(nearBase, other), List.of(), List.of()), DragonTactic.Memory.FRESH))
                .crystal().id(), "碰到记住区域的先跳过");
        assertEquals("爆炸范围碰到记住的区域「末地小屋」", assertInstanceOf(DragonTactic.SkipCrystal.class,
                decide(me(20, true, true), arena(0, circling(80), List.of(nearBase), List.of(), List.of()),
                        DragonTactic.Memory.FRESH)).why());
        DragonArena.Crystal enderman = new DragonArena.Crystal(7, 10, 90, 20, false, null, true);
        DragonArena waiting = arena(300, circling(80), List.of(enderman), List.of(), List.of());
        DragonTactic.Memory justSeen = new DragonTactic.Memory(false, Map.of(), Map.of(7, 250L), false);
        assertInstanceOf(DragonTactic.WaitForPerch.class, decide(me(20, true, true), waiting, justSeen),
                "末影人在爆炸范围里：先去打龙，等它走开");
        DragonTactic.Memory waitedLong = new DragonTactic.Memory(false, Map.of(), Map.of(7, 100L), false);
        assertInstanceOf(DragonTactic.ShootCrystal.class, decide(me(20, true, true), waiting, waitedLong),
                "等够十秒照射");
    }

    @Test
    void perchedDragonGetsItsHeadStruckAndArrowsAreNotWasted() {
        DragonArena watching = arena(0, perched(DragonArena.Activity.WATCHING, 5), List.of(), List.of(), List.of());
        assertFalse(assertInstanceOf(DragonTactic.StrikeHead.class,
                decide(me(20, true, true), watching, DragonTactic.Memory.FRESH)).backOff(), "趴着时有弓也不射，砍头");
        DragonArena roaring = arena(0, perched(DragonArena.Activity.ROARING, 30), List.of(), List.of(), List.of());
        assertTrue(assertInstanceOf(DragonTactic.StrikeHead.class,
                decide(me(20, true, true), roaring, DragonTactic.Memory.FRESH)).backOff(), "吼到第 30 刻先退出龙头前面");
        DragonArena breathing = arena(0, perched(DragonArena.Activity.BREATHING, 50), List.of(), List.of(), List.of());
        assertTrue(assertInstanceOf(DragonTactic.StrikeHead.class,
                decide(me(20, true, true), breathing, DragonTactic.Memory.FRESH)).backOff());
        assertInstanceOf(DragonTactic.WaitForPerch.class, decide(me(20, false, true), watching, DragonTactic.Memory.FRESH),
                "没有近战武器：趴着的龙打不动，等它飞起来");
    }

    @Test
    void flyingDragonIsShotOnlyWhenCloseAndSeen() {
        assertInstanceOf(DragonTactic.ShootDragon.class,
                decide(me(20, true, true), arena(0, circling(30), List.of(), List.of(), List.of()), DragonTactic.Memory.FRESH));
        assertInstanceOf(DragonTactic.WaitForPerch.class,
                decide(me(20, true, true), arena(0, circling(40), List.of(), List.of(), List.of()), DragonTactic.Memory.FRESH),
                "32 格外不射");
        assertInstanceOf(DragonTactic.WaitForPerch.class,
                decide(me(20, true, false), arena(0, circling(20), List.of(), List.of(), List.of()), DragonTactic.Memory.FRESH),
                "没弓就等它落");
    }

    @Test
    void tacticDispatchesToTheDragonOnlyWithAnArena() {
        DragonArena calm = arena(0, circling(60), List.of(), List.of(), List.of());
        assertInstanceOf(Tactic.Dragon.class, Tactic.decide(new Battlefield(me(20, true, false), List.of(), calm),
                Tactic.Memory.FRESH));
        assertInstanceOf(Tactic.Finished.class, Tactic.decide(new Battlefield(me(20, true, false), List.of()),
                Tactic.Memory.FRESH), "没有龙场照旧");
        // 点着引信的苦力怕仍然最先躲。
        CombatSenses.Threat creeper = new CombatSenses.Threat(9, UUID.randomUUID(), "minecraft:creeper", 0, 64, 23, 3,
                ThreatAssessment.Kind.EXPLOSIVE, true, true, true);
        assertInstanceOf(Tactic.EvadeBlast.class, Tactic.decide(new Battlefield(me(20, true, false),
                List.of(new Battlefield.Foe(creeper, true, false, true, false)), calm), Tactic.Memory.FRESH));
    }

    @Test
    void endermenAreFoughtInMeleeNotShot() {
        // 惹毛的末影人在十格外：有弓也不射（它会瞬移躲开箭），近身打。
        CombatSenses.Threat enderman = new CombatSenses.Threat(8, UUID.randomUUID(), "minecraft:enderman", 10, 64, 0, 10,
                ThreatAssessment.Kind.MELEE, false, true, true, 0.3, 9.7, false, 2.0, false, true);
        Battlefield.Me me = new Battlefield.Me(0, 64, 0, 0.3, 20, 10, 3.0, true, true, true, 7, 4);
        Tactic.Plan plan = Tactic.decide(new Battlefield(me, List.of(new Battlefield.Foe(enderman, true, true, true, true))),
                new Tactic.Memory(-1, false, false, Set.of(), false, false));
        assertInstanceOf(Tactic.Melee.class, plan);
    }
}
