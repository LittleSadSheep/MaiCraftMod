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

/** 开箱取货：界面刚刷新那一刻点不出去时下一刻再点，不把没点出去当成背包放不下而提前关箱。 */
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
        int notReadyMoves;
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
        @Override public boolean cursorEmpty() { return true; }

        // 快速移动：本刻发不出去就什么都不做；发出去了把箱子里这一格整堆挪进角色背包的第一个空格。
        @Override public boolean quickMove(int slotId) {
            if (notReadyMoves > 0) {
                notReadyMoves--;
                return false;
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

        @Override public boolean click(int slotId, int button) { return true; }
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
        WorldMemory memory = new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "world-1");
        MenuContainerTakes takes = new MenuContainerTakes(itemId -> Set.of(), memory, () -> null,
                (at, permissions) -> new OpenAtOnce(box));
        ItemRequest request = new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 8, "工具准备");
        Action take = takes.take(new KnownContainer("家门口的箱子", 10, 64, 5), request, Permissions.DEFAULT)
                .orElseThrow();

        Ticks ticks = new Ticks();
        ActionStatus status = ActionStatus.running();
        for (int i = 0; i < 200 && !(status instanceof ActionStatus.Done) && !(status instanceof ActionStatus.Failed); i++) {
            status = take.tick(ticks);
            ticks.advance();
        }
        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(8, box.carried(new ItemStack(Items.IRON_INGOT)), "两格铁锭都取到了");
        assertEquals(2, box.sentMoves, "发不出去的那几下不算点过，真正发出去的只有两下");
        assertEquals(0, box.notReadyMoves);
        assertTrue(box.closed, "取完关上箱子");
    }
}
