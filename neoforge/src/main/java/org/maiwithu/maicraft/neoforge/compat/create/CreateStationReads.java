// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.create;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.depot.DepotBehaviour;
import com.simibubi.create.content.logistics.stockTicker.CraftableBigItemStack;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

import org.maiwithu.maicraft.compat.create.CreateStations;

/**
 * 读机械动力看得见的东西的读写端：置物台台面那一叠是置物台行为的公开取值，加工多出的产物在同步包的 OutputBuffer 里；
 * 工作盆的输入、输出两份物品栏、传送带控制格上整条带的货物、库存请求器上次收到的现货摘要，都是 Create 同步给客户端画出来的。
 * 只翻译，不判断。
 */
public final class CreateStationReads implements CreateStations {

    /** 置物台加工多出来的产物最多放几格：和 Create 置物台行为里的产物栏一样大。 */
    private static final int DEPOT_OUTPUT_SLOTS = 8;

    @Override public Optional<List<ItemStack>> depotItems(ClientLevel level, BlockPos at) {
        if (!level.isLoaded(at)) return Optional.empty();
        DepotBehaviour depot = BlockEntityBehaviour.get(level, at, DepotBehaviour.TYPE);
        if (depot == null) return Optional.empty();
        List<ItemStack> items = new ArrayList<>();
        items.add(depot.getHeldItemStack().copy());
        BlockEntity entity = level.getBlockEntity(at);
        if (entity != null) {
            // 加工多出来的产物不在公开取值里，但同步给客户端的数据里有一份，和服务端那一份一致。
            CompoundTag synced = entity.getUpdateTag(level.registryAccess());
            ItemStackHandler outputs = new ItemStackHandler(DEPOT_OUTPUT_SLOTS);
            outputs.deserializeNBT(level.registryAccess(), synced.getCompound("OutputBuffer"));
            items.addAll(stacksOf(outputs));
        }
        return Optional.of(items);
    }

    @Override public Optional<List<ItemStack>> basinItems(ClientLevel level, BlockPos at) {
        if (!level.isLoaded(at) || !(level.getBlockEntity(at) instanceof BasinBlockEntity basin)) {
            return Optional.empty();
        }
        List<ItemStack> items = new ArrayList<>(stacksOf(basin.getInputInventory()));
        items.addAll(stacksOf(basin.getOutputInventory()));
        return Optional.of(items);
    }

    @Override public Optional<List<ItemStack>> beltItems(ClientLevel level, BlockPos at) {
        if (!level.isLoaded(at) || !(level.getBlockEntity(at) instanceof BeltBlockEntity belt)) {
            return Optional.empty();
        }
        BeltBlockEntity controller = belt.getControllerBE();
        if (controller == null) return Optional.of(List.of());
        List<ItemStack> items = new ArrayList<>();
        for (TransportedItemStack moving : controller.getInventory().getTransportedItems()) {
            items.add(moving.stack.copy());
        }
        return Optional.of(items);
    }

    @Override public Optional<StockSnapshot> stock(ClientLevel level, BlockPos at) {
        if (!level.isLoaded(at) || !(level.getBlockEntity(at) instanceof StockTickerBlockEntity ticker)) {
            return Optional.empty();
        }
        List<StockEntry> entries = new ArrayList<>();
        for (BigItemStack stack : ticker.getLastClientsideStockSnapshotAsSummary().getStacks()) {
            // 界面里"能合成多少"的预览条目不是现货。
            if (stack instanceof CraftableBigItemStack || stack.stack.isEmpty()) continue;
            entries.add(new StockEntry(BuiltInRegistries.ITEM.getKey(stack.stack.getItem()).toString(), stack.count));
        }
        return Optional.of(new StockSnapshot(entries, ticker.getTicksSinceLastUpdate()));
    }

    private static List<ItemStack> stacksOf(IItemHandler handler) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty()) stacks.add(stack.copy());
        }
        return stacks;
    }
}
