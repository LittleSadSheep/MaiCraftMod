// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.ReportsUnconfirmed;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.task.TickContext;

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

        void remove(String itemId) {
            stacks.removeIf(stack -> stack.itemId().equals(itemId));
        }
    }

    private static final class FakeDropper implements ItemDropper, ReportsUnconfirmed {
        final List<String> dropped = new ArrayList<>();
        final List<String> facts = new ArrayList<>();

        @Override
        public SpaceStepResult drop(String itemId, int count, TickContext context) {
            dropped.add(itemId);
            facts.add("腾地方丢 " + itemId + "：抛出的 1 件没等到确认");
            return SpaceStepResult.done(new Change(Change.Kind.ITEM_DROPPED, itemId, count, null));
        }

        @Override
        public List<String> unconfirmedFacts() {
            return List.copyOf(facts);
        }
    }

    /** 合并替身：一步并掉一格散堆，把背包里那种散着的物品拿掉一格。 */
    private static final class FakeMerger implements StackMerger {
        final List<String> merged = new ArrayList<>();
        private final FakeBackpack backpack;

        FakeMerger(FakeBackpack backpack) {
            this.backpack = backpack;
        }

        @Override
        public SpaceStepResult mergeOne(TickContext context) {
            String scattered = null;
            for (BackpackStack stack : backpack.stacks()) {
                long pilesOfSame = backpack.stacks().stream()
                        .filter(other -> other.itemId().equals(stack.itemId())).count();
                if (pilesOfSame > 1) {
                    scattered = stack.itemId();
                    break;
                }
            }
            if (scattered == null) return SpaceStepResult.cannotDo("没有可合并的散堆");
            merged.add(scattered);
            backpack.remove(scattered);
            return SpaceStepResult.done(new Change(Change.Kind.OTHER, scattered, 1, "并成整堆"));
        }
    }

    private static BackpackStack junk(String itemId, int count) {
        return new BackpackStack(itemId, count, 64, false, false, false, true);
    }

    private static InventorySpace spaceWithDropper(FakeBackpack backpack, FakeDropper dropper) {
        return new InventorySpace(backpack, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(dropper));
    }

    // 本刻的上下文替身：腾挪的一步就在这一刻里推进。
    private static final TickContext NOW = new TickContext() {
        @Override public long gameTick() {
            return 100;
        }

        @Override public PlayerContext player() {
            return null;
        }
    };

    private static InventorySpace.Result ensureFree(InventorySpace space, int slots) {
        return space.ensureFree(slots, "捡起掉落的铁锭", Permissions.DEFAULT, NOW);
    }

    @Test
    void 还有空格就什么都不做() {
        InventorySpace.Result result = ensureFree(spaceWithDropper(new FakeBackpack(36), new FakeDropper()), 1);
        assertEquals(InventorySpace.State.FREE, result.state());
        assertTrue(result.changes().isEmpty());
    }

    @Test
    void 丢垃圾腾地方_一步一笔账() {
        FakeBackpack backpack = new FakeBackpack(1, junk("minecraft:cobblestone", 64));
        FakeDropper dropper = new FakeDropper();
        InventorySpace space = spaceWithDropper(backpack, dropper);

        InventorySpace.Result first = ensureFree(space, 1);
        assertEquals(InventorySpace.State.PROGRESS, first.state());
        // 丢出去的变化记进账：什么、多少、为什么丢。
        assertEquals(1, first.changes().size());
        assertEquals(Change.Kind.ITEM_DROPPED, first.changes().getFirst().kind());
        assertEquals("minecraft:cobblestone", first.changes().getFirst().what());
        assertEquals(64, first.changes().getFirst().count());
        assertTrue(first.changes().getFirst().note().contains("捡起掉落的铁锭"));

        // 世界真的变了：背包里少了一堆，下一次就腾够了。
        backpack.clearAll();
        InventorySpace.Result second = ensureFree(space, 1);
        assertEquals(InventorySpace.State.FREE, second.state());
    }

    @Test
    void 能合并的散堆先并_不先丢() {
        FakeBackpack backpack = new FakeBackpack(3,
                junk("minecraft:cobblestone", 32), junk("minecraft:cobblestone", 32),
                junk("minecraft:dirt", 64));
        FakeMerger merger = new FakeMerger(backpack);
        FakeDropper dropper = new FakeDropper();
        InventorySpace space = new InventorySpace(backpack, Optional.of(merger), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(dropper));

        // 背包满着要腾一格：先并圆石的散堆，泥土整格用不着丢。
        InventorySpace.Result result = ensureFree(space, 1);
        assertEquals(InventorySpace.State.PROGRESS, result.state());
        assertEquals(List.of("minecraft:cobblestone"), merger.merged);
        assertTrue(dropper.dropped.isEmpty());
        assertEquals(Change.Kind.OTHER, result.changes().getFirst().kind());
    }

    @Test
    void 只剩贵重品就停下来问_不自己丢() {
        BackpackStack pearl = new BackpackStack("minecraft:ender_pearl", 2, 16, false, false, true, false);
        FakeBackpack backpack = new FakeBackpack(1, pearl);
        FakeDropper dropper = new FakeDropper();

        InventorySpace.Result result = ensureFree(spaceWithDropper(backpack, dropper), 1);

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

        InventorySpace.Result result = ensureFree(space, 2);
        assertEquals(InventorySpace.State.IMPOSSIBLE, result.state());
        assertTrue(result.problem().message().contains("腾不出 2 格"));
    }

    @Test
    void 丢了却没能确认的交互随结果交回_不重复() {
        FakeBackpack backpack = new FakeBackpack(1, junk("minecraft:cobblestone", 64));
        FakeDropper dropper = new FakeDropper();
        InventorySpace space = spaceWithDropper(backpack, dropper);

        InventorySpace.Result first = ensureFree(space, 1);
        // 丢的这一步把没能确认的事实一句带了回来。
        assertEquals(List.of("腾地方丢 minecraft:cobblestone：抛出的 1 件没等到确认"), first.unconfirmed());
        // 下一刻再腾：接缝没有新交回的事实就不重复。
        backpack.clearAll();
        InventorySpace.Result second = ensureFree(space, 1);
        assertTrue(second.unconfirmed().isEmpty());
    }
}
