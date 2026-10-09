// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.result.Change;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 腾挪的执行：一步一刻，变化逐笔记账；要问的处境原样带问题，不自己丢贵重品。 */
class InventorySpaceTest {

    // 测试替身：背包就是一张可以改的格子表，腾挪动作直接改它，好核对下一步看到的现场。
    private static final class FakeBackpack implements BackpackView {
        private final List<BackpackStack> stacks = new ArrayList<>();
        private final int capacity;

        FakeBackpack(int capacity, BackpackStack... initial) {
            this.capacity = capacity;
            stacks.addAll(List.of(initial));
        }

        @Override
        public List<BackpackStack> stacks() {
            return List.copyOf(stacks);
        }

        @Override
        public int usedSlots() {
            return stacks.size();
        }

        @Override
        public int totalSlots() {
            return capacity;
        }

        @Override
        public OptionalInt hotbarSlotOf(String itemId) {
            return OptionalInt.empty();
        }

        @Override
        public int selectedHotbarSlot() {
            return 0;
        }

        void clearAll() {
            stacks.clear();
        }
    }

    private static final class FakeDropper implements ItemDropper {
        final List<String> dropped = new ArrayList<>();

        @Override
        public Optional<Change> drop(String itemId, int count) {
            dropped.add(itemId);
            return Optional.of(new Change(Change.Kind.ITEM_DROPPED, itemId, count, null));
        }
    }

    private static BackpackStack junk(String itemId, int count) {
        return new BackpackStack(itemId, count, 64, false, false, false, true);
    }

    private static InventorySpace spaceWithDropper(FakeBackpack backpack, FakeDropper dropper) {
        return new InventorySpace(backpack, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(dropper));
    }

    @Test
    void 还有空格就什么都不做() {
        InventorySpace.Result result = spaceWithDropper(new FakeBackpack(36), new FakeDropper())
                .ensureFree(1, "捡起掉落的铁锭");
        assertEquals(InventorySpace.State.FREE, result.state());
        assertTrue(result.changes().isEmpty());
    }

    @Test
    void 丢垃圾腾地方_一步一笔账() {
        FakeBackpack backpack = new FakeBackpack(1, junk("minecraft:cobblestone", 64));
        FakeDropper dropper = new FakeDropper();
        InventorySpace space = spaceWithDropper(backpack, dropper);

        InventorySpace.Result first = space.ensureFree(1, "捡起掉落的铁锭");
        assertEquals(InventorySpace.State.PROGRESS, first.state());
        // 丢出去的变化记进账：什么、多少、为什么丢。
        assertEquals(1, first.changes().size());
        assertEquals(Change.Kind.ITEM_DROPPED, first.changes().getFirst().kind());
        assertEquals("minecraft:cobblestone", first.changes().getFirst().what());
        assertEquals(64, first.changes().getFirst().count());
        assertTrue(first.changes().getFirst().note().contains("捡起掉落的铁锭"));

        // 世界真的变了：背包里少了一堆，下一次就腾够了。
        backpack.clearAll();
        InventorySpace.Result second = space.ensureFree(1, "捡起掉落的铁锭");
        assertEquals(InventorySpace.State.FREE, second.state());
    }

    @Test
    void 只剩贵重品就停下来问_不自己丢() {
        BackpackStack pearl = new BackpackStack("minecraft:ender_pearl", 2, 16, false, false, true, false);
        FakeBackpack backpack = new FakeBackpack(1, pearl);
        FakeDropper dropper = new FakeDropper();

        InventorySpace.Result result = spaceWithDropper(backpack, dropper).ensureFree(1, "捡起掉落的铁锭");

        assertEquals(InventorySpace.State.NEED_ASK, result.state());
        assertEquals(Question.Reason.NEED_APPROVAL, result.question().reason());
        // 问了就没动手：背包没变，也没有丢弃的账。
        assertTrue(dropper.dropped.isEmpty());
        assertTrue(result.changes().isEmpty());
    }

    @Test
    void 接缝全没接上_腾不出就如实说() {
        BackpackStack pearl = new BackpackStack("minecraft:ender_pearl", 2, 16, false, false, true, false);
        FakeBackpack backpack = new FakeBackpack(1, pearl);
        InventorySpace space = new InventorySpace(backpack, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());

        InventorySpace.Result result = space.ensureFree(2, "捡起掉落的铁锭");
        assertEquals(InventorySpace.State.IMPOSSIBLE, result.state());
        assertTrue(result.problem().message().contains("腾不出 2 格"));
    }
}
