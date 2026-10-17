// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.backpack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;

/**
 * 精妙背包的联动入口。角色从随身背包里取东西都经这里碰模组：认出身上哪几格放的是背包、
 * 点开的界面是不是背包的、读主存储、发打开请求，每一下都经联动入口包一层，
 * 装的模组和编译时用的对不上时整个联动停用，不让 LinkageError 漏出去。
 *
 * <p>交给登记表一样东西：随身背包来源的建法——进世界时带着联动能用的玩家行为再建，
 * 每次进世界建一份新的"上次开包读到的存货"，换世界、重开游戏都从没看过开始。
 */
public final class BackpackCompat extends CompatModule {

    public static final String MOD_ID = "sophisticatedbackpacks";

    private final BackpackItems items;
    private final BackpackMenus menus;
    private final Supplier<Instant> clock;

    public BackpackCompat(BackpackItems items, BackpackMenus menus, Supplier<Instant> clock) {
        super(MOD_ID, "精妙背包");
        this.items = Objects.requireNonNull(items, "items");
        this.menus = Objects.requireNonNull(menus, "menus");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public void contribute(CompatRegistry registry) {
        // 随身背包来源：身上的仓库，开包取出算拿到，并入 carried 这条途径。
        registry.itemSource(this, services ->
                new SophisticatedBackpackSource(this, services, menus, new SeenBackpackStock(), clock));
    }

    /** 角色身上放着精妙背包的那些格子（主背包、副手与模组认的随身栏位），按格子顺序；
     * 认物品经读写端，模组接口对不上时整个联动停用。 */
    public List<BackpackStack> carriedBackpacks(BackpackView backpack) {
        List<BackpackStack> found = new ArrayList<>();
        for (BackpackStack stack : backpack.stacks()) {
            if (call("认背包物品", () -> items.isBackpack(stack.itemId()))) {
                found.add(stack);
            }
        }
        return List.copyOf(found);
    }

    /** 角色背包里装着随身背包的那几格（原版格号：主背包 0..35、盔甲 36..39、副手 40）；饰品栏里的不在这里。 */
    public List<Integer> carriedSlots(Inventory inventory) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (call("认背包物品", () -> items.isBackpack(itemId))) {
                slots.add(slot);
            }
        }
        return List.copyOf(slots);
    }

    /** 点开的界面是不是精妙背包的；见 {@link BackpackMenus#isBackpackMenu}。 */
    public boolean isBackpackMenu(AbstractContainerMenu menu) {
        return call("认背包界面", () -> menus.isBackpackMenu(menu));
    }
}
