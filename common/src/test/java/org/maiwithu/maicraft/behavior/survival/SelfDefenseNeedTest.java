// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.kernel.interrupt.SurvivalNeed;
import org.maiwithu.maicraft.kernel.interrupt.ThreatResponder;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 被攻击的自卫分流：SOON 报急、血线破升 NOW、主任务自己在打且接得住时让位。 */
class SelfDefenseNeedTest {

    /** 刻号替身。 */
    private record Tick(long gameTick) implements TickContext {
        @Override public org.maiwithu.maicraft.game.player.PlayerContext player() {
            throw new IllegalStateException("分流测试不碰角色对象");
        }
    }

    /** 感观替身：给固定的威胁、伤害证据与资质。 */
    private record FakeSenses(List<CombatSenses.Threat> threats, List<CombatSenses.Attacker> attackers,
                              CombatSenses.CombatProfile profile) implements CombatSenses {
        @Override public List<CombatSenses.Threat> threats(TickContext context, double radius) { return threats; }

        @Override public List<CombatSenses.Attacker> recentAttackers(TickContext context) { return attackers; }

        @Override public CombatSenses.CombatProfile profile(TickContext context) { return profile; }

        @Override public net.minecraft.world.entity.Entity entityById(TickContext context, int entityId) { return null; }
    }

    /** 什么都不做的任务替身：只占"主任务"这个位置。 */
    private static final Task IDLE_TASK = new Task() {
        @Override public org.maiwithu.maicraft.kernel.task.TickResult tick(TickContext context) { return null; }
        @Override public org.maiwithu.maicraft.kernel.result.TaskResult close(
                org.maiwithu.maicraft.kernel.task.CloseReason reason) { return null; }
        @Override public void pause() {}
        @Override public String describe() { return "占位的任务"; }
    };

    /** 接得住威胁的主任务替身。 */
    private static final Task CONFIDENT_FIGHT = new ConfidentFightTask();

    /** 既是任务，又声明接得住眼前的威胁。 */
    private static final class ConfidentFightTask implements Task, ThreatResponder {
        @Override public boolean confidentAgainstCurrentThreats() { return true; }
        @Override public org.maiwithu.maicraft.kernel.task.TickResult tick(TickContext context) { return null; }
        @Override public org.maiwithu.maicraft.kernel.result.TaskResult close(
                org.maiwithu.maicraft.kernel.task.CloseReason reason) { return null; }
        @Override public void pause() {}
        @Override public String describe() { return "在打的活"; }
    }

    private static final TickContext TICK = new Tick(0);

    private static CombatSenses.CombatProfile profile(double health) {
        return new CombatSenses.CombatProfile(health, 0,
                Optional.of(new WeaponChoice.Picked("minecraft:iron_sword", WeaponChoice.Weapon.SWORD_OR_AXE)),
                0);
    }

    /** 看得见角色、亮着攻击标记的僵尸：正冲着她来。 */
    private static CombatSenses.Threat zombie(double distance) {
        return new CombatSenses.Threat(1, UUID.randomUUID(), "minecraft:zombie",
                10, 64, 10, distance, ThreatAssessment.Kind.MELEE, false, true, true);
    }

    private static CombatSenses.Threat skeleton(UUID id, boolean visible, boolean aggressive) {
        return new CombatSenses.Threat(2, id, "minecraft:skeleton",
                10, 58, 10, 12, ThreatAssessment.Kind.RANGED, false, visible, aggressive);
    }

    private static CombatSenses.Threat creeper(double distance, boolean visible) {
        return new CombatSenses.Threat(3, UUID.randomUUID(), "minecraft:creeper",
                10, 64, 10, distance, ThreatAssessment.Kind.EXPLOSIVE, false, visible, false);
    }

    private static Urgency urgencyWith(List<CombatSenses.Threat> threats, List<CombatSenses.Attacker> attackers) {
        return new SelfDefenseNeed(new FakeSenses(threats, attackers, profile(20)), null, null).urgency(TICK, null);
    }

    @Test
    void threatenedMeansSoon() {
        SelfDefenseNeed need = new SelfDefenseNeed(
                new FakeSenses(List.of(zombie(5)), List.of(), profile(20)),
                null, null);
        assertEquals(Urgency.SOON, need.urgency(TICK, null));
    }

    @Test
    void lowHealthWhileAttackedMeansNow() {
        // 4 颗心裸奔挨打：折算后的有效血量已在拒战线下，立刻处理。
        SelfDefenseNeed need = new SelfDefenseNeed(
                new FakeSenses(List.of(zombie(5)),
                        List.of(new CombatSenses.Attacker(UUID.randomUUID(), "minecraft:zombie", 0, "被攻击")),
                        profile(6)),
                null, null);
        assertEquals(Urgency.NOW, need.urgency(TICK, CONFIDENT_FIGHT), "血线破了本能说了算，主任务是打仗也让位");
    }

    @Test
    void confidentMainTaskTakesOver() {
        // 主任务自己在打且接得住：SOON 不插。
        SelfDefenseNeed need = new SelfDefenseNeed(
                new FakeSenses(List.of(zombie(5)), List.of(), profile(20)),
                null, null);
        assertNull(need.urgency(TICK, CONFIDENT_FIGHT));
        // 主任务不是打仗的：照插。
        assertEquals(Urgency.SOON, need.urgency(TICK, IDLE_TASK));
    }

    @Test
    void monstersThatAreNotAfterHerDoNotInterrupt() {
        // 脚下矿洞里的骷髅：看不见、没亮攻击标记，也没打过她，干活不被打断。
        UUID underground = UUID.randomUUID();
        assertNull(urgencyWith(List.of(skeleton(underground, false, false)), List.of()));
        // 看得见但在闲逛：同样不打断。
        assertNull(urgencyWith(List.of(skeleton(underground, true, false)), List.of()));
        // 看得见且拉弓瞄着：自卫。
        assertEquals(Urgency.SOON, urgencyWith(List.of(skeleton(underground, true, true)), List.of()));
        // 看不见但十秒内真射中过她：照样自卫。
        assertEquals(Urgency.SOON, urgencyWith(List.of(skeleton(underground, false, false)),
                List.of(new CombatSenses.Attacker(underground, "minecraft:skeleton", 0, "被射中"))));
    }

    @Test
    void aCreeperCloseAndInSightIsDealtWithBeforeItSwells() {
        assertEquals(Urgency.SOON, urgencyWith(List.of(creeper(6, true)), List.of()));
        assertNull(urgencyWith(List.of(creeper(6, false)), List.of()), "墙后的苦力怕不提前动手");
        assertNull(urgencyWith(List.of(creeper(12, true)), List.of()), "8 格外看得见的苦力怕先不管");
    }

    @Test
    void noThreatNoUrgency() {
        SelfDefenseNeed need = new SelfDefenseNeed(
                new FakeSenses(List.of(), List.of(), profile(20)), null, null);
        assertNull(need.urgency(TICK, null));
    }
}
