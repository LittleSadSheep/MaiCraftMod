package org.maiwithu.maicraft.client.actor;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.combat.Battlefield;
import org.maiwithu.maicraft.core.task.combat.AttackCompanionTask;
import org.maiwithu.maicraft.task.TaskState;
import static org.maiwithu.maicraft.client.actor.CombatThreatsTest.check;
import static org.maiwithu.maicraft.client.actor.MobDefenseDamageTest.field;

/** 举盾、松盾、近战与换成弓战斗时，真实原生端口必须始终保留唯一动作所有者。 */
public final class CombatHandOwnershipTest {
    public static void main(String[] args) throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var attacker = f.mob(11, 2); f.hit(attacker, attacker);
            f.h.inventory.setItem(40, new ItemStack(Items.SHIELD));
            ActorControlTestHarness.field(Player.class, "cooldowns").set(f.h.player, new ItemCooldowns());
            f.h.mode.itemUse = p -> p.startUsingItem(InteractionHand.OFF_HAND);
            var task = MobDefenseDamageTest.fight(f);
            cooldown(f, 0); hand(task, "tickShield"); hand(task, "tickWeapon");
            check(f.h.player.isUsingItem() && f.h.mode.items == 1 && field(task, "meleeAction") == null,
                    "cooldown waits behind the owned shield use instead of overwriting it");
            next(f); cooldown(f, 100); hand(task, "tickShield"); hand(task, "tickWeapon");
            check(!f.h.player.isUsingItem() && f.h.mode.releases == 1 && field(task, "meleeAction") == null,
                    "shield release consumes this tick before any weapon selection or strike");
            next(f); hand(task, "tickShield"); hand(task, "tickWeapon");
            check(field(task, "meleeAction") != null && f.h.mode.items == 1,
                    "an available strike keeps the shield lowered rather than repeatedly raising it");
            ActorControlTestHarness.field(task.getClass(), "bowFighting").setBoolean(task, true);
            hand(task, "tickWeapon");
            check(field(task, "meleeAction") == null && f.h.mode.attacks == 0,
                    "switching to ranged combat cancels a pending melee click before the bow owns the hand");
            ActorControlTestHarness.field(task.getClass(), "bowFighting").setBoolean(task, false);
            next(f); cooldown(f, 0); hand(task, "tickShield");
            check(f.h.player.isUsingItem() && f.h.mode.items == 2, "shield defense resumes after the attack window");
            next(f); ActorControlTestHarness.field(task.getClass(), "bowFighting").setBoolean(task, true);
            hand(task, "tickShield");
            check(!f.h.player.isUsingItem() && f.h.mode.releases == 2,
                    "ranged combat releases an existing shield instead of silently leaving use held");
            next(f); task.result(TaskState.CANCELLED);
        }
        System.out.println("CombatHandOwnershipTest: shield and weapon ownership transitions passed");
    }
    private static void hand(AttackCompanionTask task, String method) throws Exception {
        var action = AttackCompanionTask.class.getDeclaredMethod(method, Battlefield.class); action.setAccessible(true);
        action.invoke(task, MobDefenseDamageTest.survey(task));
    }
    private static void cooldown(CombatThreatsTest.Fixture f, int ticks) throws Exception {
        ActorControlTestHarness.field(Player.class, "attackStrengthTicker").setInt(f.h.player, ticks);
    }
    private static void next(CombatThreatsTest.Fixture f) throws Exception {
        f.h.nextTick();
        var context = ClientRuntime.requireContext(f.h.player);
        ((DefaultNativeActionPort) context.actions()).advance(context);
    }
}
