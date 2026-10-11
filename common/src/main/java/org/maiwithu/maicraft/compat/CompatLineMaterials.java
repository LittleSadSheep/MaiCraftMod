// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.LineMaterials;

/**
 * 联动模组交来的接线线材，登记表给它包的一层：读模组方块、读已有连接时碰到接口对不上就收住，
 * 按"这一格接不上、这一头连不了"回答，线路规划换别的走法，不让它变成任务的内部错误。
 * 直走与拐弯的方块是登记时就定下的，照常回答。
 */
final class CompatLineMaterials implements LineMaterials {

    private final CompatModule module;
    private final LineMaterials materials;

    CompatLineMaterials(CompatModule module, LineMaterials materials) {
        this.module = Objects.requireNonNull(module, "module");
        this.materials = Objects.requireNonNull(materials, "materials");
    }

    @Override public ExchangePoint.Medium medium() { return materials.medium(); }

    @Override public BlockState straight(Direction.Axis axis) { return materials.straight(axis); }

    @Override public Optional<BlockState> turn(Direction.Axis in, Direction.Axis out) {
        return ask(() -> materials.turn(in, out), Optional.empty());
    }

    // 读不出来时当它会接上：宁可不在那一格放线材，也不冒险把别的网连进来。
    @Override public boolean joins(BlockState neighbor) {
        return ask(() -> materials.joins(neighbor), true);
    }

    @Override public Set<Direction> connectedFaces(BlockState piece) {
        return ask(() -> materials.connectedFaces(piece), Set.of(Direction.values()));
    }

    @Override public Optional<String> laidAsInstallation() {
        return materials.laidAsInstallation();
    }

    @Override public Optional<Span> span() {
        return ask(materials::span, Optional.<Span>empty()).map(GuardedSpan::new);
    }

    private <T> T ask(Supplier<T> read, T whenUnavailable) {
        if (!module.active()) return whenUnavailable;
        try {
            return read.get();
        } catch (ModApiMismatch broken) {
            return whenUnavailable;
        }
    }

    /** 跨越也包一层：已有的那一头读不出连接数就当连不了，形状规则读不出就当不合。 */
    private final class GuardedSpan implements Span {
        private final Span span;

        GuardedSpan(Span span) {
            this.span = span;
        }

        @Override public String installationKind() { return span.installationKind(); }

        @Override public BlockState end() { return span.end(); }

        @Override public boolean isEnd(BlockState state) { return ask(() -> span.isEnd(state), false); }

        @Override public Direction.Axis endAxis() { return span.endAxis(); }

        @Override public int reach() { return span.reach(); }

        @Override public Optional<String> problem(BlockPos from, BlockPos to) {
            return ask(() -> span.problem(from, to), Optional.of(module.name() + "的联动读不出这一跨的规则"));
        }

        @Override public boolean acceptsLink(BlockPos existingEnd) {
            return ask(() -> span.acceptsLink(existingEnd), false);
        }
    }
}
