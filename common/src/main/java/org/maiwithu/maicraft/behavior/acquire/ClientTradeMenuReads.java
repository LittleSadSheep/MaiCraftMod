// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;

import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.ClientBackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 交易界面读端的生产实现：把当前打开的交易界面读成报价与三格现状。
 *
 * <p>报价是服务端随界面同步过来的，点开后的头几刻还没到，读不到就给空，调用方等它；
 * 三格与报价的物品快照用背包视图同一套读法，稀有消耗品的判断不另写第二份。
 */
public final class ClientTradeMenuReads implements ReadsTradeMenu {

    private final Supplier<PlayerContext> contexts;

    public ClientTradeMenuReads(Supplier<PlayerContext> contexts) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    @Override public Optional<List<TradeOffers.View>> offers() {
        var menu = tradeMenu();
        if (menu == null) {
            return Optional.empty();
        }
        List<TradeOffers.View> views = new ArrayList<>();
        for (int index = 0; index < menu.getOffers().size(); index++) {
            views.add(view(index, menu.getOffers().get(index)));
        }
        return Optional.of(List.copyOf(views));
    }

    @Override public Optional<TradeOffers.SlotContent> slot(int index) {
        var menu = tradeMenu();
        if (menu == null || index < 0 || index > 2) {
            return Optional.empty();
        }
        var stack = menu.getSlot(index).getItem();
        return Optional.of(stack.isEmpty() ? TradeOffers.SlotContent.EMPTY
                : new TradeOffers.SlotContent(itemId(stack), stack.getCount()));
    }

    private MerchantMenu tradeMenu() {
        PlayerContext context = contexts.get();
        var menu = context == null || context.localPlayer() == null ? null : context.localPlayer().containerMenu;
        return menu instanceof MerchantMenu merchant ? merchant : null;
    }

    private static TradeOffers.View view(int index, MerchantOffer offer) {
        var costA = ClientBackpackView.snapshot(offer.getCostA());
        Optional<TradeOffers.Payment> costB = offer.getCostB().isEmpty() ? Optional.empty()
                : Optional.of(payment(ClientBackpackView.snapshot(offer.getCostB())));
        var result = offer.getResult();
        return new TradeOffers.View(index, payment(costA), costB,
                result.isEmpty() ? "" : itemId(result), result.getCount(), offer.isOutOfStock());
    }

    private static TradeOffers.Payment payment(BackpackStack stack) {
        return new TradeOffers.Payment(stack.itemId(), stack.count(), stack.precious());
    }

    private static String itemId(ItemStack stack) {
        return ClientBackpackView.snapshot(stack).itemId();
    }
}
