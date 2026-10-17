// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.LineMaterials;

/**
 * ME 网的接线线材：福鲁伊克斯玻璃线缆（不带颜色，和任何颜色的线缆都连），不分方向、六面都接，拐弯也是它。
 * 线缆不是能直接放的方块，所以整条线写成一段安装段（种类是线缆物品 ID），由线缆格那种设备一格一格接。
 * 挨着别的 ME 设备的格不铺（会把两张网连成一张），来源与目标两头除外；ME 网没有远距离跨越。
 */
final class MeLines implements LineMaterials {

    /** 默认用的线缆：不带颜色，连谁都行。 */
    static final String CABLE = "ae2:fluix_glass_cable";

    MeLines() {
    }

    @Override public ExchangePoint.Medium medium() {
        return ExchangePoint.Medium.ME;
    }

    @Override public BlockState straight(Direction.Axis axis) {
        return cableBus();
    }

    @Override public Optional<BlockState> turn(Direction.Axis in, Direction.Axis out) {
        return Optional.of(cableBus());
    }

    // 挨着 AE2 的方块：线缆会和它连上，两张网就成了一张（频道一起算），不在那一格铺。
    @Override public boolean joins(BlockState neighbor) {
        return BuiltInRegistries.BLOCK.getKey(neighbor.getBlock()).getNamespace().equals(Ae2Compat.MOD_ID);
    }

    @Override public Set<Direction> connectedFaces(BlockState piece) {
        return EnumSet.allOf(Direction.class);
    }

    @Override public Optional<Span> span() {
        return Optional.empty();
    }

    @Override public Optional<String> laidAsInstallation() {
        return Optional.of(CABLE);
    }

    // 线路规划拿它判断"这一格放的是什么"：线缆格方块；找不到（版本改名）就当空气，不影响判断哪几面接。
    private static BlockState cableBus() {
        ResourceLocation id = ResourceLocation.tryParse(Ae2MachineCatalog.CABLE_BUS);
        return id == null ? Blocks.AIR.defaultBlockState()
                : BuiltInRegistries.BLOCK.getOptional(id).map(block -> block.defaultBlockState())
                        .orElse(Blocks.AIR.defaultBlockState());
    }
}
