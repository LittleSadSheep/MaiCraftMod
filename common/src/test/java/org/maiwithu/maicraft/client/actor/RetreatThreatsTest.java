package org.maiwithu.maicraft.client.actor;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.combat.RetreatThreats;
import org.maiwithu.maicraft.core.task.combat.AttackCompanionTask;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import static org.maiwithu.maicraft.client.actor.CombatThreatsTest.check;
import static org.maiwithu.maicraft.client.actor.MobDefenseDamageTest.invoke;

/** 无关远处敌怪和隔墙敌怪不延长撤离；实际追兵、远程伤害和新近危险继续保留。 */
public final class RetreatThreatsTest {
    public static void main(String[] args) throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var hidden = f.mob(11, 8); var distant = f.mob(12, 30);
            f.h.set(new BlockPos(4, 1, 3), Blocks.STONE.defaultBlockState());
            f.h.set(new BlockPos(4, 2, 3), Blocks.STONE.defaultBlockState());
            var threats = new RetreatThreats();
            check(!f.h.player.hasLineOfSight(hidden), "the near ambient hostile must really be hidden by the fixture wall");
            check(threats.observe(f.h.player, 40, 14).isEmpty(),
                    "hidden ambient mobs and distant unengaged mobs cannot start a new retreat leg");
            var task = new AttackCompanionTask(f.h.player, new AttackTaskRecord("ambient-retreat", 1000, List.of(), true));
            check(!(boolean) invoke(task, "retreatThreatsPresent"), "the combat executor uses the narrowed retreat predicate");
            // 已收到伤害的射手即使隔墙、远于普通近圈，也立即成为明确追击对象。
            f.hit(distant, distant);
            check(threats.observe(f.h.player, 40, 14).equals(List.of(distant)), "native damage keeps the actual distant attacker");
            f.h.level.time += 201;
            check(threats.observe(f.h.player, 40, 14).equals(List.of(distant)),
                    "damage-memory expiry cannot forget the same still-near pursuer during a retreat");
            f.h.position(new Vec3(-12, 1, 3.5));
            check(threats.observe(f.h.player, 40, 14).isEmpty(), "real separation retires the old pursuer without adopting ambient mobs");
            // 明确的新近可见敌怪仍会加入避险；编号复用不能继承旧实体的追击身份。
            f.h.position(new Vec3(.5, 1, 3.5)); var nearby = f.mob(13, 2);
            check(threats.observe(f.h.player, 40, 14).equals(List.of(nearby)), "new visible close danger is included");
            f.mob(13, 30);
            check(threats.observe(f.h.player, 40, 14).isEmpty(), "a replacement entity cannot inherit the old pursuer identity");
        }
        try (var f = new CombatThreatsTest.Fixture()) {
            // 近圈外持续射来的箭不靠“附近没怪”忽略；真实伤害记忆过期且射手已经远离后才能结束。
            var archer = f.mob(21, 60); f.hit(archer, archer);
            var task = new AttackCompanionTask(f.h.player, new AttackTaskRecord("far-retreat", 1000, List.of(), true));
            check((boolean) invoke(task, "retreatThreatsPresent"), "a recent remote hit keeps retreat active beyond the scan radius");
            f.h.level.time += 201;
            check(!(boolean) invoke(task, "retreatThreatsPresent"), "a separated attacker with expired damage evidence no longer holds retreat");
        }
        interlockExit();
        System.out.println("RetreatThreatsTest: active pursuit, ambient exclusion and native damage memory passed");
    }

    /** 深层互锁：血量在拒战线下、无安全食物、饥饿低于回血线三者同时成立时，撤退回执点破处境并给出全部出路。 */
    private static void interlockExit() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var player = f.h.player;
            player.setHealth(4.0f);
            player.getFoodData().setFoodLevel(10);
            f.h.inventory.clearContent();
            // 腐肉带食用效果，安全食品策略不静默选择——它不能解开互锁，只有无效果食物才算出口。
            f.h.inventory.setItem(0, new ItemStack(Items.ROTTEN_FLESH));
            check(AttackCompanionTask.foodCombatLocked(player),
                    "effect-bearing food alone must not unlock the food-combat recovery loop");
            String locked = AttackCompanionTask.retreatFailureMessage(player);
            check(locked.contains("food-combat recovery loop is interlocked"),
                    "the too-hurt receipt must name the interlock when health, safe food and hunger all block recovery");
            check(locked.contains("maicraft:suicide death reset"),
                    "the interlocked receipt must still offer the authorized death reset");
            f.h.inventory.setItem(0, new ItemStack(Items.COOKED_BEEF));
            check(!AttackCompanionTask.foodCombatLocked(player),
                    "effect-free food in inventory means the loop is not interlocked");
            String fed = AttackCompanionTask.retreatFailureMessage(player);
            check(!fed.contains("interlocked") && fed.contains("maicraft:suicide death reset"),
                    "a body with safe food keeps the ordinary low-health receipt without the interlock passage");
        }
    }
}
