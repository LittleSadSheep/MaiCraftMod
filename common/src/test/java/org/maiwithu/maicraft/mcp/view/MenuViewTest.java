// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.behavior.acquire.TradeOffers;
import org.maiwithu.maicraft.behavior.menu.OpenScreen;
import org.maiwithu.maicraft.game.menu.ScreenOpening;

/** observe(menu) 的样子：开着的界面列种类、两侧、光标与谁开的；读不出来写明为什么；交易列报价。 */
class MenuViewTest {

    private static final OpenScreen CHEST = new OpenScreen(OpenScreen.Kind.STORAGE, "minecraft:generic_9x3", "仓库",
            null, new OpenScreen.Contents(List.of(new OpenScreen.ItemCount("minecraft:cobblestone", 130),
                    new OpenScreen.ItemCount("minecraft:torch", 12)), 27, 23),
            null, new OpenScreen.ItemCount("minecraft:oak_log", 3), ScreenOpening.OpenedBy.TASK);

    @Test
    void 没开界面就说没开() {
        JsonObject view = MenuView.of(Optional.empty(), List.of());

        assertFalse(view.get("open").getAsBoolean());
        assertEquals(1, view.size(), "没开界面只回一句没开，不编别的字段");
    }

    @Test
    void 箱子界面列出里面的东西光标和谁开的() {
        JsonObject view = MenuView.of(Optional.of(CHEST), List.of());

        assertTrue(view.get("open").getAsBoolean());
        assertEquals("storage", view.get("kind").getAsString());
        assertEquals("minecraft:generic_9x3", view.get("menu_type").getAsString());
        assertEquals("仓库", view.get("title").getAsString());
        assertTrue(view.get("recognized").getAsBoolean());
        // 按物品合并、不列第几格；空格只计数。
        JsonObject first = view.getAsJsonArray("contents").get(0).getAsJsonObject();
        assertEquals("minecraft:cobblestone", first.get("item").getAsString());
        assertEquals(130, first.get("count").getAsInt());
        assertEquals(27, view.get("slots").getAsInt());
        assertEquals(23, view.get("empty_slots").getAsInt());
        assertEquals("minecraft:oak_log", view.getAsJsonObject("cursor").get("item").getAsString());
        assertEquals("task", view.get("opened_by").getAsString());
        assertFalse(view.has("contents_unread"));
    }

    @Test
    void 认不出的模组界面写明原因不列格子() {
        OpenScreen filter = new OpenScreen(OpenScreen.Kind.MOD, "create:filter", "过滤器",
                "认不出界面类型 create:filter 的布局，证明不了两侧怎么分，不点击", null,
                "认不出这种界面两侧怎么分，不列里面的东西", null, ScreenOpening.OpenedBy.PLAYER);
        JsonObject view = MenuView.of(Optional.of(filter), List.of());

        assertEquals("mod", view.get("kind").getAsString());
        assertFalse(view.get("recognized").getAsBoolean());
        assertTrue(view.get("unrecognized_reason").getAsString().contains("create:filter"));
        assertFalse(view.has("contents"), "认不出两侧就不照着箱子的样子猜");
        assertTrue(view.has("contents_unread"));
        assertFalse(view.has("cursor"), "光标空着不写");
        assertEquals("player", view.get("opened_by").getAsString());
    }

    @Test
    void 刚打开还没同步的箱子不说成空的() {
        OpenScreen fresh = new OpenScreen(OpenScreen.Kind.STORAGE, "minecraft:generic_9x3", "箱子", null, null,
                "界面刚打开，里面的东西还没从服务器同步过来，过一会儿再看", null, ScreenOpening.OpenedBy.TASK);
        JsonObject view = MenuView.of(Optional.of(fresh), List.of());

        assertTrue(view.get("recognized").getAsBoolean());
        assertFalse(view.has("contents"));
        assertTrue(view.get("contents_unread").getAsString().contains("还没"));
    }

    @Test
    void 交易界面列报价卖光的写明() {
        OpenScreen trade = new OpenScreen(OpenScreen.Kind.TRADE, "minecraft:merchant", "工具匠",
                "认不出界面类型 minecraft:merchant 的布局", null, "交易界面不按格子列，卖什么看报价", null,
                ScreenOpening.OpenedBy.TASK);
        List<TradeOffers.View> offers = List.of(
                new TradeOffers.View(0, new TradeOffers.Payment("minecraft:emerald", 1, false), Optional.empty(),
                        "minecraft:stone_axe", 1, false),
                new TradeOffers.View(1, new TradeOffers.Payment("minecraft:emerald", 3, false),
                        Optional.of(new TradeOffers.Payment("minecraft:book", 1, false)),
                        "minecraft:enchanted_book", 1, true));
        JsonObject view = MenuView.of(Optional.of(trade), offers);

        assertEquals("trade", view.get("kind").getAsString());
        JsonObject second = view.getAsJsonArray("offers").get(1).getAsJsonObject();
        assertEquals("minecraft:enchanted_book", second.getAsJsonObject("sells").get("item").getAsString());
        assertEquals(2, second.getAsJsonArray("costs").size());
        assertTrue(second.get("out_of_stock").getAsBoolean());
        assertFalse(view.getAsJsonArray("offers").get(0).getAsJsonObject().has("out_of_stock"));
    }

    @Test
    void 背包与不是容器的界面不带认不认得出() {
        OpenScreen inventory = new OpenScreen(OpenScreen.Kind.INVENTORY, null, "物品栏", null, null,
                "这是角色自己的背包界面，背包里有什么看 observe(self)", null, ScreenOpening.OpenedBy.UNKNOWN);
        JsonObject view = MenuView.of(Optional.of(inventory), List.of());

        assertEquals("inventory", view.get("kind").getAsString());
        assertFalse(view.has("recognized"));
        assertFalse(view.has("menu_type"));
        assertEquals("unknown", view.get("opened_by").getAsString());
    }

    @Test
    void 一句话说开着什么界面谁开的() {
        assertEquals("开着储物界面（minecraft:generic_9x3，任务点开的）", MenuView.phrase(CHEST));
        OpenScreen pause = new OpenScreen(OpenScreen.Kind.OTHER_SCREEN, null, "游戏菜单", null, null,
                "这不是容器界面，没有格子", null, ScreenOpening.OpenedBy.PLAYER);
        assertEquals("开着「游戏菜单」界面（人开的）", MenuView.phrase(pause));
    }
}
