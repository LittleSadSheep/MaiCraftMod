// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.create;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 读机械动力看得见的东西的读写端：置物台台面那一叠是置物台行为的公开取值，加工多出的产物在同步包的 OutputBuffer 里；
 * 工作盆的输入、输出两份物品栏、传送带控制格上整条带的货物、库存请求器上次收到的现货摘要，都是 Create 同步给客户端画出来的。
 * 只翻译，不判断。
 *
 * <p>机械手手里拿着的东西：Create 同步给客户端画在手上，但只存在非公开字段 DeployerBlockEntity.heldItem 里，
 * 客户端的更新包里没有（客户端没有假玩家）。所有者 2026-10-11 批准的例外，只在这里反射读这一个字段；
 * 字段在创建读写端时取一次，取不到（Create 改了名）就给空物品，模式照读。字段名登记在机器联动模组事实清单里。
 */
public final class CreateStationReads implements CreateStations {

    private static final Logger LOG = LoggerFactory.getLogger(CreateStationReads.class);

    private final Field deployerHeld = heldField();

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

    // 机械手：模式在同步给客户端的数据里（Mode），手里那件反射读非公开字段；读不到就当空手报，不猜。
    @Override public Optional<DeployerReading> deployer(ClientLevel level, BlockPos at) {
        if (!level.isLoaded(at) || !(level.getBlockEntity(at) instanceof DeployerBlockEntity deployer)) {
            return Optional.empty();
        }
        String mode = deployer.getUpdateTag(level.registryAccess()).getString("Mode").toLowerCase(Locale.ROOT);
        ItemStack held = ItemStack.EMPTY;
        if (deployerHeld != null) {
            try {
                if (deployerHeld.get(deployer) instanceof ItemStack stack) held = stack.copy();
            } catch (IllegalAccessException | RuntimeException failure) {
                LOG.debug("读机械手手里的东西出错，这一次当空手报", failure);
            }
        }
        return Optional.of(new DeployerReading(held, mode));
    }

    // 取机械手手里那件的非公开字段并打开访问；取不到（改了名、打不开）记一行日志，手里那件读作空。
    private static Field heldField() {
        try {
            Field field = DeployerBlockEntity.class.getDeclaredField("heldItem");
            if (!field.trySetAccessible()) {
                LOG.warn("机械动力的字段 DeployerBlockEntity.heldItem 打不开，机械手手里的东西读不到");
                return null;
            }
            return field;
        } catch (NoSuchFieldException | RuntimeException missing) {
            LOG.warn("机械动力的字段 DeployerBlockEntity.heldItem 找不到（装的版本和实测过的不一样），机械手手里的东西读不到");
            return null;
        }
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
