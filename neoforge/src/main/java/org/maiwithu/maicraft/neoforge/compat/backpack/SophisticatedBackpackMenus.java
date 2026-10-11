// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.backpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.network.PacketDistributor;
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer;
import net.p3pp3rf1y.sophisticatedbackpacks.network.BackpackOpenPayload;

import org.maiwithu.maicraft.compat.backpack.BackpackMenus;

/**
 * 精妙背包的界面读写端：认背包的菜单、读主存储的槽位、发打开请求。
 * 直接引用模组的类，只翻译不判断，所以只在联动清单确认装了、版本在范围内之后才会被加载。
 */
public final class SophisticatedBackpackMenus implements BackpackMenus {

    /** 主存储槽位数的常理上限：带无限格升级的背包读出来的数对不上常理，按读不出处理。 */
    private static final int SANE_STORAGE_SLOTS = 54 * 12;

    @Override public boolean isBackpackMenu(AbstractContainerMenu menu) {
        return menu instanceof BackpackContainer;
    }

    @Override public Optional<List<Integer>> mainStorageSlotIds(AbstractContainerMenu menu) {
        if (!(menu instanceof BackpackContainer backpack)) {
            return Optional.empty();
        }
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < menu.slots.size(); slot++) {
            if (backpack.isStorageInventorySlot(slot)) {
                slots.add(slot);
            }
        }
        // 主存储大得离谱（无限格升级这类）：客户端同步不到那么多格，读数对不上，按读不出处理。
        if (slots.size() > SANE_STORAGE_SLOTS) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(slots));
    }

    @Override public List<Integer> playerSlotIds(AbstractContainerMenu menu) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < menu.slots.size(); slot++) {
            if (menu.slots.get(slot).container instanceof Inventory) {
                slots.add(slot);
            }
        }
        return List.copyOf(slots);
    }

    @Override public void openCarried() {
        // 模组自己的打开请求：与玩家按 B 键发的是同一种包，服务端找身上第一只背包打开。
        PacketDistributor.sendToServer(new BackpackOpenPayload());
    }
}
