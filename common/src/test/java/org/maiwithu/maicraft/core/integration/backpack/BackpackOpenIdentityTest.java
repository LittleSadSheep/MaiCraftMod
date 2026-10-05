// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.backpack;

import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/**
 * 回放 2026-10-05 实测：同时带钻石包（快捷栏8）和金包（主背包35）时，开包核对只认服务端下发的原生地址与内容身份。
 * 精妙内容身份组件在独立回归里未注册，这里用自定义数据承载同名身份；打开标签与渲染清单的漂移也用同一份数据模拟。
 */
public final class BackpackOpenIdentityTest {
    private static final String DIAMOND = "sophisticated_backpack:743c64e0-2905-4c24-8fa9-fbd05da7a1b1";
    private static final String GOLD = "sophisticated_backpack:ce5db4c9-2410-4cdf-8a24-e9ea57bfb3d8";
    private static final BackpackCarriers.Carrier HOTBAR_8 = new BackpackCarriers.Carrier("main", "", 8);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        // 钻石包在快捷栏8被右键：发起时记下地址、种类与身份，同行金包的身份作为“别的包”。
        var origin = new BackpackOpenSession.Origin(HOTBAR_8, Items.CHEST, DIAMOND, Set.of(GOLD));
        var held = backpack(Items.CHEST, DIAMOND, 4, "feeding:enabled");
        // 客户端菜单副本按当前升级重写了渲染清单、丢了打开标签：同一只包，但全部组件已不再相同。
        var menuCopy = backpack(Items.CHEST, DIAMOND, -1, "feeding:enabled,hunger=8;tank:right");
        check(!ItemStack.isSameItemSameComponents(menuCopy, held), "fixture reproduces the field drift that the old full-component check rejected");
        check(mismatch(origin, HOTBAR_8, menuCopy, held) == null, "the initiating diamond backpack is accepted despite volatile UI component drift");

        // 服务端换开了金包所在格：地址不符直接拒绝，不因两只都是背包就放行。
        check(mismatch(origin, new BackpackCarriers.Carrier("main", "", 35), menuCopy, held).contains("instead of main::8"),
                "a menu bound to the other carried slot is rejected");
        check(mismatch(origin, null, menuCopy, held).contains("unavailable"), "an unreadable native address cannot be assumed to match");
        // 同一格开出的却是金包（不同型号）：种类不符即拒绝。
        var gold = backpack(Items.BARREL, GOLD, -1, "stack");
        check(mismatch(origin, HOTBAR_8, gold, gold).contains("item differs"), "a different backpack tier at the same address is rejected");
        // 两只同型号钻石包只能靠内容身份区分：菜单或该格身份变成另一只，都必须拒绝。
        var twin = backpack(Items.CHEST, GOLD, 4, "feeding:enabled");
        var twinOrigin = new BackpackOpenSession.Origin(HOTBAR_8, Items.CHEST, DIAMOND, Set.of(GOLD));
        check(mismatch(twinOrigin, HOTBAR_8, twin, held).contains("differs from the initiating"), "a same-tier twin opened from the menu is rejected");
        check(mismatch(twinOrigin, HOTBAR_8, menuCopy, twin).contains("differs from the initiating"), "a twin swapped into the hand slot is rejected");
        check(mismatch(origin, HOTBAR_8, menuCopy, ItemStack.EMPTY).contains("item differs"), "an emptied hand slot cannot confirm the open");

        // 从未打开过的新包没有身份：服务端首次开包补发的新身份可接受，但不能是同行金包的身份。
        var fresh = new BackpackOpenSession.Origin(HOTBAR_8, Items.CHEST, null, Set.of(GOLD));
        var assigned = backpack(Items.CHEST, "sophisticated_backpack:new", -1, "");
        check(mismatch(fresh, HOTBAR_8, backpack(Items.CHEST, null, -1, ""), assigned) == null, "a fresh backpack may receive its first identity");
        check(mismatch(fresh, HOTBAR_8, backpack(Items.CHEST, GOLD, -1, ""), assigned).contains("another carried backpack"),
                "a fresh open cannot land on the other carried backpack's storage");

        // 原生地址按模组同一编码读回；子背包或方块背包上下文不冒充顶层随身包。
        check(HOTBAR_8.equals(BackpackMenuAccess.itemAddress(new ContextFixture(ContextFixture.ContextType.ITEM_BACKPACK, "main", "", 8))),
                "the server-assigned hand slot is decoded from the native context encoding");
        check(new BackpackCarriers.Carrier("curios", "back", 0).equals(BackpackMenuAccess.itemAddress(
                new ContextFixture(ContextFixture.ContextType.ITEM_BACKPACK, "curios", "back", 0))), "worn handler addresses keep their identifier");
        check(BackpackMenuAccess.itemAddress(new ContextFixture(ContextFixture.ContextType.ITEM_SUB_BACKPACK, "main", "", 8)) == null,
                "nested backpack contexts are not treated as the carried top-level backpack");
        System.out.println("BackpackOpenIdentityTest: passed");
    }

    private static String mismatch(BackpackOpenSession.Origin origin, BackpackCarriers.Carrier opened, ItemStack menu, ItemStack held) {
        return BackpackOpenSession.mismatch(origin, opened, menu, held, BackpackOpenIdentityTest::identity);
    }
    private static ItemStack backpack(Item item, String storage, int openTab, String renderUpgrades) {
        // 自定义数据里放身份与界面状态；身份之外的键代表会被模组原地改写的打开标签和渲染清单。
        var tag = new CompoundTag();
        if (storage != null) tag.putString("storage_uuid", storage);
        if (openTab >= 0) tag.putInt("open_tab_id", openTab);
        tag.putString("render_upgrade_items", renderUpgrades);
        var stack = new ItemStack(item); stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag)); return stack;
    }
    private static String identity(ItemStack stack) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null || !data.copyTag().contains("storage_uuid") ? null : data.copyTag().getString("storage_uuid");
    }
    /** 与精妙随身包上下文同形的公开类型和编码方法，供地址解码按真实反射路径读取。 */
    public static final class ContextFixture {
        public enum ContextType { ITEM_BACKPACK, ITEM_SUB_BACKPACK }
        private final ContextType type; private final String handler, identifier; private final int slot;
        ContextFixture(ContextType type, String handler, String identifier, int slot) {
            this.type = type; this.handler = handler; this.identifier = identifier; this.slot = slot;
        }
        public ContextType getType() { return type; }
        public void addToBuffer(FriendlyByteBuf buffer) {
            buffer.writeUtf(handler); buffer.writeUtf(identifier); buffer.writeInt(slot); buffer.writeBoolean(false);
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
