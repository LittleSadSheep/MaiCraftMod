// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import java.util.function.Supplier;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;

import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把接住自己要用的东西拿到手上：主手或副手已经拿着就直接用；在快捷栏就选中那一格；
 * 在主背包就像玩家一样打开背包换到手上那一格、关上背包再选中（共用的换手）。
 *
 * <p>站在出发点备料不急，最多给十秒；已经在掉时只剩几刻够不着落点了，主背包里的来不及挪就放弃，
 * 打开过的背包要关上，不留着界面落地。
 */
final class LandingItemInHand {

    /** 站着备料最多等这么久。 */
    private static final long PREPARE_LIMIT_TICKS = 200;
    /** 空中离出手的时刻只剩这么几刻时，背包里的就不挪了。 */
    private static final int AIRBORNE_RESERVE_TICKS = 3;

    private final Item item;
    private final String itemId;
    private final ClientMovesToMainhand hands;
    private Action move;
    private boolean ready;
    private boolean failed;
    /** 为了挪东西打开过背包：放弃时要把它关上。 */
    private boolean inventoryTouched;
    private PendingMenuAction closing;
    private long startedTick = Long.MIN_VALUE;
    private InteractionHand hand = InteractionHand.MAIN_HAND;
    private String detail = "正在把落地要用的东西拿到手上";

    LandingItemInHand(Item item, Supplier<PlayerContext> contexts) {
        this.item = item;
        this.itemId = BuiltInRegistries.ITEM.getKey(item).toString();
        this.hands = new ClientMovesToMainhand(contexts);
    }

    boolean ready() {
        return ready;
    }

    boolean failed() {
        return failed;
    }

    InteractionHand hand() {
        return hand;
    }

    String detail() {
        return detail;
    }

    /** 已经拿在手上就直接用，不动背包。 */
    boolean acceptHeld(LocalPlayer player) {
        if (move != null || inventoryTouched || failed) return false;
        if (player.getMainHandItem().is(item)) hand = InteractionHand.MAIN_HAND;
        else if (player.getOffhandItem().is(item)) hand = InteractionHand.OFF_HAND;
        else return false;
        ready = true;
        detail = "落地要用的东西已经拿在手上";
        return true;
    }

    /**
     * 每刻推进一点，拿好了返回 true。
     *
     * @param remainingTicks 离必须出手还有几刻；站在地上不急时传 Integer.MAX_VALUE
     */
    boolean tick(PlayerContext context, int remainingTicks) {
        LocalPlayer player = context.localPlayer();
        if (failed) return false;
        if (ready && player.getItemInHand(hand).is(item)) return true;
        ready = false;
        if (startedTick == Long.MIN_VALUE) startedTick = context.clientTick();
        if (move == null && acceptHeld(player)) return true;
        if (context.clientTick() - startedTick > PREPARE_LIMIT_TICKS) {
            // 记一行卡在哪：实机 10-14 水桶在主背包，十秒没换到手上，日志里看不出是没开出背包还是换手没确认。
            LoggerFactory.getLogger(LandingItemInHand.class).info("[落地] {} 十秒没拿到手上：{}，主手 {}，在第 {} 格",
                    itemId, move == null ? "还没开始换" : move.describe(),
                    BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()), carriedSlot(player));
            return fail(context, "把落地要用的东西拿到手上超过十秒还没好");
        }
        if (move == null) {
            int slot = carriedSlot(player);
            if (slot < 0) return fail(context, "身上已经没有 " + itemId);
            // 只剩几刻就要出手：主背包里的来不及开背包挪，放弃这一种。
            if (slot >= 9 && remainingTicks <= AIRBORNE_RESERVE_TICKS) {
                return fail(context, itemId + " 在背包里，来不及挪到手上");
            }
            var action = hands.actionToMainhand(itemId);
            if (action.isEmpty()) return acceptHeld(player);
            move = action.get();
            inventoryTouched = slot >= 9;
        }
        ActionStatus status = move.tick(TickContext.of(context.level().getGameTime(), context));
        if (status instanceof ActionStatus.Running) return false;
        move = null;
        if (status instanceof ActionStatus.Failed failure) return fail(context, failure.problem().message());
        inventoryTouched = false;
        hand = InteractionHand.MAIN_HAND;
        ready = player.getMainHandItem().is(item);
        detail = ready ? "落地要用的东西拿到手上了" : "换到手上后手里不是 " + itemId;
        if (!ready) failed = true;
        return ready;
    }

    /** 放弃备料：打开过的背包要关上，关好之前 cleanupPending 为真。 */
    void abandon(PlayerContext context, String reason) {
        move = null;
        // 下次再备料从头计时：被打断（自卫插进来打了一架）的那段不算进十秒。实机 10-14 换水桶换到一半被僵尸打断，
        // 打完回来接着换，计时连着打架的时间一起算，没换完就判超时。
        startedTick = Long.MIN_VALUE;
        if (!inventoryTouched || closing != null) return;
        // 交出身体时手里的现场可能已经不是这一刻的（换了别的事在推进）：关不了就交给下一件事，
        // 开工前会先关掉留下的界面。原先拿过期现场去关背包抛错，正在打的自卫跟着出错收场，站着被僵尸打死。
        if (context == null || !context.isCurrent()) {
            inventoryTouched = false;
            return;
        }
        try {
            closing = context.menuActions().closeForTaskBoundary(context, 20, reason);
        } catch (IllegalArgumentException | IllegalStateException stale) {
            inventoryTouched = false;
        }
    }

    boolean cleanupPending(PlayerContext context) {
        if (closing == null) return false;
        if (!closing.terminal()) closing = context.menuActions().poll(context, closing);
        if (!closing.terminal()) return true;
        closing = null;
        inventoryTouched = false;
        return false;
    }

    private int carriedSlot(LocalPlayer player) {
        for (int slot = 0; slot < 36; slot++) {
            if (player.getInventory().getItem(slot).is(item)) return slot;
        }
        return -1;
    }

    private boolean fail(PlayerContext context, String reason) {
        failed = true;
        detail = reason;
        abandon(context, reason);
        return false;
    }
}
