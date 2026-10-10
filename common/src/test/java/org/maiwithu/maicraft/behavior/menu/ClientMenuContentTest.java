// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 界面内容读端的分侧与证明不了给空的判定：用替身摆布局与逐槽内容，离线验证读数的形状。
 * 真实客户端的逐刻同步判断留给实机验收。
 */
class ClientMenuContentTest {

    @BeforeAll
    static void 引导物品注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }




    /** 替身布局：按给定清单回答菜单类型与分侧。 */
    private static final class FakeSlots implements MenuSlots {
        private final String typeId;
        private final int slotCount;
        private final Set<Integer> playerSide;

        FakeSlots(String typeId, int slotCount, Set<Integer> playerSide) {
            this.typeId = typeId;
            this.slotCount = slotCount;
            this.playerSide = playerSide;
        }

        @Override public String menuTypeId() { return typeId; }
        @Override public int slotCount() { return slotCount; }
        @Override public boolean playerBacked(int slot) { return playerSide.contains(slot); }
    }

    private static MenuChannel emptyChannel() {
        return new MenuChannel() {
            @Override public boolean stillOpen() { return true; }
            @Override public boolean cursorCarrying() { return false; }
            @Override public boolean click(int slot, int button) { return true; }
            @Override public void requestClose() {}
        };
    }

    @Test
    void 普通容器界面给出分侧读数() {
        // 27 格箱子（菜单类型 generic_9x3）+ 36 格角色侧：容器侧 0..26，角色侧 27..62。
        Map<Integer, SlotSnapshot> contents = new HashMap<>();
        contents.put(0, SlotSnapshot.of(new ItemStack(Items.OAK_LOG, 8)));
        MenuSlots slots = new FakeSlots("minecraft:generic_9x3", 63,
                IntStream.rangeClosed(27, 62).boxed().collect(Collectors.toSet()));
        Optional<MenuContent.Reading> reading = ClientMenuContent.reading(
                slots, emptyChannel(), slot -> contents.getOrDefault(slot, SlotSnapshot.empty()));
        assertTrue(reading.isPresent());
        MenuContent.Reading value = reading.get();
        assertEquals(27, value.containerSlotIds().size());
        assertEquals(36, value.playerSlotIds().size());
        // 槽位号与快照一一对应：容器侧第 0 格读到的就是摆进去的那叠木头。
        assertEquals(0, value.containerSlotIds().get(0));
        assertEquals(8, value.containerSnapshots().get(0).count());
        assertTrue(value.containerSnapshots().get(0).sameIdentity(
                SlotSnapshot.of(new ItemStack(Items.OAK_LOG, 8))));
    }

    @Test
    void 认不出的模组界面不给读数() {
        MenuSlots slots = new FakeSlots("somemod:weird_screen", 10,
                Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9));
        assertTrue(ClientMenuContent.reading(slots, emptyChannel(), slot -> SlotSnapshot.empty()).isEmpty());
    }

    @Test
    void 角色侧格数对不上不给读数() {
        // 容器侧界面上角色侧只有 9 格：证明不了两侧怎么分，不报读数。
        MenuSlots slots = new FakeSlots("minecraft:generic_9x3", 18,
                IntStream.range(9, 18).boxed().collect(Collectors.toSet()));
        assertTrue(ClientMenuContent.reading(slots, emptyChannel(), slot -> SlotSnapshot.empty()).isEmpty());
    }
}
