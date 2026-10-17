// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.LineMaterials;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 应力网的接线线材：直走一格是轴（沿轴向）、拐弯是齿轮箱（轴向取另外那个轴，四个侧面都接）、
 * 远距离是链条输送机之间的链（链条输送机只从上下两面接转动）。近处用轴和齿轮箱、远处用链条，
 * 线路规划按代价挑；挨着别的动力方块的格不放会四面接的齿轮箱，免得把别的网连进来、转向冲突把线弄断。
 */
final class KineticLines implements LineMaterials {

    private final CreateCompat compat;
    private final PlayerServices services;
    private final Block shaft;
    private final Block gearbox;
    private final Block conveyor;

    private KineticLines(CreateCompat compat, PlayerServices services, Block shaft, Block gearbox, Block conveyor) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.shaft = shaft;
        this.gearbox = gearbox;
        this.conveyor = conveyor;
    }

    /** 按注册 ID 找轴、齿轮箱、链条输送机；轴或齿轮箱找不到（装的版本改了名）就不给线材。 */
    static Optional<LineMaterials> of(CreateCompat compat, PlayerServices services) {
        Optional<Block> shaft = block("create:shaft");
        Optional<Block> gearbox = block("create:gearbox");
        if (shaft.isEmpty() || gearbox.isEmpty()) return Optional.empty();
        return Optional.of(new KineticLines(compat, services, shaft.get(), gearbox.get(),
                block("create:chain_conveyor").orElse(null)));
    }

    private static Optional<Block> block(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(key);
    }

    @Override public ExchangePoint.Medium medium() {
        return ExchangePoint.Medium.ROTATION;
    }

    @Override public BlockState straight(Direction.Axis axis) {
        return shaft.defaultBlockState().setValue(BlockStateProperties.AXIS, axis);
    }

    // 齿轮箱接轴向以外的四面：从 in 拐到 out，轴向就取剩下的那个轴。
    @Override public Optional<BlockState> turn(Direction.Axis in, Direction.Axis out) {
        if (in == out) return Optional.empty();
        for (Direction.Axis axis : Direction.Axis.values()) {
            if (axis != in && axis != out) {
                return Optional.of(gearbox.defaultBlockState().setValue(BlockStateProperties.AXIS, axis));
            }
        }
        return Optional.empty();
    }

    @Override public boolean joins(BlockState neighbor) {
        return compat.kinetic(neighbor);
    }

    @Override public Set<Direction> connectedFaces(BlockState piece) {
        if (!piece.hasProperty(BlockStateProperties.AXIS)) return EnumSet.allOf(Direction.class);
        Direction.Axis axis = piece.getValue(BlockStateProperties.AXIS);
        Set<Direction> faces = EnumSet.noneOf(Direction.class);
        for (Direction face : Direction.values()) {
            boolean alongAxis = face.getAxis() == axis;
            // 轴只接沿轴向的两面；齿轮箱反过来，接轴向以外的四面。
            if (piece.is(gearbox) != alongAxis) faces.add(face);
        }
        return faces;
    }

    @Override public Optional<Span> span() {
        return conveyor == null ? Optional.empty() : Optional.of(new ChainSpan());
    }

    /** 链条输送机之间的链：新放的一头是链条输送机，上下两面接转动；长度与连接数读 Create 的服务器配置。 */
    private final class ChainSpan implements Span {

        @Override public String installationKind() {
            return ChainConveyorInstallation.KIND;
        }

        @Override public BlockState end() {
            return conveyor.defaultBlockState();
        }

        @Override public boolean isEnd(BlockState state) {
            return state.is(conveyor);
        }

        @Override public Direction.Axis endAxis() {
            return Direction.Axis.Y;
        }

        @Override public int reach() {
            return compat.chainMaxLength();
        }

        @Override public Optional<String> problem(BlockPos from, BlockPos to) {
            return ChainConveyorInstallation.shapeProblem(List.of(from, to), Optional.of(compat.chainMaxLength()));
        }

        @Override public boolean acceptsLink(BlockPos existingEnd) {
            PlayerContext current = services.context().get();
            ClientLevel level = current == null ? null : current.level();
            if (level == null) return false;
            return compat.chainConnections(level, existingEnd)
                    .map(links -> links.size() < compat.chainMaxConnections()).orElse(false);
        }
    }
}
