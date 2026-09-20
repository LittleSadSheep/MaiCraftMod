// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.container;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.task.TaskState;
import java.util.Map;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;

/** 用原生箱子菜单验证实际落槽顺序和双边守恒；测试不伪造服务器网络确认。 */
public final class ContainerBatchReplanTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        fullStacksThenThirtyThree(); partialStackThenOne(); externalChangeDoesNotReplan(); nativeSlotCapacityAndCursorGuard();
        System.out.println("ContainerBatchReplanTest: 1121-item reverse quick moves, live remainder allocation, conservation and cursor guards passed");
    }
    private static void fullStacksThenThirtyThree() throws Exception {
        // 复现取 1121 块石砖：17 次整堆移动填满旧尾数槽，最后 33 块必须按当前空槽重新安排。
        try (var fixture = new Fixture(1121, 1234)) {
            var initial = (List<?>) field(SemanticContainerCompanionTask.class, "plan").get(fixture.task);
            int staleDestination = move(initial.getLast()).to();
            check(staleDestination == 50, "fixture must reproduce the old final destination 50");
            for (int index = 0; index < 17; index++) {
                var next = fixture.next(); check(next.to() == -1, "complete stacks retain one native quick-move gesture");
                fixture.apply(next); fixture.confirm();
            }
            check(fixture.menu.getSlot(staleDestination).getItem().getCount() == 64, "native reverse routing fills the old simulated remainder slot");
            var tail = fixture.next();
            check(tail.count() == 33 && tail.to() != staleDestination && fixture.menu.getSlot(tail.to()).getItem().isEmpty(),
                    "the settled native layout must produce a fresh destination for exactly 33 remaining items");
            fixture.apply(tail); fixture.confirm(); invoke(fixture.task, "transfer");
            var report = fixture.task.resultData();
            check(report.get("moved_count").equals(1121) && report.get("observed_final_main_count").equals(1121)
                    && report.get("observed_final_container_count").equals(113) && Boolean.TRUE.equals(report.get("goal_satisfied")),
                    "one original count goal must finish with exact equal-and-opposite aggregate deltas");
            check(fixture.menu.getCarried().isEmpty(), "the 31-item cursor remainder returns to its native source slot");
        }
    }
    private static void partialStackThenOne() throws Exception {
        try (var fixture = new Fixture(33, 96)) {
            fixture.stock.setItem(0, new ItemStack(Items.STONE_BRICKS, 32));
            fixture.stock.setItem(1, new ItemStack(Items.STONE_BRICKS, 64)); fixture.restartPlan(96);
            var whole = fixture.next(); fixture.apply(whole); fixture.confirm();
            var tail = fixture.next();
            check(tail.count() == 1 && fixture.menu.getSlot(tail.to()).getItem().getCount() == 32,
                    "the tail merges into the real quick-move result instead of inventing another 33-item request");
            fixture.apply(tail); fixture.confirm(); invoke(fixture.task, "transfer");
            check(fixture.task.resultData().get("moved_count").equals(33) && fixture.stock.countItem(Items.STONE_BRICKS) == 63,
                    "partial stacks preserve the original quantity and the native source remainder");
        }
    }
    private static void externalChangeDoesNotReplan() throws Exception {
        // 别人改过箱子后不能把新状态吞进重规划，继续执行尚未确认的旧取料请求。
        try (var fixture = new Fixture(128, 192)) {
            var first = fixture.next(); fixture.apply(first); fixture.confirm();
            fixture.stock.setItem(0, new ItemStack(Items.STONE_BRICKS, 1));
            invoke(fixture.task, "transfer");
            check("menu_changed_externally".equals(field(SemanticContainerCompanionTask.class, "failureCode").get(fixture.task)),
                    "replanning cannot absorb an unconfirmed or external inventory change");
            check(field(SemanticContainerCompanionTask.class, "activeRecord").get(fixture.task) == null,
                    "no new native transfer may be dispatched after the stable fingerprint diverges");
        }
    }
    private static void nativeSlotCapacityAndCursorGuard() throws Exception {
        try (var fixture = new Fixture(17, 64)) {
            fixture.menu.slots.set(33, new Slot(fixture.world.player.getInventory(), 15, 0, 0) {
                @Override public int getMaxStackSize(ItemStack stack) { return 16; }
            });
            var move = new ContainerTransferTaskRecord.Move(0, 33, 17);
            var record = new ContainerTransferTaskRecord("slot-capacity", 1000, fixture.menu.containerId, List.of(move), false);
            var lower = new ContainerTransferCompanionTask(fixture.world.player, record);
            Method begin = ContainerTransferCompanionTask.class.getDeclaredMethod("beginMove", ContainerTransferTaskRecord.Move.class); begin.setAccessible(true);
            check(begin.invoke(lower, move) == TaskState.FAILED && fixture.menu.getCarried().isEmpty(), "native per-slot limit must be checked before pickup");
            var allowed = new ContainerTransferTaskRecord.Move(0, 33, 5);
            lower = new ContainerTransferCompanionTask(fixture.world.player, new ContainerTransferTaskRecord("cursor-owner", 1000, fixture.menu.containerId, List.of(allowed), false));
            field(ContainerTransferCompanionTask.class, "menu").set(lower, fixture.menu);
            check(begin.invoke(lower, allowed) == TaskState.RUNNING, "an empty compatible destination accepts its exact initial plan");
            fixture.menu.setCarried(new ItemStack(Items.DIAMOND));
            Method pickup = ContainerTransferCompanionTask.class.getDeclaredMethod("submitPickup", LocalPlayerContext.class, ContainerTransferTaskRecord.Move.class); pickup.setAccessible(true);
            check(pickup.invoke(lower, ClientRuntime.requireContext(fixture.world.player), allowed) == TaskState.FAILED,
                    "an unexpected cursor arriving before pickup must not be treated as this task's rollback stack");
            lower.result(TaskState.FAILED);
            check(fixture.world.player.containerMenu == fixture.menu && fixture.menu.getCarried().is(Items.DIAMOND)
                            && fixture.stock.getItem(0).getCount() == 64,
                    "refusing a stale pickup preserves the foreign cursor, source stack and visible menu");
        }
    }
    private static final class Fixture implements AutoCloseable {
        final InteractionWorldTestHarness world = new InteractionWorldTestHarness();
        final SimpleContainer stock = new SimpleContainer(27);
        final ChestMenu menu;
        final SemanticContainerCompanionTask task;
        Fixture(int requested, int available) throws Exception {
            for (int slot = 9; slot < 15; slot++) world.player.getInventory().items.set(slot, new ItemStack(Items.COBBLESTONE));
            for (int slot = 0; available > 0; slot++) { int count = Math.min(64, available); stock.setItem(slot, new ItemStack(Items.STONE_BRICKS, count)); available -= count; }
            menu = ChestMenu.threeRows(45, world.player.getInventory(), stock); world.player.containerMenu = menu;
            Minecraft.getInstance().screen = new ContainerScreen(menu, world.player.getInventory(), Component.literal("Native barrel routing"));
            BlockPos target = new BlockPos(4, 1, 4); world.set(target, Blocks.BARREL.defaultBlockState());
            field(Level.class, "isClientSide").setBoolean(world.level, true);
            Object cache = field(world.level.getClass(), "chunks").get(world.level), chunk = field(cache.getClass(), "chunk").get(cache);
            field(chunk.getClass(), "level").set(chunk, world.level); field(ChunkAccess.class, "levelHeightAccessor").set(chunk, world.level);
            var barrel = new BarrelBlockEntity(target, Blocks.BARREL.defaultBlockState()); barrel.setLevel(world.level);
            field(chunk.getClass(), "blockEntities").set(chunk, new HashMap<>(Map.of(target, barrel)));
            field(chunk.getClass(), "pendingBlockEntities").set(chunk, new HashMap<>());
            var record = new SemanticContainerTaskRecord("batch", 1000, SemanticContainerTaskRecord.Operation.WITHDRAW,
                    List.of(ResourceLocation.parse("minecraft:stone_bricks")), null, requested, null, ResourceLocation.parse("minecraft:barrel"), null,
                    SemanticContainerTaskRecord.Selection.NEAREST, List.of(), 1);
            task = new SemanticContainerCompanionTask(world.player, record);
            Class<?> candidate = Class.forName(SemanticContainerCompanionTask.class.getName() + "$Candidate");
            var constructor = candidate.getDeclaredConstructors()[0]; constructor.setAccessible(true);
            field(SemanticContainerCompanionTask.class, "target").set(task, constructor.newInstance(target, ResourceLocation.parse("minecraft:barrel"), false));
            Method classify = SemanticContainerCompanionTask.class.getDeclaredMethod("classify", AbstractContainerMenu.class); classify.setAccessible(true);
            field(SemanticContainerCompanionTask.class, "view").set(task, classify.invoke(task, menu));
            field(SemanticContainerCompanionTask.class, "expectedContainerId").setInt(task, menu.containerId);
            field(SemanticContainerCompanionTask.class, "expectedMenuClass").set(task, menu.getClass());
            restartPlan(stock.countItem(Items.STONE_BRICKS));
        }
        void restartPlan(int count) throws Exception {
            Method fingerprint = SemanticContainerCompanionTask.class.getDeclaredMethod("fingerprint", AbstractContainerMenu.class); fingerprint.setAccessible(true);
            field(SemanticContainerCompanionTask.class, "stableFingerprint").set(task, fingerprint.invoke(null, menu));
            field(SemanticContainerCompanionTask.class, "initialContainerCount").setInt(task, count);
            field(SemanticContainerCompanionTask.class, "lastContainerCount").setInt(task, count);
            invoke(task, "plan");
        }
        ContainerTransferTaskRecord.Move next() throws Exception {
            invoke(task, "transfer");
            var record = (ContainerTransferTaskRecord) field(SemanticContainerCompanionTask.class, "activeRecord").get(task);
            check(record != null, "a valid settled menu must produce the next finite transfer"); return record.moves.getFirst();
        }
        void apply(ContainerTransferTaskRecord.Move move) {
            if (move.to() < 0) menu.quickMoveStack(world.player, move.from());
            else {
                var source = menu.getSlot(move.from()); menu.setCarried(source.safeTake(source.getItem().getCount(), 64, world.player));
                for (int count = 0; count < move.count(); count++) menu.setCarried(menu.getSlot(move.to()).safeInsert(menu.getCarried(), 1));
                if (!menu.getCarried().isEmpty()) menu.setCarried(source.safeInsert(menu.getCarried()));
            }
            check(menu.getCarried().isEmpty(), "native slot simulation must settle its cursor before receipt reconciliation");
        }
        void confirm() throws Exception {
            field(SemanticContainerCompanionTask.class, "activeChild").set(task, null);
            field(SemanticContainerCompanionTask.class, "activeRecord").set(task, null);
            field(SemanticContainerCompanionTask.class, "activePurpose").set(task, null);
            invoke(task, "verifyTransfer");
            check(field(SemanticContainerCompanionTask.class, "failureCode").get(task) == null, "confirmed moves must conserve both native inventory sides");
        }
        public void close() throws Exception { world.close(); }
    }
    private static ContainerTransferTaskRecord.Move move(Object planned) throws Exception {
        Method accessor = planned.getClass().getDeclaredMethod("move"); accessor.setAccessible(true); return (ContainerTransferTaskRecord.Move) accessor.invoke(planned);
    }
    private static Object invoke(Object target, String name) throws Exception { Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(target); }
    private static Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
