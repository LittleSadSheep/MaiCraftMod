// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.maiwithu.maicraft.behavior.acquire.ReadsTradeMenu;
import org.maiwithu.maicraft.behavior.acquire.TradeOffers;
import org.maiwithu.maicraft.behavior.menu.OpenScreen;
import org.maiwithu.maicraft.behavior.menu.ReadsOpenScreen;
import org.maiwithu.maicraft.game.menu.ScreenOpening;

/**
 * observe(menu) 的样子：角色此刻开着什么界面、哪一种、认不认得出、容器那一侧有什么、光标上拿着什么、谁开的；
 * 交易界面另列报价。没开界面就说没开。
 *
 * <p>只给看到的事实：东西按物品合并，不列第几格；认不出两侧或还没同步过来时不列，并写明为什么没列，
 * 不把没读到说成空的。角色那一侧就是背包，看 observe(self)。
 */
public final class MenuView {
    private MenuView() {}

    /**
     * 看界面要读的两样：开着的界面，和交易界面的报价。进世界时按这个世界认得出的界面建一份。
     *
     * @param screens 此刻开着的界面
     * @param trades  交易界面的报价
     */
    public record Reads(ReadsOpenScreen screens, ReadsTradeMenu trades) {}

    /** 读一次此刻的界面并整理成 observe(menu) 的样子；交易界面再读报价。 */
    public static JsonObject read(Reads reads) {
        Optional<OpenScreen> screen = reads.screens().current();
        List<TradeOffers.View> offers = screen.filter(open -> open.kind() == OpenScreen.Kind.TRADE)
                .flatMap(open -> reads.trades().offers()).orElse(List.of());
        return of(screen, offers);
    }

    /**
     * @param screen 此刻开着的界面；什么都没开时为空
     * @param offers 交易界面的报价；不是交易界面或没读到时给空列表
     */
    public static JsonObject of(Optional<OpenScreen> screen, List<TradeOffers.View> offers) {
        JsonObject json = new JsonObject();
        if (screen.isEmpty()) {
            json.addProperty("open", false);
            return json;
        }
        OpenScreen open = screen.get();
        json.addProperty("open", true);
        json.addProperty("kind", lower(open.kind()));
        if (open.menuType() != null) json.addProperty("menu_type", open.menuType());
        if (open.title() != null) json.addProperty("title", open.title());
        // 认不认得出只对容器界面有意义：认得出才替你读两侧、搬东西；认不出的写明原因。
        if (open.menuType() != null || open.unrecognized() != null) {
            json.addProperty("recognized", open.unrecognized() == null);
            if (open.unrecognized() != null) json.addProperty("unrecognized_reason", open.unrecognized());
        }
        if (open.contents() != null) {
            json.add("contents", items(open.contents().items()));
            json.addProperty("slots", open.contents().slots());
            json.addProperty("empty_slots", open.contents().emptySlots());
        } else {
            json.addProperty("contents_unread", open.contentsUnread());
        }
        if (open.kind() == OpenScreen.Kind.TRADE && !offers.isEmpty()) {
            json.add("offers", offers(offers));
        }
        if (open.cursor() != null) json.add("cursor", item(open.cursor()));
        json.addProperty("opened_by", lower(open.openedBy()));
        return json;
    }

    /**
     * 一句话说开着什么界面、谁开的，给结束事件的自身摘要用，例如"开着储物界面（minecraft:generic_9x3，任务点开的）"。
     */
    public static String phrase(OpenScreen open) {
        StringBuilder text = new StringBuilder("开着");
        String word = kindWord(open);
        text.append(word).append("界面（");
        if (open.menuType() != null) text.append(open.menuType()).append("，");
        text.append(openedByWords(open.openedBy())).append("）");
        return text.toString();
    }

    // 种类说成玩家的话；不是容器界面的按画面上的标题说，没有标题说"一个"。
    private static String kindWord(OpenScreen open) {
        return switch (open.kind()) {
            case STORAGE -> "储物";
            case CRAFTING_TABLE -> "工作台";
            case FURNACE -> "熔炉";
            case STONECUTTER -> "切石机";
            case ENCHANTING_TABLE -> "附魔台";
            case TRADE -> "交易";
            case OTHER_VANILLA -> "原版";
            case MOD -> "模组";
            case INVENTORY -> "背包";
            case OTHER_SCREEN -> open.title() == null || open.title().isBlank() ? "一个" : "「" + open.title() + "」";
        };
    }

    private static String openedByWords(ScreenOpening.OpenedBy openedBy) {
        return switch (openedBy) {
            case TASK -> "任务点开的";
            case PLAYER -> "人开的";
            case UNKNOWN -> "不知道谁开的";
        };
    }

    private static JsonArray items(List<OpenScreen.ItemCount> items) {
        JsonArray list = new JsonArray();
        items.forEach(entry -> list.add(item(entry)));
        return list;
    }

    private static JsonObject item(OpenScreen.ItemCount entry) {
        JsonObject json = new JsonObject();
        json.addProperty("item", entry.item());
        json.addProperty("count", entry.count());
        return json;
    }

    // 交易报价：卖什么几件、要付什么几件（一笔或两笔），卖光了的写明；按村民摆的先后。
    private static JsonArray offers(List<TradeOffers.View> offers) {
        JsonArray list = new JsonArray();
        for (TradeOffers.View offer : offers) {
            JsonObject json = new JsonObject();
            json.add("sells", item(new OpenScreen.ItemCount(offer.resultItemId(), offer.resultCount())));
            JsonArray costs = new JsonArray();
            costs.add(item(new OpenScreen.ItemCount(offer.costA().itemId(), offer.costA().count())));
            offer.costB().ifPresent(cost -> costs.add(item(new OpenScreen.ItemCount(cost.itemId(), cost.count()))));
            json.add("costs", costs);
            if (offer.outOfStock()) json.addProperty("out_of_stock", true);
            list.add(json);
        }
        return list;
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
