// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.ae2;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import appeng.api.implementations.IPowerChannelState;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.parts.IPartItem;
import appeng.block.networking.ControllerBlock;
import appeng.parts.networking.CablePart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;

import org.maiwithu.maicraft.compat.ae2.Ae2Grid;

/**
 * 读 ME 网络设备的读写端（客户端）：线缆格是装部件的方块实体，中间的线缆往哪几面连着、各面的部件亮没亮，
 * AE2 都同步给客户端画出来；设备方块的有没有电读它自己报的电与频道状态，或方块状态上的 powered，
 * 控制器的状态在方块状态上。只翻译，不判断。
 */
public final class AppliedEnergisticsGrid implements Ae2Grid {

    @Override public Optional<GridCell> cell(ClientLevel level, BlockPos at) {
        if (!level.isLoaded(at)) return Optional.empty();
        BlockEntity entity = level.getBlockEntity(at);
        if (entity instanceof IPartHost host) return Optional.of(cableBus(host));
        if (!(entity instanceof IInWorldGridNodeHost)) return Optional.empty();
        BlockState state = level.getBlockState(at);
        Optional<Boolean> powered = entity instanceof IPowerChannelState channel ? Optional.of(channel.isPowered())
                : poweredProperty(state);
        Optional<Boolean> active = entity instanceof IPowerChannelState channel ? Optional.of(channel.isActive())
                : Optional.empty();
        Optional<String> controller = state.getBlock() instanceof ControllerBlock
                ? Optional.of(state.getValue(ControllerBlock.CONTROLLER_STATE).getSerializedName()) : Optional.empty();
        return Optional.of(new GridCell(false, Optional.empty(), Set.of(), Map.of(), powered, active, controller));
    }

    // 线缆格：中间线缆的物品与连着的面，各面部件的物品与电、频道状态。
    private static GridCell cableBus(IPartHost host) {
        IPart center = host.getPart(null);
        Optional<String> cableItem = center == null ? Optional.empty() : Optional.of(idOf(center.getPartItem()));
        Set<Direction> links = EnumSet.noneOf(Direction.class);
        Map<Direction, PartView> parts = new EnumMap<>(Direction.class);
        for (Direction side : Direction.values()) {
            if (center instanceof CablePart cable && cable.isConnected(side)) links.add(side);
            IPart part = host.getPart(side);
            if (part == null) continue;
            boolean powered = part instanceof IPowerChannelState channel && channel.isPowered();
            boolean active = part instanceof IPowerChannelState channel && channel.isActive();
            parts.put(side, new PartView(idOf(part.getPartItem()), powered, active));
        }
        Optional<Boolean> powered = center instanceof IPowerChannelState channel
                ? Optional.of(channel.isPowered()) : Optional.empty();
        return new GridCell(true, cableItem, links, parts, powered, Optional.empty(), Optional.empty());
    }

    private static Optional<Boolean> poweredProperty(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property instanceof BooleanProperty flag && property.getName().equals("powered")) {
                return Optional.of(state.getValue(flag));
            }
        }
        return Optional.empty();
    }

    @Override public boolean partItem(String itemId) {
        return itemOf(itemId).map(item -> item instanceof IPartItem<?>).orElse(false);
    }

    @Override public boolean cableItem(String itemId) {
        return itemOf(itemId).map(item -> item instanceof IPartItem<?> part
                && CablePart.class.isAssignableFrom(part.getPartClass())).orElse(false);
    }

    private static Optional<Item> itemOf(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        return id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id);
    }

    private static String idOf(IPartItem<?> item) {
        return BuiltInRegistries.ITEM.getKey(item.asItem()).toString();
    }
}
