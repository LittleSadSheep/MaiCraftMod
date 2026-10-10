// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.inventory.ContainerChooser;
import org.maiwithu.maicraft.behavior.inventory.SpotsContainers;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 存东西的任务：挑容器、点开、逐笔搬、满了换下一只、被打断重新点开，都对着离线替身跑。 */
class DepositTaskTest {

    private static final String DIRT = "minecraft:dirt";
    private static final String SAND = "minecraft:sand";
    private static final String STONE = "minecraft:stone";
    private static final String COBBLE = "minecraft:cobblestone";
    private static final WorldPosition NEAR = WorldPosition.here(2, 64, 0);
    private static final WorldPosition FAR = WorldPosition.here(9, 64, 0);

    @BeforeAll
    static void 引导物品注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 不点名时工具食物和一组垫脚方块留身上_其余存进箱子() {
        Rig rig = new Rig();
        rig.carry("minecraft:iron_pickaxe", 1).carry("minecraft:bread", 5).carry(COBBLE, 64).carry(COBBLE, 64)
                .carry(DIRT, 32);
        FakeChest chest = rig.chest(NEAR, false);
        TaskResult result = rig.run(rig.input(null, List.of(), null));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(64, chest.total(COBBLE));
        assertEquals(32, chest.total(DIRT));
        assertEquals(64, rig.carried(COBBLE), "第一组建材留在身上当垫脚");
        assertEquals(5, rig.carried("minecraft:bread"));
        assertTrue(chest.closed);
        assertEquals(96, stored(result));
    }

    @Test
    void 只存一部分时拆成半堆放进一格_不多搬() {
        Rig rig = new Rig();
        rig.carry(DIRT, 32);
        FakeChest chest = rig.chest(NEAR, false);
        TaskResult result = rig.run(rig.input(null, List.of(DIRT), 10));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(10, chest.total(DIRT));
        assertEquals(22, rig.carried(DIRT));
        assertEquals(10, stored(result));
    }

    @Test
    void 界面刚刷新那一刻点不出去_下一刻再点_照常存完不判成放不下() {
        // 整堆的快速移动撞上一下"本刻发不出去"。
        Rig whole = new Rig();
        whole.carry(DIRT, 32);
        FakeChest wholeChest = whole.chest(NEAR, false);
        wholeChest.notReadyClicks = 1;
        TaskResult first = whole.run(whole.input(null, List.of(DIRT), null));
        assertEquals(TaskResult.Status.DONE, first.status(), first.summary());
        assertEquals(32, wholeChest.total(DIRT));
        assertEquals(0, wholeChest.notReadyClicks, "发不出去的那一下被下一刻补上了");

        // 拆半堆的几下普通点击里，头两下撞上"本刻发不出去"：不跳过那几下，只存要的 10 件。
        Rig part = new Rig();
        part.carry(SAND, 32);
        FakeChest partChest = part.chest(NEAR, false);
        partChest.notReadyClicks = 2;
        TaskResult second = part.run(part.input(null, List.of(SAND), 10));
        assertEquals(TaskResult.Status.DONE, second.status(), second.summary());
        assertEquals(10, partChest.total(SAND));
        assertEquals(22, part.carried(SAND));
        assertEquals(0, partChest.notReadyClicks);
    }

    @Test
    void 第一只装满了换第二只() {
        Rig rig = new Rig();
        rig.carry(DIRT, 32);
        FakeChest full = rig.chest(NEAR, false);
        full.fill(STONE);
        FakeChest empty = rig.chest(FAR, false);
        TaskResult result = rig.run(rig.input(null, List.of(DIRT), null));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(32, empty.total(DIRT));
        assertTrue(full.closed && empty.closed);
        assertTrue(result.attempts().stream().anyMatch(attempt -> attempt.result().contains("放不下")));
    }

    @Test
    void 都满了_部分完成并写明还剩多少() {
        Rig rig = new Rig();
        rig.carry(DIRT, 32).carry(SAND, 32);
        FakeChest chest = rig.chest(NEAR, false);
        chest.fill(STONE);
        chest.slots.set(0, ItemStack.EMPTY);
        TaskResult result = rig.run(rig.input(null, List.of(), null));
        assertEquals(TaskResult.Status.PARTIAL, result.status(), result.summary());
        assertEquals(Problem.Kind.NOT_FOUND, result.problem().kind());
        assertTrue(result.remaining().stream().anyMatch(left -> left.contains(SAND) && left.contains("32")));
    }

    @Test
    void 附近只有别人的箱子_先问() {
        Rig rig = new Rig();
        rig.carry(DIRT, 32);
        rig.chest(NEAR, true);
        TaskResult result = rig.run(rig.input(null, List.of(), null));
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NEED_APPROVAL, result.problem().kind());
        assertEquals(0, rig.opens);
    }

    @Test
    void 点名别人的箱子_照样存() {
        Rig rig = new Rig();
        rig.carry(DIRT, 32);
        FakeChest chest = rig.chest(NEAR, true);
        TaskResult result = rig.run(rig.input(new Target.Position(NEAR.x(), NEAR.y(), NEAR.z(), null), List.of(), null));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(32, chest.total(DIRT));
    }

    @Test
    void 打不开就换下一只() {
        Rig rig = new Rig();
        rig.carry(DIRT, 32);
        rig.chest(NEAR, false).locked = true;
        FakeChest second = rig.chest(FAR, false);
        TaskResult result = rig.run(rig.input(null, List.of(), null));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(32, second.total(DIRT));
        assertTrue(result.attempts().stream().anyMatch(attempt -> attempt.tried().contains("打开")));
    }

    @Test
    void 一只都打不开_按打不开的原因失败() {
        Rig rig = new Rig();
        rig.carry(DIRT, 32);
        rig.chest(NEAR, false).locked = true;
        TaskResult result = rig.run(rig.input(null, List.of(), null));
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.REFUSED_BY_GAME, result.problem().kind());
    }

    @Test
    void 搬到一半被打断_先关界面_恢复后重新点开接着存() {
        Rig rig = new Rig();
        rig.carry(DIRT, 32).carry(SAND, 32);
        FakeChest chest = rig.chest(NEAR, false);
        DepositTask task = rig.task(rig.input(null, List.of(), null));
        while (chest.total(DIRT) == 0) task.tick(rig.tick());
        task.pause();
        assertTrue(chest.closed, "被打断时先关上界面");
        TaskResult result = rig.finish(task);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(2, rig.opens);
        assertEquals(32, chest.total(SAND));
    }

    @Test
    void 被取消时关上界面_结算过的照记_没结算的进未确认() {
        Rig rig = new Rig();
        rig.carry(DIRT, 32).carry(SAND, 32);
        FakeChest chest = rig.chest(NEAR, false);
        DepositTask task = rig.task(rig.input(null, List.of(), null));
        // 泥土那一笔结算过了，沙子刚搬进去还没来得及结算就被取消。
        while (chest.total(SAND) == 0) task.tick(rig.tick());
        TaskResult result = task.close(CloseReason.CANCELLED);
        assertTrue(chest.closed);
        assertEquals(TaskResult.Status.CANCELLED, result.status());
        assertEquals(32, stored(result));
        assertEquals(1, result.unconfirmed().size());
    }

    @Test
    void 标签点名只存挂着标签的东西() {
        Rig rig = new Rig();
        rig.carry("minecraft:oak_log", 16).carry(DIRT, 32);
        FakeChest chest = rig.chest(NEAR, false);
        TaskResult result = rig.run(rig.input(null, List.of("#minecraft:logs"), null));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(16, chest.total("minecraft:oak_log"));
        assertEquals(0, chest.total(DIRT));
    }

    @Test
    void 身上没有要存的东西_直接完成() {
        Rig rig = new Rig();
        rig.carry(DIRT, 32);
        rig.chest(NEAR, false);
        TaskResult result = rig.run(rig.input(null, List.of("minecraft:diamond"), null));
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(0, rig.opens);
    }

    private static int stored(TaskResult result) {
        return result.changes().stream().filter(change -> change.kind() == Change.Kind.ITEM_STORED)
                .mapToInt(Change::count).sum();
    }

    private static ItemStack stack(String id, int count) {
        return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)), count);
    }

    /** 一套离线替身：背包、附近的箱子、打开箱子。 */
    private static final class Rig {
        /** 角色背包的 36 格，槽位号 0–35。 */
        final List<ItemStack> player = new ArrayList<>();
        final Map<WorldPosition, FakeChest> chests = new HashMap<>();
        int opens;
        private long tick;

        Rig() {
            for (int i = 0; i < 36; i++) player.add(ItemStack.EMPTY);
        }

        Rig carry(String id, int count) {
            player.set(player.indexOf(ItemStack.EMPTY), stack(id, count));
            return this;
        }

        int carried(String id) {
            return player.stream().filter(item -> !item.isEmpty()
                    && BuiltInRegistries.ITEM.getKey(item.getItem()).toString().equals(id))
                    .mapToInt(ItemStack::getCount).sum();
        }

        FakeChest chest(WorldPosition at, boolean someoneElses) {
            FakeChest chest = new FakeChest(this, at, someoneElses);
            chests.put(at, chest);
            return chest;
        }

        DepositInput input(Target target, List<String> items, Integer count) {
            return new DepositInput(target, items, count, 32, Permissions.DEFAULT);
        }

        DepositTask task(DepositInput input) {
            SpotsContainers spots = new SpotsContainers() {
                @Override public List<ContainerChooser.Candidate> around(BlockPos center, int radius) {
                    return chests.values().stream().map(FakeChest::candidate).toList();
                }

                @Override public boolean scanComplete() {
                    return true;
                }

                @Override public Optional<ContainerChooser.Candidate> at(WorldPosition position) {
                    return Optional.ofNullable(chests.get(position)).map(FakeChest::candidate);
                }
            };
            DepositSeams.FindsPlaces places = new DepositSeams.FindsPlaces() {
                @Override public Optional<WorldPosition> seen(String seenId) { return Optional.empty(); }
                @Override public Optional<WorldPosition> landmark(String name) { return Optional.empty(); }
                @Override public BlockPos feet() { return BlockPos.ZERO; }
            };
            DepositServices services = new DepositServices(spots, (at, permissions) -> {
                opens++;
                return new FakeOpening(chests.get(WorldPosition.here(at.getX(), at.getY(), at.getZ())));
            }, places, null, itemId -> itemId.equals("minecraft:oak_log") ? Set.of("minecraft:logs") : Set.of(),
                    null);
            DepositTask task = new DepositTask(input, services, backpack());
            task.start(tick());
            return task;
        }

        // 背包视图按替身背包现读：建材按方块物品认，食物与工具按名字认。
        private BackpackView backpack() {
            return new BackpackView() {
                @Override public List<BackpackStack> stacks() {
                    List<BackpackStack> stacks = new ArrayList<>();
                    for (ItemStack item : player) {
                        if (item.isEmpty()) continue;
                        String id = BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
                        stacks.add(new BackpackStack(id, item.getCount(), item.getMaxStackSize(),
                                id.endsWith("_pickaxe"), id.equals("minecraft:bread"), false, id.equals(COBBLE)));
                    }
                    return stacks;
                }

                @Override public int usedSlots() { return stacks().size(); }
                @Override public int totalSlots() { return 36; }
            };
        }

        TaskResult run(DepositInput input) {
            return finish(task(input));
        }

        TaskResult finish(DepositTask task) {
            for (int i = 0; i < 600; i++) {
                if (task.tick(tick()) instanceof TickResult.Finished finished) return finished.result();
            }
            throw new AssertionError("任务六百刻都没结束：" + task.describe());
        }

        TickContext tick() {
            long now = ++tick;
            return new TickContext() {
                @Override public long gameTick() { return now; }
                @Override public PlayerContext player() { return null; }
            };
        }
    }

    /** 一只替身箱子：27 格，槽位号 36 起；快速移动与左右键按原版的规矩并堆、拿半堆、放一个。 */
    private static final class FakeChest implements OpenedMenu {
        private final Rig rig;
        private final WorldPosition at;
        private final boolean someoneElses;
        final List<ItemStack> slots = new ArrayList<>();
        ItemStack cursor = ItemStack.EMPTY;
        boolean locked;
        boolean open;
        boolean closed;
        /** 接下来有几下点击本刻发不出去（界面刚刷新还没画好），什么都不做、回报假。 */
        int notReadyClicks;
        /** 实际发出去的点击（含快速移动）次数。 */
        int sentClicks;

        FakeChest(Rig rig, WorldPosition at, boolean someoneElses) {
            this.rig = rig;
            this.at = at;
            this.someoneElses = someoneElses;
            for (int i = 0; i < 27; i++) slots.add(ItemStack.EMPTY);
        }

        ContainerChooser.Candidate candidate() {
            return new ContainerChooser.Candidate("箱子" + at.x(), "minecraft:chest", at, null, true, at.x(),
                    someoneElses, true, ContainerChooser.Lid.CLEAR, false);
        }

        void fill(String id) {
            for (int i = 0; i < slots.size(); i++) slots.set(i, stack(id, 64));
        }

        int total(String id) {
            return slots.stream().filter(item -> !item.isEmpty()
                    && BuiltInRegistries.ITEM.getKey(item.getItem()).toString().equals(id))
                    .mapToInt(ItemStack::getCount).sum();
        }

        @Override public Optional<MenuContent.Reading> reading() {
            if (!open) return Optional.empty();
            List<Integer> playerIds = new ArrayList<>();
            List<SlotSnapshot> playerSnapshots = new ArrayList<>();
            for (int i = 0; i < 36; i++) {
                playerIds.add(i);
                playerSnapshots.add(snapshot(rig.player.get(i)));
            }
            List<Integer> chestIds = new ArrayList<>();
            List<SlotSnapshot> chestSnapshots = new ArrayList<>();
            for (int i = 0; i < slots.size(); i++) {
                chestIds.add(36 + i);
                chestSnapshots.add(snapshot(slots.get(i)));
            }
            return Optional.of(new MenuContent.Reading(CHANNEL, SLOTS, chestIds, playerIds, chestSnapshots,
                    playerSnapshots));
        }

        private static SlotSnapshot snapshot(ItemStack item) {
            return item.isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(item.copy());
        }

        @Override public boolean busy() { return false; }
        @Override public boolean cursorEmpty() { return cursor.isEmpty(); }

        // 本刻发不发得出去：还有"没准备好"的次数就先消耗一次，回报假。
        private boolean sendable() {
            if (notReadyClicks > 0) {
                notReadyClicks--;
                return false;
            }
            sentClicks++;
            return true;
        }

        // 快速移动：先并进同种的堆，再占空格，放不下的留在原格。
        @Override public boolean quickMove(int slotId) {
            if (!sendable()) return false;
            ItemStack moving = rig.player.get(slotId);
            for (ItemStack slot : slots) {
                if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, moving)) {
                    int room = slot.getMaxStackSize() - slot.getCount();
                    int moved = Math.min(room, moving.getCount());
                    slot.grow(moved);
                    moving.shrink(moved);
                }
            }
            for (int i = 0; i < slots.size() && !moving.isEmpty(); i++) {
                if (slots.get(i).isEmpty()) {
                    slots.set(i, moving.copy());
                    moving.setCount(0);
                }
            }
            if (moving.isEmpty()) rig.player.set(slotId, ItemStack.EMPTY);
            return true;
        }

        // 左右键：空光标左键拿整份、右键拿半堆；拿着东西左键放整份、右键放一个。
        @Override public boolean click(int slotId, int button) {
            if (!sendable()) return false;
            List<ItemStack> side = slotId < 36 ? rig.player : slots;
            int index = slotId < 36 ? slotId : slotId - 36;
            ItemStack slot = side.get(index);
            if (cursor.isEmpty()) {
                int take = button == 0 ? slot.getCount() : (slot.getCount() + 1) / 2;
                cursor = slot.split(take);
                if (slot.isEmpty()) side.set(index, ItemStack.EMPTY);
                return true;
            }
            int give = button == 0 ? cursor.getCount() : 1;
            if (slot.isEmpty()) {
                side.set(index, cursor.split(give));
            } else if (ItemStack.isSameItemSameComponents(slot, cursor)) {
                int moved = Math.min(give, slot.getMaxStackSize() - slot.getCount());
                slot.grow(moved);
                cursor.shrink(moved);
            }
            if (cursor.isEmpty()) cursor = ItemStack.EMPTY;
            return true;
        }

        @Override public void noteCursorTakenFrom(int slotId) {}

        @Override public Action closing() {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    open = false;
                    closed = true;
                    return ActionStatus.done();
                }

                @Override public String describe() { return "关上替身箱子"; }
            };
        }

        @Override public void abandon() {
            open = false;
            closed = true;
        }
    }

    /** 打开替身箱子：锁着的点不开；能开的走两刻后开着。 */
    private record FakeOpening(FakeChest chest, int[] ran) implements MenuOpening {
        FakeOpening(FakeChest chest) {
            this(chest, new int[1]);
        }

        @Override public Optional<OpenedMenu> opened() {
            return chest.open ? Optional.of(chest) : Optional.empty();
        }

        @Override public ActionStatus tick(TickContext context) {
            if (chest.locked) {
                return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME, "箱子上锁了", null));
            }
            if (++ran[0] < 2) return ActionStatus.progressed();
            chest.open = true;
            chest.closed = false;
            return ActionStatus.done();
        }

        @Override public String describe() { return "打开替身箱子"; }
    }

    private static final MenuChannel CHANNEL = new MenuChannel() {
        @Override public boolean stillOpen() { return true; }
        @Override public boolean cursorCarrying() { return false; }
        @Override public boolean click(int slot, int button) { return true; }
        @Override public void requestClose() {}
    };

    private static final MenuSlots SLOTS = new MenuSlots() {
        @Override public String menuTypeId() { return "minecraft:generic_9x3"; }
        @Override public int slotCount() { return 63; }
        @Override public boolean playerBacked(int slot) { return slot < 36; }
    };
}
