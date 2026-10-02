package org.maiwithu.maicraft.client.actor;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.core.combat.Battlefield;
import org.maiwithu.maicraft.core.task.combat.AttackCompanionTask;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import static org.maiwithu.maicraft.client.actor.CombatThreatsTest.check;
import static org.maiwithu.maicraft.client.actor.MobDefenseDamageTest.field;
import static org.maiwithu.maicraft.client.actor.MobDefenseDamageTest.invoke;

/** 原生已出刀 -> 尸体先出现 -> 只读确认回执；验证阶段切换不漏账，也不凭死亡或消失补造攻击。 */
public final class MeleeReceiptSettlementTest {
    public static void main(String[] args) throws Exception {
        lethalReceiptSettlesDuringLoot();
        deathCannotConfirmAnUncertainReceipt();
        confirmedReceiptSurvivesTargetLoss();
        noReceiptMeansNoStrike();
        System.out.println("MeleeReceiptSettlementTest: 致命一击回执、未知结果与幂等记账通过");
    }

    private static void lethalReceiptSettlesDuringLoot() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var foe = f.mob(11, 2); var task = task(f); submit(f, task, foe.position());
            var record = (AttackTaskRecord) field(task, "r"); foe.dead = true;
            f.h.nextTick(); task.tick(f.h.player);
            check(record.strikes() == 0 && record.defeated().contains(11), "死亡先记胜负，原回执未确认不能提前算出刀");
            f.h.nextTick(); task.tick(f.h.player);
            check(record.strikes() == 1 && f.h.mode.attacks == 1, "进入拾取后仍收取原回执，不能重放致命一击");
            f.h.nextTick(); task.tick(f.h.player); task.result(TaskState.CANCELLED);
            check(record.strikes() == 1 && f.h.mode.attacks == 1, "重复推进和清理不重复记账");
        }
    }

    private static void deathCannotConfirmAnUncertainReceipt() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var foe = f.mob(11, 2); var task = task(f); submit(f, task, foe.position());
            var action = (Interaction) field(task, "meleeAction");
            var receipt = (NativeActionReceipt) field(action, "receipt");
            // 保留真实提交的回执，只让测试服务端不提供确认；即使目标随后死去，战斗层也不能越过回执判据。
            ActorControlTestHarness.field(NativeActionReceipt.class, "confirmation").set(receipt, NativeConfirmation.pending());
            foe.dead = true;
            for (int i = 0; i < 22; i++) { f.h.nextTick(); task.tick(f.h.player); }
            var record = (AttackTaskRecord) field(task, "r");
            check(receipt.status() == NativeActionReceipt.Status.UNCERTAIN && record.strikes() == 0
                    && record.defeated().contains(11) && f.h.mode.attacks == 1, "未知回执保留未知，死亡不补命中也不触发重放");
            task.result(TaskState.CANCELLED);
        }
    }

    private static void confirmedReceiptSurvivesTargetLoss() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var foe = f.mob(11, 2); var task = task(f); submit(f, task, foe.position());
            var receipt = (NativeActionReceipt) field(field(task, "meleeAction"), "receipt");
            receipt.finish(NativeActionReceipt.Status.CONFIRMED_APPLIED, "测试中已到达的原生确认");
            f.h.level.entities.remove(11);
            check(!(Boolean) invoke(task, "settleSubmittedMelee"), "读取冻结确认不要求目标继续存在");
            invoke(task, "settleFinishedTargets"); task.result(TaskState.CANCELLED);
            var record = (AttackTaskRecord) field(task, "r");
            check(record.strikes() == 1 && record.lost().contains(11) && record.defeated().isEmpty()
                    && f.h.mode.attacks == 1, "确认出刀与目标丢失分别报告，消失不能冒充击败");
        }
    }

    private static void noReceiptMeansNoStrike() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var foe = f.mob(11, 2); var task = task(f); foe.dead = true;
            f.h.nextTick(); task.tick(f.h.player); task.result(TaskState.CANCELLED);
            check(((AttackTaskRecord) field(task, "r")).strikes() == 0 && f.h.mode.attacks == 0,
                    "没有原生攻击回执时，即使目标死亡也不能记一刀");
        }
    }

    private static AttackCompanionTask task(CombatThreatsTest.Fixture f) {
        var task = new AttackCompanionTask(f.h.player, new AttackTaskRecord("receipt-settlement", 1000, List.of(11), false));
        task.start(f.h.player); return task;
    }

    private static void submit(CombatThreatsTest.Fixture f, AttackCompanionTask task, Vec3 target) throws Exception {
        // 实际准星命中后经生产攻击端口提交一次，后续测试只能读取这份回执。
        Vec3 direction = target.add(0, .9, 0).subtract(f.h.player.getEyePosition());
        f.h.player.setYRot((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)));
        f.h.player.setXRot((float) -Math.toDegrees(Math.atan2(direction.y, direction.horizontalDistance())));
        var weapon = AttackCompanionTask.class.getDeclaredMethod("tickWeapon", Battlefield.class); weapon.setAccessible(true);
        weapon.invoke(task, MobDefenseDamageTest.survey(task)); f.h.nextTick();
        weapon.invoke(task, MobDefenseDamageTest.survey(task));
        check(f.h.mode.attacks == 1, "先确认只有一次原生提交，再改变同步观察");
    }
}
