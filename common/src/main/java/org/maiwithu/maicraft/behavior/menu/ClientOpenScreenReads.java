// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.game.menu.ScreenOpening;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 看角色此刻开着的界面的读端：界面种类按菜单类型认，两侧怎么分交给认得出的界面（原版加联动模组的证明），
 * 容器那一侧读界面内容读端；眼前是哪个界面、谁开的、同步到没有，看游戏接口层的界面打开记录。只读，不点、不关。
 *
 * <p>认不出两侧的界面不列格子，不照着普通箱子的样子猜；刚打开、服务端的格子还没到时也不列，
 * 写明是还没同步，免得把没同步的箱子说成空的。
 */
public final class ClientOpenScreenReads implements ReadsOpenScreen {

    private final Supplier<PlayerContext> contexts;
    /** 认得出哪些界面：原版加上联动模组证明过的；和点开、搬东西用的是同一份。 */
    private final MenuLayouts layouts;

    public ClientOpenScreenReads(Supplier<PlayerContext> contexts, MenuLayouts layouts) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.layouts = Objects.requireNonNull(layouts, "layouts");
    }

    @Override public Optional<OpenScreen> current() {
        PlayerContext context = contexts.get();
        LocalPlayer player = context == null ? null : context.localPlayer();
        if (player == null || context.menuActions() == null) return Optional.empty();
        // 眼前的界面由游戏接口层的界面打开记录交出（读之前它先看一眼）：画面上只有世界、身上也没有别的菜单时为空。
        Optional<ScreenOpening> seen = context.menuActions().screenOpening(context);
        if (seen.isEmpty()) return Optional.empty();
        ScreenOpening opening = seen.get();
        Screen screen = opening.screen();
        AbstractContainerMenu menu = opening.menu();
        ScreenOpening.OpenedBy openedBy = opening.openedBy();
        String title = screen == null ? null : screen.getTitle().getString();
        OpenScreen.ItemCount cursor = itemCount(menu.getCarried());
        if (menu == player.inventoryMenu) {
            // 角色自己的背包界面（生存或创造的物品栏）或不是容器的界面（聊天框、暂停菜单、告示牌编辑）：没有容器那一侧可列。
            boolean inventory = screen instanceof AbstractContainerScreen<?>;
            return Optional.of(new OpenScreen(inventory ? OpenScreen.Kind.INVENTORY : OpenScreen.Kind.OTHER_SCREEN,
                    null, title, null, null,
                    inventory ? "这是角色自己的背包界面，背包里有什么看 observe(self)" : "这不是容器界面，没有格子",
                    cursor, openedBy));
        }
        ClientMenuSlots slots = new ClientMenuSlots(contexts);
        String type = slots.menuTypeId();
        OpenScreen.Kind kind = MenuLayout.kindOf(type);
        String menuType = type.isEmpty() ? null : type;
        if (layouts.classify(slots) instanceof MenuLayout.Unsupported unsupported) {
            // 认不出两侧：交易界面的报价另读，其余如实说不列。
            String unread = kind == OpenScreen.Kind.TRADE ? "交易界面不按格子列，卖什么看报价"
                    : "认不出这种界面两侧怎么分，不列里面的东西";
            return Optional.of(new OpenScreen(kind, menuType, title, unsupported.reason(), null, unread, cursor, openedBy));
        }
        // 同步从界面出现那一刻起算；说不清哪一刻开的版本号记的是 -1，界面内容读端看到版本"变过"就给读数。
        Optional<MenuContent.Reading> reading = ClientMenuContent.openedAt(contexts, layouts, opening).current();
        if (reading.isEmpty()) {
            return Optional.of(new OpenScreen(kind, menuType, title, null, null,
                    "界面刚打开，里面的东西还没从服务器同步过来，过一会儿再看", cursor, openedBy));
        }
        return Optional.of(new OpenScreen(kind, menuType, title, null, contents(reading.get().containerSnapshots()),
                null, cursor, openedBy));
    }

    // 容器那一侧按物品合并，按界面里的先后排；空格只计数。数量多也全列，不截断。
    private static OpenScreen.Contents contents(List<SlotSnapshot> snapshots) {
        Map<String, Integer> merged = new LinkedHashMap<>();
        int empty = 0;
        for (SlotSnapshot snapshot : snapshots) {
            if (snapshot.isEmpty()) {
                empty++;
                continue;
            }
            merged.merge(itemId(snapshot.stack()), snapshot.count(), Integer::sum);
        }
        List<OpenScreen.ItemCount> items = new ArrayList<>();
        merged.forEach((item, count) -> items.add(new OpenScreen.ItemCount(item, count)));
        return new OpenScreen.Contents(items, snapshots.size(), empty);
    }

    private static OpenScreen.ItemCount itemCount(ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : new OpenScreen.ItemCount(itemId(stack), stack.getCount());
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
