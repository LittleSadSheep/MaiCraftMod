// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.BackpackStack;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 存什么的判定：点名照存，不点名按随身要留的清单留。 */
class DepositDeciderTest {
    private static final boolean NEVER_TAGGED = false;

    private static BackpackStack stack(String id, int count, boolean gear, boolean food,
            boolean precious, boolean building) {
        return new BackpackStack(id, count, 64, gear, food, precious, building);
    }

    @Test
    void unnamedDepositKeepsGearFoodTorchAndOneFootBlock() {
        List<BackpackStack> backpack = List.of(
                stack("minecraft:iron_pickaxe", 1, true, false, false, false),
                stack("minecraft:cooked_beef", 20, false, true, false, false),
                stack("minecraft:torch", 30, false, false, false, false),
                stack("minecraft:shield", 1, true, false, false, false),
                stack("minecraft:cobblestone", 64, false, false, false, true),
                stack("minecraft:cobblestone", 64, false, false, false, true),
                stack("minecraft:diamond", 5, false, false, true, false),
                stack("minecraft:rotten_flesh", 12, false, false, false, false));
        List<DepositDecider.ToDeposit> chosen = DepositDecider.choose(backpack, List.of(), null, (itemId, tag) -> NEVER_TAGGED);
        List<String> deposited = chosen.stream().map(item -> item.stack().itemId()).toList();
        // 工具、食物、火把、盾牌、第一组建材留在身上；第二组建材、贵重品、其余全存。
        assertEquals(List.of("minecraft:cobblestone", "minecraft:diamond", "minecraft:rotten_flesh"), deposited);
    }

    @Test
    void namedItemsGoEvenIfTheyWouldStay() {
        List<BackpackStack> backpack = List.of(
                stack("minecraft:cooked_beef", 20, false, true, false, false),
                stack("minecraft:torch", 30, false, false, false, false));
        List<DepositDecider.ToDeposit> chosen =
                DepositDecider.choose(backpack, List.of("minecraft:cooked_beef", "minecraft:torch"), null, (itemId, tag) -> NEVER_TAGGED);
        assertEquals(2, chosen.size());
    }

    @Test
    void tagFilterMatchesThroughTagSeam() {
        List<BackpackStack> backpack = List.of(
                stack("minecraft:oak_log", 10, false, false, false, true),
                stack("minecraft:cobblestone", 10, false, false, false, true));
        // #minecraft:logs 只有橡木对得上（替身判断写死这条）。
        List<DepositDecider.ToDeposit> chosen = DepositDecider.choose(backpack, List.of("#minecraft:logs"),
                null, (itemId, tag) -> itemId.equals("minecraft:oak_log") && tag.equals("minecraft:logs"));
        assertEquals(List.of("minecraft:oak_log"),
                chosen.stream().map(item -> item.stack().itemId()).toList());
    }

    @Test
    void countCapsTotalAmountAcrossStacks() {
        List<BackpackStack> backpack = List.of(
                stack("minecraft:dirt", 30, false, false, false, false),
                stack("minecraft:dirt", 30, false, false, false, false),
                stack("minecraft:gravel", 40, false, false, false, false));
        List<DepositDecider.ToDeposit> chosen = DepositDecider.choose(backpack, List.of(), 50, (itemId, tag) -> NEVER_TAGGED);
        assertEquals(50, chosen.stream().mapToLong(DepositDecider.ToDeposit::amount).sum());
        assertEquals(30, chosen.getFirst().amount());
        assertEquals(20, chosen.get(1).amount());
    }
}
