// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.mekanism;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import mekanism.api.RelativeSide;
import mekanism.common.block.attribute.Attribute;
import mekanism.common.block.attribute.AttributeSideConfig;
import mekanism.common.inventory.container.slot.ContainerSlotType;
import mekanism.common.inventory.container.slot.IVirtualSlot;
import mekanism.common.inventory.container.slot.InventoryContainerSlot;
import mekanism.common.item.ItemConfigurator;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.network.PacketUtils;
import mekanism.common.network.to_server.PacketModeChange;
import mekanism.common.tile.component.config.ConfigInfo;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.interfaces.ISideConfiguration;
import mekanism.common.tile.transmitter.TileEntityTransmitter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.compat.mekanism.MekanismReads;

/**
 * 读通用机械的读写端（客户端）：侧面配置从机器的配置组件读（Mekanism 随同步包下发，按相对朝向存，这里换成世界方向）；
 * 管道往哪几面连着管道、连着机器是同步给客户端画连接用的两个位图；配置器的模式在物品上。
 * 换模式发的是按换模式键时 Mekanism 自己发的那个包。只翻译，不判断。
 */
public final class MekanismClientReads implements MekanismReads {

    @Override public Optional<SideConfig> sideConfig(ClientLevel level, BlockPos at) {
        if (!level.isLoaded(at) || !(level.getBlockEntity(at) instanceof ISideConfiguration machine)) {
            return Optional.empty();
        }
        Direction facing = machine.getDirection();
        Map<String, Map<Direction, String>> sides = new LinkedHashMap<>();
        Map<String, List<String>> choices = new LinkedHashMap<>();
        for (TransmissionType type : machine.getConfig().getTransmissions()) {
            ConfigInfo info = machine.getConfig().getConfig(type);
            if (info == null) continue;
            Map<Direction, String> perSide = new EnumMap<>(Direction.class);
            for (Direction side : Direction.values()) {
                DataType data = info.getDataType(RelativeSide.fromDirections(facing, side));
                perSide.put(side, data == null ? "none" : data.getSerializedName());
            }
            String medium = type.name().toLowerCase(Locale.ROOT);
            sides.put(medium, perSide);
            List<String> options = new ArrayList<>();
            for (DataType data : info.getSupportedDataTypes()) options.add(data.getSerializedName());
            choices.put(medium, options);
        }
        return Optional.of(new SideConfig(facing, sides, choices));
    }

    @Override public boolean configurable(BlockState state) {
        return Attribute.has(state, AttributeSideConfig.class);
    }

    @Override public Optional<MekanismReads.Transmitter> transmitter(ClientLevel level, BlockPos at) {
        if (!level.isLoaded(at)) return Optional.empty();
        BlockEntity entity = level.getBlockEntity(at);
        if (!(entity instanceof TileEntityTransmitter tile)) return Optional.empty();
        var transmitter = tile.getTransmitter();
        Set<String> media = new LinkedHashSet<>();
        for (TransmissionType type : transmitter.getSupportedTransmissionTypes()) {
            media.add(type.name().toLowerCase(Locale.ROOT));
        }
        return Optional.of(new MekanismReads.Transmitter(media,
                sidesOf(transmitter.currentTransmitterConnections),
                sidesOf(transmitter.getAcceptorCache().currentAcceptorConnections)));
    }

    // Mekanism 用一个字节记六个方向：第几位是 1 就是那个方向连着。
    private static Set<Direction> sidesOf(byte bits) {
        Set<Direction> sides = EnumSet.noneOf(Direction.class);
        for (Direction side : Direction.values()) {
            if ((bits & (1 << side.ordinal())) != 0) sides.add(side);
        }
        return sides;
    }

    @Override public Optional<String> configuratorMode(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof ItemConfigurator configurator)) return Optional.empty();
        return Optional.of(configurator.getMode(stack).getSerializedName());
    }

    @Override public void nextConfiguratorMode() {
        PacketUtils.sendToServer(new PacketModeChange(EquipmentSlot.MAINHAND, false));
    }

    // 打开着的界面里逐格看：背后是 Mekanism 机器物品格、又不是虚拟格、不是只看不存的格，才算真机器格。
    @Override public Set<Integer> openMachineSlots() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return Set.of();
        AbstractContainerMenu menu = player.containerMenu;
        Set<Integer> found = new LinkedHashSet<>();
        for (int index = 0; index < menu.slots.size(); index++) {
            if (menu.slots.get(index) instanceof InventoryContainerSlot slot && !(slot instanceof IVirtualSlot)
                    && slot.getSlotType() != ContainerSlotType.IGNORED
                    && slot.getSlotType() != ContainerSlotType.VALIDITY) {
                found.add(index);
            }
        }
        return Set.copyOf(found);
    }
}
