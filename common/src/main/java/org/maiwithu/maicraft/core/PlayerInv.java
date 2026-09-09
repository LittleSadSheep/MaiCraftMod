package org.maiwithu.maicraft.core;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 提供背包计数和查找方法。要区分“身上总共有多少”和“前 36 格能拿来操作的有多少”。
 * 例如头盔从背包穿到头上，总数不变，但背包里的数量减少；不同目的应选对应的计数方法。
 */
public final class PlayerInv {

    private PlayerInv() {}

    /**
     * 建造当前能从中拿材料的 36 格：快捷栏加主背包，不含盔甲与副手。
     * 预估材料与真正选取必须按同一范围数；否则副手有一叠木板时，前面说够用，后面却拿不到。
     */
    public static final int BUILDABLE_SLOTS = 36;

    /** 数玩家身上全部同种物品，包括盔甲与副手；不表示这些物品都能被当前动作选择器使用。 */
    public static int count(Inventory inv, Item item) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(item)) n += s.getCount();
        }
        return n;
    }

    /**
     * 背着的数量:只数 36 个主格(含快捷栏),穿在身上和副手握着的不算。
     * "它离开了背包"这类判断要用这一个——{@link #count} 含盔甲槽,头盔从手里挪到头上数量不变。
     */
    public static int carriedCount(Inventory inv, Item item) {
        int n = 0;
        for (int i = 0; i < Math.min(BUILDABLE_SLOTS, inv.items.size()); i++) {
            ItemStack s = inv.items.get(i);
            if (!s.isEmpty() && s.is(item)) n += s.getCount();
        }
        return n;
    }

    /** 建造口径的存量:只数 {@link #BUILDABLE_SLOTS} 格。报价与实扣共用这一个。 */
    public static int buildableCount(Inventory inv, Item item) {
        int limit = Math.min(BUILDABLE_SLOTS, inv.items.size());
        int n = 0;
        for (int i = 0; i < limit; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(item)) n += s.getCount();
        }
        return n;
    }

    /** 返回第一个装着该物品的槽位，包括盔甲与副手；没找到返回 -1。调用方仍须核对能否操作该槽位。 */
    public static int findSlot(Inventory inv, Item item) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(item)) return i;
        }
        return -1;
    }


    /**
     * 直接调用 Inventory.add，并返回原 stack 中没装下的部分；原对象数量会被修改。
     * 这是本地背包数据操作，不是向服务器请求领取物品，也不能代替菜单或拾取确认。
     */
    public static ItemStack add(Inventory inv, ItemStack stack) {
        inv.add(stack);
        return stack;   // Inventory.add consumed what fit; remainder stays here
    }
}
