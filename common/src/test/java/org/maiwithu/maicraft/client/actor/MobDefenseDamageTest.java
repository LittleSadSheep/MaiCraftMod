package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.combat.AttackPlan;
import org.maiwithu.maicraft.core.combat.Battlefield;
import org.maiwithu.maicraft.core.task.chain.MobDefenseChain;
import org.maiwithu.maicraft.core.task.combat.AttackCompanionTask;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskSelector;
import org.maiwithu.maicraft.task.TaskState;
import static org.maiwithu.maicraft.client.actor.CombatThreatsTest.check;

/** 验证伤害事件实际接入自卫、选敌、原生攻击和低血量撤退，而非只测试记录器。 */
public final class MobDefenseDamageTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        distantDamageTakesOverAndSelectsAttacker();
        lowHealthKeepsRetreatingFromDistantFire();
        retaliatesThroughNativeAttack();
        cancelsAnExpiredPendingStrike();
        defendsDuringLootWithoutDiscardingDeaths();
        System.out.println("MobDefenseDamageTest: damage triggers defense, native retaliation and retreat");
    }

    private static void distantDamageTakesOverAndSelectsAttacker() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var shooter = f.mob(11, 50); var bystander = f.mob(12, 2);
            var defense = new MobDefenseChain();
            check(!defense.canRun(f.h.player), "nearby hostiles alone do not trigger retaliation");
            Task work = new Task() {
                public TaskState tick(net.minecraft.client.player.LocalPlayer p) { return TaskState.RUNNING; }
                public void stop(net.minecraft.client.player.LocalPlayer p, StopReason why) { }
                public String name() { return "ongoing work"; }
            };
            check(TaskSelector.select(List.of(defense), null, work, List.of(), f.h.player) == work,
                    "ordinary work runs before any attack evidence");
            f.hit(null, shooter);
            check(TaskSelector.select(List.of(defense), null, work, List.of(), f.h.player) == defense,
                    "a remote shooter interrupts work without waiting for a model instruction");
            defense.tick(f.h.player);
            var fight = (AttackCompanionTask) field(defense, "fight");
            Battlefield field = survey(fight);
            check(field.byId(shooter.getId()).authorized() && field.byId(shooter.getId()).engaging(),
                    "the actual reflex child selects a shooter outside the local combat radius");
            check(!field.byId(bystander.getId()).authorized(), "a bystander never inherits the attacker's authorization");
            // With usable melee equipment, the existing plan approaches that exact attacker.
            var equipped = new Battlefield(field.effectiveHealth(), field.meleeReach(), true, false, false, field.foes());
            check(AttackPlan.decide(equipped, null).foeId() == shooter.getId(), "damage evidence reaches combat planning");
            f.h.level.entities.clear();
            defense.tick(f.h.player);
            check(TaskSelector.select(List.of(defense), null, work, List.of(), f.h.player) == work,
                    "work becomes eligible again after the threat has gone");
        }
    }

    private static void lowHealthKeepsRetreatingFromDistantFire() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var shooter = f.mob(11, 50); f.hit(null, shooter); f.h.player.setHealth(4);
            var task = fight(f);
            check(AttackPlan.decide(survey(task), null).action() == AttackPlan.Action.DISENGAGE,
                    "confirmed damage preserves the low-health retreat policy");
            check(invoke(task, "tickFlee") == TaskState.RUNNING,
                    "a shooter beyond 32 blocks must not make retreat report immediate safety");
            f.h.level.entities.clear();
            check(invoke(task, "tickFlee") == TaskState.FAILED,
                    "retreat can end once the loaded threat is gone");
            task.result(TaskState.FAILED);
        }
    }

    private static void retaliatesThroughNativeAttack() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var attacker = f.mob(11, 2); var bystander = f.mob(12, 1.3);
            f.hit(attacker, attacker);
            var task = fight(f);
            weapon(task);
            check(((Integer) field(task, "meleeVictimId")) == attacker.getId(),
                    "retaliation must skip a closer hostile that has not attacked");
            f.h.level.entities.remove(bystander.getId());
            Vec3 aim = attacker.getBoundingBox().getCenter().subtract(f.h.player.getEyePosition());
            f.h.player.setYRot((float) Math.toDegrees(Math.atan2(-aim.x, aim.z)));
            f.h.player.setXRot((float) -Math.toDegrees(Math.atan2(aim.y, aim.horizontalDistance())));
            f.h.nextTick(); weapon(task);
            check(f.h.mode.attacks == 1, "the attack loop must submit a real native attack request");
            attacker.damage = 1;
            f.h.nextTick(); weapon(task);
            var record = (AttackTaskRecord) field(task, "r");
            check(record.strikes(attacker.getId()) == 0 && f.h.mode.attacks == 1,
                    "one damage observation keeps the native confirmation pending");
            f.h.nextTick(); weapon(task);
            check(record.strikes(attacker.getId()) == 1 && f.h.mode.attacks == 1,
                    "synchronized damage confirms one strike without sending another click");
            task.result(TaskState.CANCELLED);
        }
    }

    private static void cancelsAnExpiredPendingStrike() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var attacker = f.mob(11, 2); f.hit(attacker, attacker);
            var task = fight(f); weapon(task);
            f.h.level.time += 200; f.h.nextTick(); weapon(task);
            check(field(task, "meleeAction") == null && f.h.mode.attacks == 0,
                    "an unsubmitted strike must not outlive its defense evidence");
            task.result(TaskState.CANCELLED);
        }
    }

    private static void defendsDuringLootWithoutDiscardingDeaths() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var corpse = f.mob(11, 3); corpse.dead = true;
            var task = fight(f);
            var record = (AttackTaskRecord) field(task, "r"); record.defeated(corpse.getId());
            Method beginLoot = AttackCompanionTask.class.getDeclaredMethod("beginLoot", int.class, Vec3.class);
            beginLoot.setAccessible(true); beginLoot.invoke(task, corpse.getId(), corpse.position());
            var attacker = f.mob(12, 2); f.hit(attacker, attacker);
            check(task.tick(f.h.player) == TaskState.RUNNING && ((Integer) field(task, "meleeVictimId")) == attacker.getId(),
                    "waiting for a previous kill's drops must not swallow a new attack");
            Vec3 aim = attacker.getBoundingBox().getCenter().subtract(f.h.player.getEyePosition());
            f.h.player.setYRot((float) Math.toDegrees(Math.atan2(-aim.x, aim.z)));
            f.h.player.setXRot((float) -Math.toDegrees(Math.atan2(aim.y, aim.horizontalDistance())));
            f.h.nextTick(); task.tick(f.h.player);
            check(f.h.mode.attacks == 1, "self-defense must still reach the native attack while loot is pending");
            attacker.damage = 1;
            f.h.nextTick(); task.tick(f.h.player); f.h.nextTick(); task.tick(f.h.player);
            attacker.dead = true;
            f.h.nextTick(); task.tick(f.h.player);
            var loot = field(task, "loot");
            check(((List<?>) field(loot, "deaths")).size() == 2 && record.defeated().size() == 2,
                    "resuming loot retains both the previous corpse and the newly defeated attacker");
            check(field(task, "meleeAction") == null, "no stale melee action remains when pickup resumes");
            task.result(TaskState.CANCELLED);
        }
    }

    static AttackCompanionTask fight(CombatThreatsTest.Fixture f) {
        var task = new AttackCompanionTask(f.h.player, new AttackTaskRecord("self-defense", 1000, List.of(), true));
        task.start(f.h.player);
        return task;
    }
    static Battlefield survey(AttackCompanionTask task) throws Exception { return (Battlefield) invoke(task, "surveyField"); }
    private static void weapon(AttackCompanionTask task) throws Exception {
        Method method = AttackCompanionTask.class.getDeclaredMethod("tickWeapon", Battlefield.class);
        method.setAccessible(true); method.invoke(task, survey(task));
    }
    static Object field(Object value, String name) throws Exception {
        return ActorControlTestHarness.field(value.getClass(), name).get(value);
    }
    static Object invoke(Object value, String name) throws Exception {
        Method method = value.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(value);
    }
}
