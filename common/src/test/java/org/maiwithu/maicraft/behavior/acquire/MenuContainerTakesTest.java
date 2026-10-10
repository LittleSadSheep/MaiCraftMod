// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.acquire.spi.ReportsUnconfirmed;
import org.maiwithu.maicraft.behavior.inventory.KnownContainer;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 开箱取货按量拿：整堆够用整堆拿，尾数按件拿够就停；点不出去时下一刻再点，不当成背包放不下。 */
class MenuContainerTakesTest {

    @TempDir Path temp;

    @BeforeAll
    static void 引导物品注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 替身箱子：容器侧 27 格（槽位号 0–26），角色侧 36 格（27–62）；可以让前几下快速移动"本刻发不出去"。 */
    private static final class FakeBox implements OpenedMenu {
        final List<ItemStack> box = new ArrayList<>();
        final List<ItemStack> player = new ArrayList<>();
        /** 光标上拿着的东西；普通点击拿起、放下的都在这里周转。 */
        private ItemStack cursor = ItemStack.EMPTY;
        int notReadyMoves;
        /** 点得出去但东西原地不动的那种：真实游戏里背包塞满、服务端收下点击却搬不动就是这样。 */
        int jamMoves;
        int sentMoves;
        boolean closed;

        FakeBox() {
            for (int i = 0; i < 27; i++) box.add(ItemStack.EMPTY);
            for (int i = 0; i < 36; i++) player.add(ItemStack.EMPTY);
        }

        @Override public Optional<MenuContent.Reading> reading() {
            if (closed) return Optional.empty();
            List<Integer> boxIds = new ArrayList<>();
            List<SlotSnapshot> boxSnapshots = new ArrayList<>();
            for (int i = 0; i < box.size(); i++) {
                boxIds.add(i);
                boxSnapshots.add(snapshot(box.get(i)));
            }
            List<Integer> playerIds = new ArrayList<>();
            List<SlotSnapshot> playerSnapshots = new ArrayList<>();
            for (int i = 0; i < player.size(); i++) {
                playerIds.add(27 + i);
                playerSnapshots.add(snapshot(player.get(i)));
            }
            return Optional.of(new MenuContent.Reading(CHANNEL, SLOTS, boxIds, playerIds, boxSnapshots,
                    playerSnapshots));
        }

        private static SlotSnapshot snapshot(ItemStack item) {
            return item.isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(item);
        }

        @Override public boolean busy() { return false; }
        @Override public boolean cursorEmpty() { return cursor.isEmpty(); }

        // 快速移动：本刻发不出去就什么都不做；点得出去但塞不下时收下点击、东西不动；
        // 发得出去才把箱子里这一格整堆挪进角色背包的第一个空格。
        @Override public boolean quickMove(int slotId) {
            if (notReadyMoves > 0) {
                notReadyMoves--;
                return false;
            }
            if (jamMoves > 0) {
                jamMoves--;
                return true;
            }
            sentMoves++;
            ItemStack moving = box.get(slotId);
            for (int i = 0; i < player.size(); i++) {
                if (player.get(i).isEmpty()) {
                    player.set(i, moving.copy());
                    box.set(slotId, ItemStack.EMPTY);
                    break;
                }
            }
            return true;
        }

        // 普通点击按原版规矩结算：空光标左键整份拿起、右键拿半堆；拿着东西左键整份放进（同种并堆，
        // 别的交换）、右键放一个，放不进就不动。
        @Override public boolean click(int slotId, int button) {
            List<ItemStack> side = slotId < 27 ? box : player;
            int index = slotId < 27 ? slotId : slotId - 27;
            ItemStack slot = side.get(index);
            if (cursor.isEmpty()) {
                if (slot.isEmpty()) return true;
                if (button == 0) {
                    cursor = slot;
                    side.set(index, ItemStack.EMPTY);
                } else {
                    int taken = (slot.getCount() + 1) / 2;
                    cursor = slot.copyWithCount(taken);
                    side.set(index, slot.copyWithCount(slot.getCount() - taken));
                }
            } else if (button == 0) {
                if (slot.isEmpty()) {
                    side.set(index, cursor);
                    cursor = ItemStack.EMPTY;
                } else if (ItemStack.isSameItemSameComponents(slot, cursor)) {
                    int fitting = Math.min(cursor.getCount(), slot.getMaxStackSize() - slot.getCount());
                    slot.grow(fitting);
                    cursor.shrink(fitting);
                } else {
                    side.set(index, cursor);
                    cursor = slot;
                }
            } else if (slot.isEmpty() || ItemStack.isSameItemSameComponents(slot, cursor)
                    && slot.getCount() < slot.getMaxStackSize()) {
                if (slot.isEmpty()) {
                    side.set(index, cursor.copyWithCount(1));
                } else {
                    slot.grow(1);
                }
                cursor.shrink(1);
            }
            if (cursor.isEmpty()) cursor = ItemStack.EMPTY;
            return true;
        }

        @Override public void noteCursorTakenFrom(int slotId) {}

        @Override public Action closing() {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    closed = true;
                    return ActionStatus.done();
                }

                @Override public String describe() { return "关上替身箱子"; }
            };
        }

        @Override public void abandon() {
            closed = true;
        }

        int carried(ItemStack kind) {
            return player.stream().filter(item -> ItemStack.isSameItem(item, kind)).mapToInt(ItemStack::getCount).sum();
        }
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
        @Override public boolean playerBacked(int slot) { return slot >= 27; }
    };

    /** 打开替身箱子：一推进就开好。 */
    private record OpenAtOnce(FakeBox box) implements MenuOpening {
        @Override public Optional<OpenedMenu> opened() { return Optional.of(box); }
        @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
        @Override public String describe() { return "打开替身箱子"; }
    }

    /** 只有刻号的推进现场：开箱取货在替身里碰不到角色对象。 */
    private static final class Ticks implements TickContext {
        private long tick = 1000;

        @Override public long gameTick() { return tick; }

        @Override public PlayerContext player() {
            throw new IllegalStateException("替身开箱不应碰到角色对象");
        }

        void advance() {
            tick++;
        }
    }

    @Test
    void 界面刚刷新那一刻点不出去_下一刻再点_照常取到不提前关箱() {
        FakeBox box = new FakeBox();
        box.box.set(4, new ItemStack(Items.IRON_INGOT, 5));
        box.box.set(9, new ItemStack(Items.IRON_INGOT, 3));
        // 前三下都撞上"本刻发不出去"，比搬运结清后等同步的宽限还多：照旧的做法会当成背包放不下关箱。
        box.notReadyMoves = 3;
        Action take = takes(box, 8);
        ActionStatus status = runToFinish(take);

        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(8, box.carried(new ItemStack(Items.IRON_INGOT)), "两格铁锭都取到了");
        assertEquals(2, box.sentMoves, "发不出去的那几下不算点过，真正发出去的只有两下");
        assertEquals(0, box.notReadyMoves);
        assertTrue(box.closed, "取完关上箱子");
    }

    @Test
    void 箱子里有六十四个只拿要的五个_背包里只多五个() {
        FakeBox box = new FakeBox();
        box.box.set(4, new ItemStack(Items.IRON_INGOT, 64));
        Action take = takes(box, 5);
        ActionStatus status = runToFinish(take);

        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(5, box.carried(new ItemStack(Items.IRON_INGOT)), "只多拿要的 5 个");
        assertEquals(59, box.box.get(4).getCount(), "箱子里剩下的还是 59 个");
        assertTrue(box.closed, "拿够就关上箱子");
        assertTrue(((ReportsUnconfirmed) take).unconfirmedFacts().isEmpty(), "点一下成一下，没有没能确认的");
    }

    @Test
    void 拿够就停_后面的格子不再碰() {
        FakeBox box = new FakeBox();
        box.box.set(4, new ItemStack(Items.IRON_INGOT, 64));
        box.box.set(9, new ItemStack(Items.IRON_INGOT, 30));
        ActionStatus status = runToFinish(takes(box, 5));

        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(5, box.carried(new ItemStack(Items.IRON_INGOT)));
        assertEquals(30, box.box.get(9).getCount(), "够了的量不再去动后面的格子");
    }

    @Test
    void 多格凑量_前面的格子整堆拿_最后一格拿尾数() {
        FakeBox box = new FakeBox();
        box.box.set(2, new ItemStack(Items.IRON_INGOT, 3));
        box.box.set(9, new ItemStack(Items.IRON_INGOT, 4));
        ActionStatus status = runToFinish(takes(box, 5));

        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(5, box.carried(new ItemStack(Items.IRON_INGOT)), "3 个整堆加 2 个尾数凑够 5 个");
        assertTrue(box.box.get(2).isEmpty(), "3 个那格整堆拿走");
        assertEquals(2, box.box.get(9).getCount(), "4 个那格只拿走要的 2 个");
    }

    @Test
    void 整堆刚好够用_一笔快速移动整堆拿走() {
        FakeBox box = new FakeBox();
        box.box.set(4, new ItemStack(Items.IRON_INGOT, 64));
        ActionStatus status = runToFinish(takes(box, 64));

        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(64, box.carried(new ItemStack(Items.IRON_INGOT)));
        assertTrue(box.box.get(4).isEmpty());
        assertEquals(1, box.sentMoves, "刚好够用整堆走快速移动，不拆堆");
    }

    @Test
    void 箱里的不够要的_有多少拿多少然后照常收场() {
        FakeBox box = new FakeBox();
        box.box.set(4, new ItemStack(Items.IRON_INGOT, 64));
        box.box.set(9, new ItemStack(Items.IRON_INGOT, 30));
        ActionStatus status = runToFinish(takes(box, 100));

        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(94, box.carried(new ItemStack(Items.IRON_INGOT)), "只有 94 个就全拿走，差多少引擎清点后再说");
        assertTrue(box.closed);
    }

    @Test
    void 背包放不下尾数时不硬点_照常收场交回清点() {
        FakeBox box = new FakeBox();
        box.box.set(4, new ItemStack(Items.IRON_INGOT, 64));
        for (int i = 0; i < 36; i++) box.player.set(i, new ItemStack(Items.GOLD_BLOCK, 64));
        Action take = takes(box, 5);
        ActionStatus status = runToFinish(take);

        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(0, box.carried(new ItemStack(Items.IRON_INGOT)), "拆不出 5 个放的位置就一个不拿");
        assertEquals(64, box.box.get(4).getCount(), "箱子原封不动，不硬点整堆把整组搬走");
        assertEquals(0, box.sentMoves);
        assertTrue(box.closed);
        assertTrue(((ReportsUnconfirmed) take).unconfirmedFacts().isEmpty(), "一下都没点出去，谈不上没能确认");
    }

    @Test
    void 点出去了容器一直没动_照常收场_没能确认的那笔交回() {
        // 背包塞满时服务端照样收下点击、东西却搬不动：点出去是事实，结果没能确认要如实交回，
        // 不与"没点过"混在一起；箱子原封不动，拿走多少仍由引擎清点。
        FakeBox box = new FakeBox();
        box.box.set(4, new ItemStack(Items.IRON_INGOT, 64));
        for (int i = 0; i < 36; i++) box.player.set(i, new ItemStack(Items.GOLD_BLOCK, 64));
        box.jamMoves = 3;
        Action take = takes(box, 64);
        ActionStatus status = runToFinish(take);

        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(64, box.box.get(4).getCount(), "箱子原封不动");
        assertTrue(box.closed, "照常收场关上箱子");
        List<String> facts = ((ReportsUnconfirmed) take).unconfirmedFacts();
        assertEquals(1, facts.size(), "点出去没等到结果的只有这一笔");
        assertTrue(facts.get(0).contains("搬 64 件"), "交回的事实写明要搬几件");
        assertTrue(facts.get(0).contains("没能确认"));
    }

    @Test
    void 搬到一半界面被关_点出去的那几下交回没能确认() {
        FakeBox box = new FakeBox();
        box.box.set(4, new ItemStack(Items.IRON_INGOT, 64));
        box.jamMoves = Integer.MAX_VALUE;
        Action take = takes(box, 64);
        Ticks ticks = new Ticks();
        take.tick(ticks);
        ticks.advance();
        take.tick(ticks);
        ticks.advance();
        // 第二刻那一下已经点出去了，随后界面被合上（被打断或服务端关的）。
        box.closed = true;
        ActionStatus status = runToFinish(take, ticks);

        assertInstanceOf(ActionStatus.Done.class, status);
        List<String> facts = ((ReportsUnconfirmed) take).unconfirmedFacts();
        assertEquals(1, facts.size());
        assertTrue(facts.get(0).contains("点了 1 下"), "点出去的一下如实交代");
        assertTrue(facts.get(0).contains("界面关上"));
        assertTrue(facts.get(0).contains("没能等到结算结果"));
    }

    /** 开好一只替身箱子、要 iron_ingot 这么几件的开箱取货动作。 */
    private Action takes(FakeBox box, int count) {
        WorldMemory memory = new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "world-1");
        MenuContainerTakes takes = new MenuContainerTakes(itemId -> Set.of(), memory, () -> null,
                (at, permissions) -> new OpenAtOnce(box));
        return takes.take(new KnownContainer("家门口的箱子", 10, 64, 5),
                new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), count, "工具准备"),
                Permissions.DEFAULT).orElseThrow();
    }

    /** 推进到动作结束为止：给足刻数，开箱取货几十刻内该收场。 */
    private static ActionStatus runToFinish(Action take) {
        return runToFinish(take, new Ticks());
    }

    /** 从给了一半的刻度接着推进到动作结束。 */
    private static ActionStatus runToFinish(Action take, Ticks ticks) {
        ActionStatus status = ActionStatus.running();
        for (int i = 0; i < 400 && status instanceof ActionStatus.Running; i++) {
            status = take.tick(ticks);
            ticks.advance();
        }
        return status;
    }
}
