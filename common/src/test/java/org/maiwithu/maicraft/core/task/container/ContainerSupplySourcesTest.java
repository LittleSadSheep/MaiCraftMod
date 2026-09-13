// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.container;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.inventory.StockEvidence;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.task.TaskState;

public final class ContainerSupplySourcesTest {
    private static final ResourceLocation IRON = ResourceLocation.parse("minecraft:iron_ingot");
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        cacheNeverConfusesContainersOrWorlds();
        choosesOnlyLoadedOrdinarySourcesAndProtectsBothChestHalves();
        partialWithdrawalAndMenuOwnershipRemainExplicit();
    }
    private static void cacheNeverConfusesContainersOrWorlds() {
        var cache = new ContainerSupplySources.Cache(); Object owner = new Object(), level = new Object(), first = new Object(), second = new Object();
        BlockPos a = new BlockPos(2, 1, 2), b = a.east();
        var ids = Map.of(a, first, b, second);
        var stock = new StockEvidence.Snapshot(StockEvidence.Source.CONTAINER, Map.of(IRON, 7L), Set.of(), 20);
        cache.record(owner, level, ids, stock);
        check(cache.latest(owner, level, b, ids, 21).storedCount(IRON) == 7, "both halves refer to one observed inventory");
        check(cache.latest(owner, level, a, Map.of(a, first, b, new Object()), 21) == null, "replaced other chest half invalidates the hint");
        cache.record(owner, level, ids, stock);
        check(cache.latest(owner, level, a, ids, 1221) == null, "expired GUI contents are not current stock");
        cache.record(owner, level, ids, stock);
        check(cache.latest(owner, new Object(), a, ids, 21) == null, "world changes cannot reuse container identities");
        cache.record(owner, level, ids, stock);
        check(cache.latest(new Object(), level, a, ids, 21) == null, "player replacement invalidates container stock");
    }
    private static void choosesOnlyLoadedOrdinarySourcesAndProtectsBothChestHalves() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var entities = worldEntities(h); BlockPos near = new BlockPos(3, 1, 3), far = new BlockPos(6, 1, 3);
            addBarrel(h, entities, near); addBarrel(h, entities, far);
            BlockPos furnace = new BlockPos(2, 1, 4); h.set(furnace, Blocks.FURNACE.defaultBlockState());
            var furnaceEntity = new FurnaceBlockEntity(furnace, Blocks.FURNACE.defaultBlockState()); furnaceEntity.setLevel(h.level); entities.put(furnace, furnaceEntity);
            var candidates = ContainerSupplySources.candidates(h.player, h.player.blockPosition(), 16, List.of(IRON), Set.of(), List.of());
            check(candidates.size() == 2 && candidates.getFirst().position().equals(near), "ordinary storage search does not raid furnace process slots");
            check(ContainerSupplySources.candidates(h.player, h.player.blockPosition(), 16, List.of(IRON), Set.of(near), List.of()).getFirst().position().equals(far),
                    "visited empty containers cannot repeatedly win nearest selection");
            check(NavigationSafetyContext.withProtectedArea(List.of(near), List.of(),
                    () -> ContainerSupplySources.candidates(h.player, h.player.blockPosition(), 16, List.of(IRON), Set.of(), List.of())).stream()
                    .noneMatch(value -> value.position().equals(near)), "explicit protection blocks warehouse use");
            check(NavigationSafetyContext.withPreservedStructures(List.of(near), () -> ContainerSupplySources.allowed(h.player, near, List.of())),
                    "preserving a structure from mining does not revoke explicitly authorized GUI access");
            BlockPos left = new BlockPos(9, 1, 4); var state = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.LEFT);
            BlockPos right = left.relative(ChestBlock.getConnectedDirection(state));
            h.set(left, state); h.set(right, state.setValue(ChestBlock.TYPE, ChestType.RIGHT));
            var first = new ChestBlockEntity(left, h.level.getBlockState(left)); first.setLevel(h.level); entities.put(left, first);
            var second = new ChestBlockEntity(right, h.level.getBlockState(right)); second.setLevel(h.level); entities.put(right, second);
            check(ContainerSupplySources.footprint(h.level, left).size() == 2, "native double chest is one source footprint");
            check(!NavigationSafetyContext.withProtectedArea(List.of(right), List.of(), () -> ContainerSupplySources.allowed(h.player, left, List.of())),
                    "a protected other half prevents withdrawing through its unprotected neighbor");
            check(h.blockUses() == 0 && h.itemUses() == 0, "source selection and protection checks perform no native action");
        } finally { ContainerSupplySources.reset(); }
    }
    private static void partialWithdrawalAndMenuOwnershipRemainExplicit() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var record = SemanticContainerTaskRecord.withdrawAvailableAt("partial-stock", 1000, List.of(IRON), 10,
                    new BlockPos(3, 1, 3), ResourceLocation.parse("minecraft:barrel"), List.of());
            var task = new SemanticContainerCompanionTask(h.player, record);
            Class<?> direction = java.util.Arrays.stream(task.getClass().getDeclaredClasses()).filter(type -> type.getSimpleName().equals("Direction")).findFirst().orElseThrow();
            var amount = task.getClass().getDeclaredMethod("requestedAmount", int.class, int.class, direction); amount.setAccessible(true);
            Object withdraw = java.util.Arrays.stream(direction.getEnumConstants()).filter(value -> value.toString().equals("WITHDRAW")).findFirst().orElseThrow();
            check((int) amount.invoke(task, 5, 3, withdraw) == 3 && (int) amount.invoke(task, 5, 64, withdraw) == 5
                    && (int) amount.invoke(task, 10, 64, withdraw) == 0, "bounded withdrawal never exceeds stock or the remaining final-inventory need");
            field(task.getClass(), "movedCount").setInt(task, 3); field(task.getClass(), "plannedAmount").setInt(task, 3);
            field(task.getClass(), "lastPlayerCount").setInt(task, 8);
            var transfer = task.getClass().getDeclaredMethod("transfer"); transfer.setAccessible(true);
            check(transfer.invoke(task) == TaskState.RUNNING && !field(task.getClass(), "outcomeUncertain").getBoolean(task)
                    && !field(task.getClass(), "goalSatisfied").getBoolean(task), "a settled partial source is not an uncertain or falsely satisfied global goal");
            var owned = ChestMenu.threeRows(1, h.inventory); var foreign = ChestMenu.threeRows(2, h.inventory);
            field(task.getClass(), "ownedMenu").set(task, owned); field(task.getClass(), "openedMenu").setBoolean(task, true);
            check(task.mustSettleBeforeSatisfiedCancellation(), "visible owned GUI establishes the parent's cleanup barrier");
            h.player.containerMenu = foreign;
            var lost = task.getClass().getDeclaredMethod("menuLost", String.class); lost.setAccessible(true); lost.invoke(task, "changed by user");
            task.result(TaskState.FAILED);
            check(h.player.containerMenu == foreign && h.blockUses() == 0 && h.itemUses() == 0, "cleanup leaves a replacement menu untouched");
        }
    }
    public static Map<BlockPos, BlockEntity> worldEntities(InteractionWorldTestHarness h) throws Exception {
        field(Level.class, "isClientSide").setBoolean(h.level, true);
        Object source = field(h.level.getClass(), "chunks").get(h.level), chunk = field(source.getClass(), "chunk").get(source);
        field(chunk.getClass(), "level").set(chunk, h.level); field(ChunkAccess.class, "levelHeightAccessor").set(chunk, h.level);
        Map<BlockPos, BlockEntity> entities = new LinkedHashMap<>(); field(chunk.getClass(), "blockEntities").set(chunk, entities); return entities;
    }
    public static void addBarrel(InteractionWorldTestHarness h, Map<BlockPos, BlockEntity> entities, BlockPos position) {
        var state = Blocks.BARREL.defaultBlockState(); h.set(position, state); var barrel = new BarrelBlockEntity(position, state);
        barrel.setLevel(h.level); entities.put(position, barrel);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) try { var field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (NoSuchFieldException absent) { }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
