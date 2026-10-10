// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.inventory.spi.CarriedBackpack;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.CollectedRecords;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 腾地方：一步一个真实动作，做完重新看背包；做不成换下一步、不原地重试；要动贵重品就停下来问。 */
class InventorySpaceTest {

    // 测试替身：背包就是一张可以改的格子表，腾挪动作直接改它，好核对下一步看到的现场。
    private static final class FakeBackpack implements BackpackView {
        private final List<BackpackStack> stacks = new ArrayList<>();
        private final int capacity;

        FakeBackpack(int capacity, BackpackStack... initial) {
            this.capacity = capacity;
            stacks.addAll(List.of(initial));
        }

        @Override public List<BackpackStack> stacks() {
            return List.copyOf(stacks);
        }

        @Override public int usedSlots() {
            return stacks.size();
        }

        @Override public int totalSlots() {
            return capacity;
        }

        @Override public OptionalInt hotbarSlotOf(String itemId) {
            return OptionalInt.empty();
        }

        @Override public int selectedHotbarSlot() {
            return 0;
        }

        void removeOne(String itemId) {
            for (BackpackStack stack : stacks) {
                if (stack.itemId().equals(itemId)) {
                    stacks.remove(stack);
                    return;
                }
            }
        }
    }

    /** 合并替身：并一对就把那种散着的物品拿掉一格，变化照真读端的样子记一笔。 */
    private static final class FakeMerger implements StackMerger {
        final List<String> merged = new ArrayList<>();
        private final FakeBackpack backpack;

        FakeMerger(FakeBackpack backpack) {
            this.backpack = backpack;
        }

        @Override public Action mergeOne(TaskRecords records) {
            return oneTick(() -> {
                for (BackpackStack stack : backpack.stacks()) {
                    long piles = backpack.stacks().stream().filter(other -> other.itemId().equals(stack.itemId())).count();
                    if (piles > 1) {
                        merged.add(stack.itemId());
                        backpack.removeOne(stack.itemId());
                        records.change(new Change(Change.Kind.OTHER, stack.itemId(), stack.count(), "并成整堆"));
                        return ActionStatus.done();
                    }
                }
                return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "没有能并成一堆的散堆"));
            });
        }
    }

    /** 随身背包替身：放得进就把那一堆从背包里拿走；坏了就每次都放不进。 */
    private static final class FakeCarried implements CarriedBackpack {
        final List<String> stored = new ArrayList<>();
        boolean broken;
        int tried;
        private final FakeBackpack backpack;

        FakeCarried(FakeBackpack backpack) {
            this.backpack = backpack;
        }

        @Override public int freeSlots() {
            return 5;
        }

        @Override public Optional<Action> store(BackpackStack stack, TaskRecords records) {
            return Optional.of(oneTick(() -> {
                tried++;
                if (broken) return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME, "随身背包打不开"));
                stored.add(stack.itemId());
                backpack.removeOne(stack.itemId());
                records.change(new Change(Change.Kind.ITEM_STORED, stack.itemId(), stack.count(), "放进了随身背包"));
                return ActionStatus.done();
            }));
        }
    }

    private interface Step {
        ActionStatus run();
    }

    private static Action oneTick(Step step) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return step.run();
            }

            @Override public String describe() {
                return "替身的一步";
            }
        };
    }

    private static BackpackStack junk(String itemId, int count) {
        return new BackpackStack(itemId, count, 64, false, false, false, true);
    }

    private static BackpackStack pearl() {
        return new BackpackStack("minecraft:ender_pearl", 2, 16, false, false, true, false);
    }

    private static final TickContext NOW = new TickContext() {
        @Override public long gameTick() {
            return 100;
        }

        @Override public PlayerContext player() {
            return null;
        }
    };

    // 推进到收场；腾地方每做完一步回来看一次背包，几十刻足够。
    private static ActionStatus run(Action action) {
        for (int i = 0; i < 50; i++) {
            ActionStatus status = action.tick(NOW);
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        throw new AssertionError("腾地方 50 刻还没收场：" + action.describe());
    }

    private static Action makeRoom(InventorySpace space, int slots, Set<String> keep, TaskRecords records) {
        return space.makeRoom(slots, "捡起掉落的铁锭", keep, Permissions.DEFAULT, records);
    }

    @Test
    void 还有空格就什么都不做() {
        CollectedRecords records = new CollectedRecords();
        InventorySpace space = new InventorySpace(new FakeBackpack(36), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
        assertInstanceOf(ActionStatus.Done.class, run(makeRoom(space, 1, Set.of(), records)));
        assertTrue(records.changes.isEmpty());
    }

    @Test
    void 能合并的散堆先并_不往外挪() {
        FakeBackpack backpack = new FakeBackpack(3,
                junk("minecraft:cobblestone", 32), junk("minecraft:cobblestone", 32), junk("minecraft:dirt", 64));
        FakeMerger merger = new FakeMerger(backpack);
        FakeCarried carried = new FakeCarried(backpack);
        InventorySpace space = new InventorySpace(backpack, Optional.of(merger), Optional.of(carried),
                Optional.empty(), Optional.empty(), Optional.empty());
        CollectedRecords records = new CollectedRecords();

        assertInstanceOf(ActionStatus.Done.class, run(makeRoom(space, 1, Set.of(), records)));
        assertEquals(List.of("minecraft:cobblestone"), merger.merged);
        assertTrue(carried.stored.isEmpty(), "并出一格就够了，泥土不用挪");
        assertEquals(1, records.changes.size());
    }

    @Test
    void 挪最不值钱的那堆_变化记进发起任务的结果() {
        FakeBackpack backpack = new FakeBackpack(1, junk("minecraft:cobblestone", 64));
        FakeCarried carried = new FakeCarried(backpack);
        InventorySpace space = new InventorySpace(backpack, Optional.empty(), Optional.of(carried),
                Optional.empty(), Optional.empty(), Optional.empty());
        CollectedRecords records = new CollectedRecords();

        assertInstanceOf(ActionStatus.Done.class, run(makeRoom(space, 1, Set.of(), records)));
        assertEquals(List.of("minecraft:cobblestone"), carried.stored);
        assertEquals(Change.Kind.ITEM_STORED, records.changes.getFirst().kind());
        assertEquals(64, records.changes.getFirst().count());
    }

    @Test
    void 要留着的东西不往外挪() {
        FakeBackpack backpack = new FakeBackpack(2, junk("minecraft:cobblestone", 64), junk("minecraft:dirt", 64));
        FakeCarried carried = new FakeCarried(backpack);
        InventorySpace space = new InventorySpace(backpack, Optional.empty(), Optional.of(carried),
                Optional.empty(), Optional.empty(), Optional.empty());

        // 正要拿圆石：腾地方不把圆石挪走，挪的是泥土。
        run(makeRoom(space, 1, Set.of("minecraft:cobblestone"), new CollectedRecords()));
        assertEquals(List.of("minecraft:dirt"), carried.stored);
    }

    @Test
    void 一步做不成换下一步_不原地重试() {
        FakeBackpack backpack = new FakeBackpack(1, junk("minecraft:cobblestone", 64));
        FakeCarried carried = new FakeCarried(backpack);
        carried.broken = true;
        InventorySpace space = new InventorySpace(backpack, Optional.empty(), Optional.of(carried),
                Optional.empty(), Optional.empty(), Optional.empty());
        CollectedRecords records = new CollectedRecords();

        // 随身背包打不开，丢东西的部件又没接上：试一次就如实说腾不出，不在随身背包上反复点。
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class,
                run(makeRoom(space, 1, Set.of(), records)));
        assertEquals(1, carried.tried);
        assertEquals(Problem.Kind.INVENTORY_FULL, failed.problem().kind());
        assertTrue(records.attempts.stream().anyMatch(line -> line.contains("随身背包打不开")), records.attempts::toString);
    }

    @Test
    void 只剩贵重品就停下来问_不自己挪() {
        FakeBackpack backpack = new FakeBackpack(1, pearl());
        FakeCarried carried = new FakeCarried(backpack);
        InventorySpace space = new InventorySpace(backpack, Optional.empty(), Optional.of(carried),
                Optional.empty(), Optional.empty(), Optional.empty());
        CollectedRecords records = new CollectedRecords();

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class,
                run(makeRoom(space, 1, Set.of(), records)));
        assertEquals(Problem.Kind.NEED_APPROVAL, failed.problem().kind());
        assertTrue(failed.problem().message().contains("ender_pearl"), failed.problem().message());
        // 问了就没动手：背包没变，也没有账。
        assertTrue(carried.stored.isEmpty());
        assertTrue(records.changes.isEmpty());
    }

    @Test
    void 连贵重品都算上也不够_如实说腾不出() {
        InventorySpace space = new InventorySpace(new FakeBackpack(1, pearl()), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class,
                run(makeRoom(space, 2, Set.of(), new CollectedRecords())));
        assertEquals(Problem.Kind.INVENTORY_FULL, failed.problem().kind());
        assertTrue(failed.problem().message().contains("腾不出 2 格"), failed.problem().message());
    }
}
