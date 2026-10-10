// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;

/** 从终端怎么取：缺一组以上整组取，不足一组一件一件取再放下；只拿需要的量，背包放不下就停。 */
class NetworkTakePlanTest {

    @BeforeAll
    static void 引导物品注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Ae2TerminalMenu.StockEntry cobbleInNetwork(long stored) {
        return new Ae2TerminalMenu.StockEntry(7, new ItemStack(Items.COBBLESTONE), stored, false);
    }

    /** 角色背包那一侧：36 格，槽位号从 9 起；前几格按给的样子摆，其余为空。 */
    private static List<SlotSnapshot> playerSide(ItemStack... firstSlots) {
        List<SlotSnapshot> slots = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            slots.add(i < firstSlots.length ? SlotSnapshot.of(firstSlots[i]) : SlotSnapshot.empty());
        }
        return slots;
    }

    private static List<SlotSnapshot> fullOf(ItemStack stack) {
        List<SlotSnapshot> slots = new ArrayList<>();
        for (int i = 0; i < 36; i++) slots.add(SlotSnapshot.of(stack));
        return slots;
    }

    private static final List<Integer> PLAYER_SLOT_IDS = IntStream.range(9, 45).boxed().toList();

    @Test
    void 缺一组以上就整组取() {
        NetworkTakePlan.Move move = NetworkTakePlan.next(69, SlotSnapshot.empty(), cobbleInNetwork(200),
                PLAYER_SLOT_IDS, playerSide());
        assertEquals(new NetworkTakePlan.TakeStack(7), move);
    }

    @Test
    void 不足一组就一件一件取() {
        NetworkTakePlan.Move move = NetworkTakePlan.next(5, SlotSnapshot.empty(), cobbleInNetwork(200),
                PLAYER_SLOT_IDS, playerSide());
        assertEquals(new NetworkTakePlan.TakeOne(7), move);
    }

    @Test
    void 光标上凑够了就放进能整个装下的一格_先叠到同种没满的堆上() {
        SlotSnapshot cursor = SlotSnapshot.of(new ItemStack(Items.COBBLESTONE, 5));
        NetworkTakePlan.Move move = NetworkTakePlan.next(5, cursor, cobbleInNetwork(195),
                PLAYER_SLOT_IDS, playerSide(new ItemStack(Items.DIRT, 64), new ItemStack(Items.COBBLESTONE, 30)));
        assertEquals(new NetworkTakePlan.PutDown(10), move);
    }

    @Test
    void 光标上还不够就接着取一件() {
        SlotSnapshot cursor = SlotSnapshot.of(new ItemStack(Items.COBBLESTONE, 3));
        NetworkTakePlan.Move move = NetworkTakePlan.next(5, cursor, cobbleInNetwork(197),
                PLAYER_SLOT_IDS, playerSide());
        assertEquals(new NetworkTakePlan.TakeOne(7), move);
    }

    @Test
    void 网络里没了就把光标上已有的放下() {
        SlotSnapshot cursor = SlotSnapshot.of(new ItemStack(Items.COBBLESTONE, 2));
        NetworkTakePlan.Move move = NetworkTakePlan.next(5, cursor, cobbleInNetwork(0),
                PLAYER_SLOT_IDS, playerSide());
        assertEquals(new NetworkTakePlan.PutDown(9), move);
    }

    @Test
    void 再叠一件就没有一格放得下时先放下() {
        // 背包只剩一格能放圆石，而且那一格只差 2 件就满：光标上 2 件时不再往上叠。
        List<SlotSnapshot> slots = fullOf(new ItemStack(Items.DIRT, 64));
        slots.set(0, SlotSnapshot.of(new ItemStack(Items.COBBLESTONE, 62)));
        SlotSnapshot cursor = SlotSnapshot.of(new ItemStack(Items.COBBLESTONE, 2));
        NetworkTakePlan.Move move = NetworkTakePlan.next(5, cursor, cobbleInNetwork(100), PLAYER_SLOT_IDS, slots);
        assertEquals(new NetworkTakePlan.PutDown(9), move);
    }

    @Test
    void 背包满了就停() {
        NetworkTakePlan.Move move = NetworkTakePlan.next(64, SlotSnapshot.empty(), cobbleInNetwork(200),
                PLAYER_SLOT_IDS, fullOf(new ItemStack(Items.DIRT, 64)));
        assertInstanceOf(NetworkTakePlan.NoRoom.class, move);
    }

    @Test
    void 够数或网络里没了就算这一条取完() {
        assertInstanceOf(NetworkTakePlan.Done.class, NetworkTakePlan.next(0, SlotSnapshot.empty(),
                cobbleInNetwork(200), PLAYER_SLOT_IDS, playerSide()));
        assertInstanceOf(NetworkTakePlan.Done.class, NetworkTakePlan.next(10, SlotSnapshot.empty(),
                cobbleInNetwork(0), PLAYER_SLOT_IDS, playerSide()));
    }

    @Test
    void 默认样子的先取_再按存量从多到少() {
        ItemStack named = new ItemStack(Items.IRON_PICKAXE);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("老伙计"));
        List<Ae2TerminalMenu.StockEntry> stock = List.of(
                new Ae2TerminalMenu.StockEntry(1, named, 5, false),
                new Ae2TerminalMenu.StockEntry(2, new ItemStack(Items.IRON_PICKAXE), 1, false),
                new Ae2TerminalMenu.StockEntry(3, new ItemStack(Items.COBBLESTONE), 900, false),
                new Ae2TerminalMenu.StockEntry(4, new ItemStack(Items.IRON_PICKAXE), 0, true));
        List<Ae2TerminalMenu.StockEntry> picks = NetworkTakePlan.matching(stock,
                sample -> sample.is(Items.IRON_PICKAXE));
        assertEquals(List.of(2L, 1L), picks.stream().map(Ae2TerminalMenu.StockEntry::serial).toList());
    }
}
