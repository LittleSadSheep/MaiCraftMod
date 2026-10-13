// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.maiwithu.maicraft.behavior.interaction.UseKeyProjection;
import org.maiwithu.maicraft.behavior.inventory.ClientGearChanges;
import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 举盾：近身打的时候，对手离得近、自己又还没到出手的时候，把副手的盾举起来挡；充能好了、够得着要挥了就先放下。
 * 举着要每刻续上使用键的投影，断了原版就当松手；不再被叫到（打完了、换去射箭、撤离）投影两刻内过期，盾自己放下。
 * 被斧头砸掉盾进了冷却就不举。
 *
 * <p>副手空着、身上带着盾、对手还在四格外，就先把盾装到副手——剑早就自动换到手上，盾没道理不能。
 * 只装空着的副手，副手里有别的东西不替主人换掉；贴脸了不为装盾开背包。
 */
final class ShieldGuard {

    /** 对手离这么近才举盾。 */
    static final double GUARD_RANGE = 4.5;
    /** 对手还在这么远以外，才为装盾打开背包。 */
    private static final double EQUIP_DISTANCE = 4.0;
    private static final String SHIELD = "minecraft:shield";
    private static final int CONFIRM_TICKS = 20;

    private final UseKeyProjection projection;
    private PlayerContext current;
    private final ClientGearChanges gear;
    private Action equipping;
    /** 举盾的那一下；没举为 null。 */
    private PendingInteraction raising;
    /** 放盾的那一下：等它结清，免得占着交互入口挡住下一刀。 */
    private PendingInteraction lowering;
    private ItemStack shieldAtRaise = ItemStack.EMPTY;

    ShieldGuard(UseKeyProjection projection) {
        this.projection = projection;
        this.gear = new ClientGearChanges(() -> current, new ClientMovesToMainhand(() -> current));
    }

    /**
     * 这一刻举不举盾。
     *
     * @param distance      对手离多远（格）
     * @param aboutToStrike 充能好了、够得着，这一刻要挥：盾得放下
     */
    void tick(TickContext context, double distance, boolean aboutToStrike) {
        PlayerContext player = context.player();
        current = player;
        LocalPlayer self = player == null ? null : player.localPlayer();
        if (self == null || player.interactionSender() == null) return;
        if (lowering != null && !lowering.terminal()) lowering = player.interactionSender().poll(player, lowering);
        if (equipping != null) {
            if (equipping.tick(context) instanceof ActionStatus.Running) return;
            equipping.close();
            equipping = null;
        }
        if (!self.getOffhandItem().is(Items.SHIELD)) {
            // 副手空着、身上有盾、对手还远：先装上；没盾就正常打。
            if (self.getOffhandItem().isEmpty() && distance > EQUIP_DISTANCE && !self.isUsingItem() && carriesShield(self)) {
                equipping = gear.wear(SHIELD, GearSlotName.OFFHAND).orElse(null);
            }
            return;
        }
        boolean want = distance <= GUARD_RANGE && !aboutToStrike && !self.getCooldowns().isOnCooldown(Items.SHIELD);
        if (want) raise(player, self);
        else lower(player, self);
    }

    // 举起来：没举就右键副手，确认在用了；举着就每刻续上投影。原版把它放下了（被砸进冷却、换了副手）就重新来。
    private void raise(PlayerContext player, LocalPlayer self) {
        boolean up = self.isUsingItem() && self.getUsedItemHand() == InteractionHand.OFF_HAND;
        if (raising != null && raising.terminal() && !up) raising = null;
        if (raising == null) {
            if (self.isUsingItem() || !player.canInteractThisTick() || lowering != null && !lowering.terminal()) return;
            shieldAtRaise = self.getOffhandItem().copy();
            try {
                raising = player.interactionSender().useItem(player, InteractionHand.OFF_HAND,
                        c -> c.localPlayer().isUsingItem() ? InteractionConfirmation.Verdict.APPLIED
                                : InteractionConfirmation.Verdict.PENDING, CONFIRM_TICKS);
            } catch (IllegalStateException busy) {
                // 上一下举盾（或别的动作）还在等确认：这一刻不举，下一刻再试。举盾只是锦上添花，
                // 不能因为它把整场自卫掀翻——实机 10-13 打完一仗刚收起的举盾还没结清，躲苦力怕时再举就抛错，
                // 自卫任务出错结束、缓着不再插，站在点着引信的苦力怕旁边被炸死。
                return;
            }
        } else if (!raising.terminal()) {
            raising = player.interactionSender().poll(player, raising);
        }
        if (raising.terminal() && raising.status() != PendingInteraction.Status.CONFIRMED_APPLIED) {
            raising = null;
            return;
        }
        projection.renew(this, player, raising, InteractionHand.OFF_HAND, shieldAtRaise);
    }

    // 放下：交还投影，举着就原生松手（盾松开不会放出什么）。
    private void lower(PlayerContext player, LocalPlayer self) {
        if (raising == null) return;
        projection.release(this);
        boolean up = self.isUsingItem() && self.getUsedItemHand() == InteractionHand.OFF_HAND;
        if (up && raising.terminal() && player.canInteractThisTick()) {
            lowering = player.interactionSender().releaseUsingItem(player, raising);
        }
        if (!up || lowering != null) raising = null;
    }

    private static boolean carriesShield(LocalPlayer self) {
        for (ItemStack stack : self.getInventory().items) {
            if (stack.is(Items.SHIELD)) return true;
        }
        return false;
    }

    /** 不打了：盾放下，装到一半的收尾。 */
    void close() {
        projection.release(this);
        raising = null;
        if (equipping != null) equipping.close();
        equipping = null;
    }
}
