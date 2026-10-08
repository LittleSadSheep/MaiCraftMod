// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;

/**
 * 只延续任务已经开始的那一次原生持用：占用"按住使用键"的投影。不改真实按键、不重复右键，
 * 也不替游戏完成吃饭。原版每个客户端刻读一次使用键决定进食、拉弓是否继续；
 * 从任务提交持续使用到它确认收尾之间可能隔好几刻，控制权也已交还，
 * 由这里的投影让原版在那几刻仍看到"按住"，直到归属核对不通过、超时或任务松手为止。
 *
 * <p>归属逐项核对：只有同一任务、同一次提交、同一玩家、同一手持物品与主手槽位，
 * 且自动化仍握有控制权、仍在时间窗内，才允许续上；任何一项变了就把投影还回真实键值，
 * 不能用旧吃饭任务的投影去触发下一口。动作归属交给交互提交入口核对
 * （{@link InteractionSender#ownsItemUse}），本类不重复判断动作处于哪个阶段。
 *
 * <p>实例在启动时创建，Mixin 的按键投影经 {@link org.maiwithu.maicraft.game.ClientHooks} 取用；
 * 提交方（持续使用物品的确认与松手）接入后调用 {@link #renew} 与 {@link #release}。
 */
public final class UseKeyHold {
    /** 当前被占用的一次持用投影；没有任务占用时为 null。 */
    private Hold active;

    private record Hold(
            Object owner, Minecraft minecraft, LocalPlayer player, ClientLevel level,
            PlayerInput input, InteractionSender sender, PendingInteraction pending,
            InteractionHand hand, ItemStack item, int selected, int remaining,
            long throughTick, long throughNanos) {}

    /**
     * 延续一次持续使用：在提交后的两个游戏刻（或半秒）内，只要仍是同一任务、同一次提交、
     * 同一玩家与手持，就把按住投影续到新的期限。旧投影仍被别的任务持有时不接管。
     */
    public boolean renew(Object owner, PlayerContext context, PendingInteraction pending,
                         InteractionHand hand, ItemStack before) {
        // 上下文还有效也不够：本刻必须仍允许动手、自动化仍握有控制权、没有打开界面，
        // 且这次提交仍归交互提交入口认管；任一不满足就不投影，让真实键值说话。
        if (!(context instanceof DefaultPlayerContext own) || !own.permitsNativeActions()
                || !context.input().automationOwnsControls()
                || Minecraft.getInstance().screen != null
                || context.interactionSender() == null
                || !context.interactionSender().ownsItemUse(pending)) {
            return false;
        }
        if (active != null && active.owner != owner && valid(active)) return false;
        LocalPlayer player = context.localPlayer();
        // 主手续用必须留在同一槽位；换过热栏就等于换了物品，投影不再替它按住。
        int selected = active != null && active.owner == owner && active.pending == pending
                ? active.selected : player.getInventory().selected;
        // 剩余持用刻只减不增：先观察到已经扣减的就取较小值，防止倒计时被回退当成同一次使用。
        int remaining = player.getUseItemRemainingTicks();
        if (active != null && active.owner == owner && active.pending == pending) {
            remaining = Math.min(remaining, active.remaining);
        }
        active = new Hold(owner, Minecraft.getInstance(), player, context.level(), context.input(),
                context.interactionSender(), pending, hand, before.copy(), selected, remaining,
                context.level().getGameTime() + 2, System.nanoTime() + 250_000_000L);
        return true;
    }

    /** 原版只在正在使用同一物品时获得按住投影；吃完后返回真实键值，不触发下一块面包。 */
    public boolean project(Minecraft minecraft, boolean actual) {
        Hold hold = active;
        if (hold == null || hold.minecraft != minecraft) return actual;
        if (!valid(hold)) {
            active = null;
            return actual;
        }
        return actual || sameUse(hold);
    }

    /** 停止动作也必须仍拥有同一次提交和手中物品，不能用旧吃饭任务松掉后来开始的持用。 */
    public boolean owns(Object owner, PlayerContext context, PendingInteraction pending) {
        Hold hold = active;
        return hold != null && hold.owner == owner && hold.pending == pending
                && hold.player == context.localPlayer() && context.isCurrent()
                && valid(hold) && sameUse(hold);
    }

    /** 任务结束或改判时交还投影；只交还自己占用的那次，不动别的任务的持用。 */
    public void release(Object owner) {
        if (active != null && active.owner == owner) active = null;
    }

    /** 交互提交入口整体停用（例如断开连接）时，跟着它登记的持用投影一并作废。 */
    public void revoke(InteractionSender sender) {
        if (active != null && active.sender == sender) active = null;
    }

    private boolean valid(Hold hold) {
        // 玩家、世界、界面、控制权、动作归属与期限逐项核对；期限按游戏刻与真实时间先到者为准。
        return hold.minecraft.player == hold.player && hold.minecraft.level == hold.level
                && hold.minecraft.screen == null && hold.input.automationOwnsControls()
                && hold.sender.ownsItemUse(hold.pending)
                && hold.player.level().getGameTime() <= hold.throughTick
                && System.nanoTime() <= hold.throughNanos;
    }

    private static boolean sameUse(Hold hold) {
        return hold.player.isUsingItem() && hold.player.getUsedItemHand() == hold.hand
                && hold.player.getUseItemRemainingTicks() <= hold.remaining
                && (hold.hand != InteractionHand.MAIN_HAND || hold.player.getInventory().selected == hold.selected)
                && HeldUseItems.same(hold.item, hold.player.getItemInHand(hold.hand), hold.player::registryAccess)
                && HeldUseItems.same(hold.item, hold.player.getUseItem(), hold.player::registryAccess);
    }
}
