// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.Objects;
import java.util.function.Function;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 原生丢出的动作：手里握着要丢的东西，用原生的 Q 投掷逐份丢出去，每一份都等游戏确认扣减。
 *
 * <p>一整格都该丢时整份一抛，剩下的零头一件一件丢——原生的部分投掷一次只出手一件。
 * 抛掷没有等到确认就收尾（超时或被顶掉）时如实报告"没能确认"，把计划丢的件数交给调用方
 * 记进没确认的交互里；丢弃不可逆，没等到确认不等于确定没丢，也不盲目再丢一次。
 * 主手上不是要丢的东西时按缺料失败：换到主手是调用方的前置阶段。
 */
public final class ThrowsItems implements Action {

    /** 等一次投掷被游戏确认的期限；投掷本身即时结算，超时多半是断线或高延迟。 */
    private static final int CONFIRM_TIMEOUT_TICKS = 20;

    private final String itemId;
    private final Function<PlayerContext, FirstPersonScene> scenes;

    private int remaining;
    private PendingInteraction pending;
    /** 这次提交时计划丢出的件数：整份抛是一格的数量，零头抛是 1。 */
    private int plannedThisThrow;
    private boolean hadConfirmationOwner;
    private Problem failure;

    /**
     * @param itemId 要丢的物品注册 ID
     * @param count  要丢几件
     * @param scenes 第一人称现场的取法，用来核对手上握着的是不是这件东西
     */
    public ThrowsItems(String itemId, int count, Function<PlayerContext, FirstPersonScene> scenes) {
        this.itemId = Objects.requireNonNull(itemId, "itemId");
        if (count < 1) throw new IllegalArgumentException("要丢的数量至少为 1：" + count);
        this.remaining = count;
        this.scenes = Objects.requireNonNull(scenes, "scenes");
    }

    /** 还剩几件没丢出去。 */
    public int remaining() {
        return remaining;
    }

    /** 收尾时的问题；动作以失败结束时非空。 */
    public Problem failure() {
        return failure;
    }

    /** 收尾时有没有还在等确认的投掷；有的话按没能确认的交互记账，不能当没发生。 */
    public boolean hasThrowInFlight() {
        return hadConfirmationOwner;
    }

    @Override
    public ActionStatus tick(TickContext context) {
        PlayerContext player = context.player();
        if (!player.isCurrent() || player.interactionSender() == null) {
            return ActionStatus.running();
        }
        if (pending != null) {
            return awaitConfirmation(player);
        }
        if (remaining <= 0) {
            return ActionStatus.done();
        }
        return throwNext(player);
    }

    // 每次投掷前先核对主手：不是这件东西就按缺料失败，换到主手是调用方的前置阶段。
    private ActionStatus throwNext(PlayerContext player) {
        ItemStack held = scenes.apply(player).heldItem(InteractionHand.MAIN_HAND);
        if (held == null || held.isEmpty() || !itemIdOf(held).equals(itemId)) {
            return ActionStatus.failed(new Problem(Problem.Kind.NEED_ITEM,
                    "主手上没有" + itemId + "，丢东西要先把要丢的换到主手", null));
        }
        // 丢一下也是一次交互：本刻机会已经用掉（或角色不归自动化控制）就等下一刻。
        if (!player.canInteractThisTick()) {
            return ActionStatus.running();
        }
        plannedThisThrow = held.getCount() <= remaining ? held.getCount() : 1;
        ItemStack before = held.copy();
        pending = player.interactionSender().dropSelected(player, before, plannedThisThrow > 1,
                InteractionConfirmation.heldItemChanged(InteractionHand.MAIN_HAND, before),
                CONFIRM_TIMEOUT_TICKS);
        hadConfirmationOwner = true;
        return ActionStatus.progressed();
    }

    private ActionStatus awaitConfirmation(PlayerContext player) {
        if (!pending.terminal()) {
            pending = player.interactionSender().poll(player, pending);
            if (pending != null && !pending.terminal()) return ActionStatus.running();
        }
        if (pending == null) {
            return ActionStatus.failed(stuck("投掷的确认记录没了，说不清丢没丢出去"));
        }
        PendingInteraction settled = pending;
        pending = null;
        return switch (settled.status()) {
            case CONFIRMED_APPLIED -> {
                remaining -= plannedThisThrow;
                yield remaining <= 0 ? ActionStatus.done() : ActionStatus.progressed();
            }
            case CONFIRMED_NOT_APPLIED -> ActionStatus.failed(new Problem(Problem.Kind.REFUSED_BY_GAME,
                    "游戏没有接受这次投掷：" + settled.detail(), null));
            default -> ActionStatus.failed(stuck("投掷提交了但没等到确认，不能确定丢没丢出去，不盲目再丢"));
        };
    }

    private Problem stuck(String message) {
        failure = new Problem(Problem.Kind.STUCK, message + "；还剩 " + remaining + " 件没确认丢出", null);
        return failure;
    }

    // 手上东西的注册 ID；投掷会改数量，只比物品种类。
    private static String itemIdOf(ItemStack held) {
        return BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
    }

    @Override
    public void pause() {
        // 投掷是即时交互；被生存需求打断时让等待中的确认自然超时，已出手的照实记账。
    }

    @Override
    public void close() {
        // 任务收尾时还有没等完的投掷：如实留在"没能确认"里，由调用方记账。
    }

    @Override
    public Interruptibility interruptibility() {
        return pending != null && !pending.terminal()
                ? Interruptibility.UNSAFE_TO_STOP
                : Interruptibility.WORKING;
    }

    @Override
    public String describe() {
        if (pending != null && !pending.terminal()) {
            return "等游戏确认这次投掷（" + itemId + "，还剩 " + remaining + " 件）";
        }
        return "正在丢" + itemId + "（还剩 " + remaining + " 件）";
    }
}
