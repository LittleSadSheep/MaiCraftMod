// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.create;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.content.decoration.placard.PlacardBlockEntity;
import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;
import com.simibubi.create.content.kinetics.crafter.CrafterHelper;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlock;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.depot.DepotBehaviour;
import com.simibubi.create.content.logistics.stockTicker.CraftableBigItemStack;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import com.simibubi.create.content.redstone.link.LinkBehaviour;
import com.simibubi.create.content.redstone.link.RedstoneLinkBlock;
import com.simibubi.create.content.redstone.link.RedstoneLinkFrequencySlot;
import com.simibubi.create.content.redstone.nixieTube.NixieTubeBlockEntity;
import com.simibubi.create.content.trains.display.FlapDisplayBlockEntity;
import com.simibubi.create.content.trains.display.FlapDisplayLayout;
import com.simibubi.create.content.trains.display.FlapDisplaySection;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.edgeInteraction.EdgeInteractionBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.edgeInteraction.EdgeInteractionHandler;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

import org.maiwithu.maicraft.compat.create.CreateStations;
import org.maiwithu.maicraft.compat.create.CreateValues;
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

    // 流体：储罐读整座罐的控制格，注液器、分液池读它的流体格，工作盆读进、出两组流体格。
    @Override public List<FluidReading> fluids(ClientLevel level, BlockPos at) {
        if (!level.isLoaded(at)) return List.of();
        List<FluidReading> found = new ArrayList<>();
        if (level.getBlockEntity(at) instanceof FluidTankBlockEntity tank) {
            FluidTankBlockEntity controller = tank.getControllerBE();
            if (controller != null) {
                FluidTank inventory = controller.getTankInventory();
                addFluid(found, inventory.getFluid(), inventory.getCapacity());
            }
            return found;
        }
        SmartFluidTankBehaviour single = BlockEntityBehaviour.get(level, at, SmartFluidTankBehaviour.TYPE);
        if (single != null) {
            addFluid(found, single.getPrimaryHandler().getFluid(), single.getPrimaryHandler().getCapacity());
        }
        for (var type : List.of(SmartFluidTankBehaviour.INPUT, SmartFluidTankBehaviour.OUTPUT)) {
            SmartFluidTankBehaviour tanks = BlockEntityBehaviour.get(level, at, type);
            if (tanks == null) continue;
            for (SmartFluidTankBehaviour.TankSegment segment : tanks.getTanks()) {
                addFluid(found, segment.getRenderedFluid(), 0);
            }
        }
        return found;
    }

    private static void addFluid(List<FluidReading> found, FluidStack fluid, int capacity) {
        if (fluid.isEmpty()) return;
        found.add(new FluidReading(BuiltInRegistries.FLUID.getKey(fluid.getFluid()).toString(), fluid.getAmount(), capacity));
    }

    // 显示面：辉光管的两行字、翻牌显示板每一行的各段字连起来、告示框里放着的东西。
    @Override public Optional<String> shownText(ClientLevel level, BlockPos at) {
        if (!level.isLoaded(at)) return Optional.empty();
        BlockEntity entity = level.getBlockEntity(at);
        if (entity instanceof NixieTubeBlockEntity nixie) return Optional.of(nixie.getFullText().getString());
        if (entity instanceof FlapDisplayBlockEntity flaps) {
            FlapDisplayBlockEntity controller = flaps.getController();
            if (controller == null) return Optional.of("");
            List<String> lines = new ArrayList<>();
            for (FlapDisplayLayout line : controller.getLines()) {
                StringBuilder text = new StringBuilder();
                for (FlapDisplaySection section : line.getSections()) text.append(section.getText().getString());
                lines.add(text.toString().strip());
            }
            return Optional.of(String.join(" / ", lines));
        }
        if (entity instanceof PlacardBlockEntity placard) {
            ItemStack held = placard.getHeldItem();
            return Optional.of(held.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
        }
        return Optional.empty();
    }

    // 无线红石：两个频率格放的东西、能点在哪（和 Create 判点中用的同一个框），收发看方块状态。
    @Override public Optional<LinkReading> link(ClientLevel level, BlockPos at) {
        if (!level.isLoaded(at)) return Optional.empty();
        LinkBehaviour link = BlockEntityBehaviour.get(level, at, LinkBehaviour.TYPE);
        if (link == null) return Optional.empty();
        BlockState state = level.getBlockState(at);
        List<ItemStack> frequencies = List.of(link.getNetworkKey().getFirst().getStack().copy(),
                link.getNetworkKey().getSecond().getStack().copy());
        List<CreateValues.Spot> spots = new ArrayList<>();
        for (boolean first : List.of(true, false)) {
            RedstoneLinkFrequencySlot slot = new RedstoneLinkFrequencySlot(first);
            Vec3 local = slot.getLocalOffset(level, at, state);
            if (local == null) continue;
            Direction face = state.hasProperty(BlockStateProperties.FACING)
                    ? state.getValue(BlockStateProperties.FACING) : Direction.UP;
            spots.add(new CreateValues.Spot(face, Vec3.atLowerCornerOf(at).add(local), slot.getScale() / 3.5));
        }
        boolean receiver = state.hasProperty(RedstoneLinkBlock.RECEIVER) && state.getValue(RedstoneLinkBlock.RECEIVER);
        boolean powered = state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED);
        return Optional.of(new LinkReading(frequencies, spots, receiver, powered));
    }

    // 机械合成器：格里的东西画在正面上，箭头与朝向在方块状态里。
    @Override public Optional<CrafterReading> crafter(ClientLevel level, BlockPos at) {
        if (!level.isLoaded(at)) return Optional.empty();
        MechanicalCrafterBlockEntity crafter = CrafterHelper.getCrafter(level, at);
        if (crafter == null) return Optional.empty();
        BlockState state = level.getBlockState(at);
        return Optional.of(new CrafterReading(crafter.getInventory().getItem(0).copy(),
                state.getValue(MechanicalCrafterBlock.POINTING).getSerializedName(),
                state.getValue(MechanicalCrafterBlock.HORIZONTAL_FACING)));
    }

    @Override public boolean craftersConnected(ClientLevel level, BlockPos first, BlockPos second) {
        return level.isLoaded(first) && level.isLoaded(second) && CrafterHelper.areCraftersConnected(level, first, second);
    }

    // 扳手点哪几面能连 toward 那一边：Create 自己算这一面能连的边，含 toward 的面才算。
    @Override public List<Direction> crafterLinkFaces(ClientLevel level, BlockPos at, Direction toward) {
        if (!level.isLoaded(at)) return List.of();
        EdgeInteractionBehaviour edges = BlockEntityBehaviour.get(level, at, EdgeInteractionBehaviour.TYPE);
        if (edges == null) return List.of();
        List<Direction> faces = new ArrayList<>();
        for (Direction face : Direction.values()) {
            if (face.getAxis() == toward.getAxis()) continue;
            if (EdgeInteractionHandler.getConnectiveSides(level, at, face, edges).contains(toward)) faces.add(face);
        }
        return faces;
    }

    // 装置在不在动：轴承问它自己，活塞与滑轮看公开字段 running。
    @Override public Optional<Boolean> moving(ClientLevel level, BlockPos at) {
        if (!level.isLoaded(at)) return Optional.empty();
        BlockEntity entity = level.getBlockEntity(at);
        if (entity instanceof MechanicalBearingBlockEntity bearing) return Optional.of(bearing.isRunning());
        if (entity instanceof ClockworkBearingBlockEntity clock) return Optional.of(clock.isRunning());
        if (entity instanceof LinearActuatorBlockEntity actuator) return Optional.of(actuator.running);
        return Optional.empty();
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

    // 分液池上的那一件：公开取值，倒着的与倒空了还留着的都是它。
    @Override public Optional<List<ItemStack>> drainItems(ClientLevel level, BlockPos at) {
        if (!level.isLoaded(at) || !(level.getBlockEntity(at) instanceof ItemDrainBlockEntity drain)) {
            return Optional.empty();
        }
        ItemStack held = drain.getHeldItemStack();
        return Optional.of(held.isEmpty() ? List.of() : List.of(held.copy()));
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
