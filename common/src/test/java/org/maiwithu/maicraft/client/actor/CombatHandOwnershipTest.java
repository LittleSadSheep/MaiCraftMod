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
        raisedShieldOutlivesVanillaReleaseChecks();
        abandonedItemUseFreesThePort();
        finishedReceiptStaysReadableAfterReplacement();
        System.out.println("CombatHandOwnershipTest: shield and weapon ownership transitions passed");
    }

    // 举着不等于结束：使用键租约两刻过期，任务必须每刻推进同一次持用，否则原版 handleKeybinds
    // 把盾当松手放掉，回执永远停在 PENDING，后面的近战一次都提交不出去。
    private static void raisedShieldOutlivesVanillaReleaseChecks() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var attacker = f.mob(11, 2); f.hit(attacker, attacker);
            f.h.inventory.setItem(40, new ItemStack(Items.SHIELD));
            ActorControlTestHarness.field(Player.class, "cooldowns").set(f.h.player, new ItemCooldowns());
            f.h.mode.itemUse = p -> p.startUsingItem(InteractionHand.OFF_HAND);
            var task = MobDefenseDamageTest.fight(f);
            cooldown(f, 0); hand(task, "tickShield"); hand(task, "tickWeapon");
            check(f.h.player.isUsingItem() && f.h.mode.items == 1 && field(task, "meleeAction") == null,
                    "an unready strike raises the shield through exactly one native use");
            for (int tick = 0; tick < 20; tick++) {
                // 与 Minecraft.handleKeybinds 的原版分支相同：没被租约投影按住的使用当场松手。
                if (f.h.player.isUsingItem() && !ItemUseInputLease.project(f.h.h.minecraft, false)) {
                    f.h.mode.releaseUsingItem(f.h.player);
                }
                check(f.h.player.isUsingItem(), "a raised shield must outlive vanilla's per-tick release check");
                next(f); hand(task, "tickShield"); hand(task, "tickWeapon");
                check(field(task, "shieldAction") != null && f.h.mode.items == 1 && f.h.mode.releases == 0,
                        "holding the shield renews the owned input instead of replaying or releasing the use");
            }
            task.result(TaskState.CANCELLED);
        }
    }

    // 租约失效（界面、控制权或所有者变化）后原版会把这面盾放掉，而手里的盾没有换过：
    // 扣数与组件证据都不会出现，回执只能停在 PENDING。它必须退役，端口留给下一次举盾，
    // 否则任务会卡在这次持用上直到 deadline，期间连第一次点击都提交不出去。
    private static void abandonedItemUseFreesThePort() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var attacker = f.mob(11, 2); f.hit(attacker, attacker);
            f.h.inventory.setItem(40, new ItemStack(Items.SHIELD));
            ActorControlTestHarness.field(Player.class, "cooldowns").set(f.h.player, new ItemCooldowns());
            f.h.mode.itemUse = p -> p.startUsingItem(InteractionHand.OFF_HAND);
            var task = MobDefenseDamageTest.fight(f);
            cooldown(f, 0); hand(task, "tickShield");
            check(f.h.player.isUsingItem() && f.h.mode.items == 1, "the shield is raised before its lease goes away");
            ItemUseInputLease.release(field(task, "shieldAction"));
            for (int tick = 0; tick < 5 && field(task, "shieldAction") != null; tick++) {
                // 与 Minecraft.handleKeybinds 的原版分支相同：没有投影按住的使用当场被松开。
                if (f.h.player.isUsingItem() && !ItemUseInputLease.project(f.h.h.minecraft, false)) {
                    f.h.mode.releaseUsingItem(f.h.player);
                }
                next(f); hand(task, "tickShield");
            }
            check(field(task, "shieldAction") == null,
                    "an abandoned use stops instead of polling an unconfirmable receipt forever");
            check(f.h.inventory.getItem(40).is(Items.SHIELD),
                    "the identical shield stayed in hand, so no native evidence could ever confirm that use");
            hand(task, "tickShield"); hand(task, "tickWeapon");
            check(f.h.player.isUsingItem() && f.h.mode.items == 2,
                    "the freed port lets the next defense click reach the native use path");
            task.result(TaskState.CANCELLED);
        }
    }

    // 迟到的持有者仍要读回自己已终结的回执：端口可能已经先一步替它确认，或被后来者合法接管。
    private static void finishedReceiptStaysReadableAfterReplacement() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var actions = f.h.h.actions;
            var first = actions.selectHotbar(ClientRuntime.requireContext(f.h.player), 0, 20);
            for (int tick = 0; tick < 4 && !first.terminal(); tick++) next(f);
            check(first.terminal() && first.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED,
                    "the per-tick runtime drain confirms a selection before its owner polls it");
            next(f);
            var second = actions.selectHotbar(ClientRuntime.requireContext(f.h.player), 1, 20);
            check(actions.poll(ClientRuntime.requireContext(f.h.player), first) == first && first.terminal(),
                    "a late poll still reads the finished receipt instead of failing its owning task");
            check(actions.poll(ClientRuntime.requireContext(f.h.player), second) == second && !second.terminal(),
                    "the action that took the port keeps its own pending receipt");
        }
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
