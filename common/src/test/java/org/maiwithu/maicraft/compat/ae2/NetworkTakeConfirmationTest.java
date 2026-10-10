// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.menu.MoveConfirmation.Verdict;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;

/** 取货确认：只看角色这一侧；整组、单件、放下各按自己的规矩核对，对不上不凑数。 */
class NetworkTakeConfirmationTest {

    @BeforeAll
    static void 引导物品注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static SlotSnapshot cobble(int count) {
        return SlotSnapshot.of(new ItemStack(Items.COBBLESTONE, count));
    }

    // 物品快照要在注册表引导之后才建得出来，所以写成方法，不放静态字段。
    private static SlotSnapshot sample() {
        return cobble(1);
    }

    private static SlotSnapshot empty() {
        return SlotSnapshot.empty();
    }

    @Test
    void 整组取_背包里多出来且光标空才算() {
        NetworkTakeConfirmation.Result result = NetworkTakeConfirmation.stackTaken(sample(),
                List.of(cobble(30), empty()), List.of(cobble(64), cobble(30)), empty());
        assertEquals(new NetworkTakeConfirmation.Result(Verdict.CONFIRMED, 64), result);
    }

    @Test
    void 整组取_纹丝不动是回显还没到() {
        NetworkTakeConfirmation.Result result = NetworkTakeConfirmation.stackTaken(sample(),
                List.of(cobble(30), empty()), List.of(cobble(30), empty()), empty());
        assertEquals(Verdict.WAITING, result.verdict());
    }

    @Test
    void 整组取_光标上挂着东西或多出一组以上都是对不上() {
        assertEquals(Verdict.DIVERGED, NetworkTakeConfirmation.stackTaken(sample(),
                List.of(empty()), List.of(cobble(64)), cobble(3)).verdict());
        assertEquals(Verdict.DIVERGED, NetworkTakeConfirmation.stackTaken(sample(),
                List.of(empty(), empty()), List.of(cobble(64), cobble(10)), empty()).verdict());
        assertEquals(Verdict.DIVERGED, NetworkTakeConfirmation.stackTaken(sample(),
                List.of(cobble(20)), List.of(cobble(10)), empty()).verdict());
    }

    @Test
    void 取一件_光标上同一种恰好多一件才算() {
        assertEquals(new NetworkTakeConfirmation.Result(Verdict.CONFIRMED, 1),
                NetworkTakeConfirmation.oneTaken(sample(), empty(), cobble(1)));
        assertEquals(new NetworkTakeConfirmation.Result(Verdict.CONFIRMED, 1),
                NetworkTakeConfirmation.oneTaken(sample(), cobble(3), cobble(4)));
        assertEquals(Verdict.WAITING, NetworkTakeConfirmation.oneTaken(sample(), cobble(3), cobble(3)).verdict());
        assertEquals(Verdict.DIVERGED, NetworkTakeConfirmation.oneTaken(sample(), cobble(3), cobble(5)).verdict());
        assertEquals(Verdict.DIVERGED, NetworkTakeConfirmation.oneTaken(sample(), empty(),
                SlotSnapshot.of(new ItemStack(Items.DIRT))).verdict());
    }

    @Test
    void 放回网络_光标空了或同一种少了几件才算() {
        assertEquals(new NetworkTakeConfirmation.Result(Verdict.CONFIRMED, 5),
                NetworkTakeConfirmation.putBack(cobble(5), empty()));
        assertEquals(new NetworkTakeConfirmation.Result(Verdict.CONFIRMED, 3),
                NetworkTakeConfirmation.putBack(cobble(5), cobble(2)));
        assertEquals(Verdict.WAITING, NetworkTakeConfirmation.putBack(cobble(5), cobble(5)).verdict());
        assertEquals(Verdict.DIVERGED, NetworkTakeConfirmation.putBack(cobble(5), cobble(7)).verdict());
        assertEquals(Verdict.DIVERGED, NetworkTakeConfirmation.putBack(cobble(5),
                SlotSnapshot.of(new ItemStack(Items.DIRT))).verdict());
        assertThrows(IllegalArgumentException.class, () -> NetworkTakeConfirmation.putBack(empty(), empty()));
    }

    @Test
    void 放下光标_光标空掉且那一格恰好多了那么多件() {
        assertEquals(new NetworkTakeConfirmation.Result(Verdict.CONFIRMED, 5),
                NetworkTakeConfirmation.putDown(cobble(5), cobble(30), empty(), cobble(35)));
        assertEquals(Verdict.WAITING,
                NetworkTakeConfirmation.putDown(cobble(5), cobble(30), cobble(5), cobble(30)).verdict());
        assertEquals(Verdict.DIVERGED,
                NetworkTakeConfirmation.putDown(cobble(5), cobble(30), empty(), cobble(33)).verdict());
        assertThrows(IllegalArgumentException.class,
                () -> NetworkTakeConfirmation.putDown(empty(), cobble(30), empty(), cobble(30)));
    }
}
