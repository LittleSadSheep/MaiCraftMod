// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.LineMaterials;

/**
 * 线路规划：直线优先、拐弯用拐弯件、绕开占着的格、拐弯处不贴着会被一起接上的方块、远了用跨越、
 * 不许新放跨越的头时老实铺长线、无路可走如实说。原木代轴（有轴向），石头代齿轮箱，熔炉代别的机器，灯笼代链条输送机。
 */
class RoutePlannerTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 测试线材：原木沿轴向接两面，石头（拐弯件）六面都接，熔炉会被接上，灯笼是跨越的一头。 */
    private static final class TestMaterials implements LineMaterials {
        @Override public ExchangePoint.Medium medium() { return ExchangePoint.Medium.ROTATION; }

        @Override public BlockState straight(Direction.Axis axis) {
            return Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, axis);
        }

        @Override public Optional<BlockState> turn(Direction.Axis in, Direction.Axis out) {
            return Optional.of(Blocks.STONE.defaultBlockState());
        }

        @Override public boolean joins(BlockState neighbor) { return neighbor.is(Blocks.FURNACE); }

        @Override public Set<Direction> connectedFaces(BlockState piece) {
            if (!piece.hasProperty(BlockStateProperties.AXIS)) return EnumSet.allOf(Direction.class);
            Direction.Axis axis = piece.getValue(BlockStateProperties.AXIS);
            return Set.of(Direction.get(Direction.AxisDirection.POSITIVE, axis),
                    Direction.get(Direction.AxisDirection.NEGATIVE, axis));
        }

        @Override public Optional<Span> span() {
            return Optional.of(new Span() {
                @Override public String installationKind() { return "test:chain"; }
                @Override public BlockState end() { return Blocks.LANTERN.defaultBlockState(); }
                @Override public boolean isEnd(BlockState state) { return state.is(Blocks.LANTERN); }
                @Override public Direction.Axis endAxis() { return Direction.Axis.Y; }
                @Override public int reach() { return 32; }
                @Override public Optional<String> problem(BlockPos from, BlockPos to) {
                    return from.distSqr(to) < 9 ? Optional.of("太近") : Optional.empty();
                }
                @Override public boolean acceptsLink(BlockPos existingEnd) { return true; }
            });
        }
    }

    /** 现场：记下的格是占着的方块，其余都是空气。 */
    private static final class TestGround implements RoutePlanner.Ground {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        @Override public boolean free(BlockPos at) { return !blocks.containsKey(at); }

        @Override public Optional<BlockState> state(BlockPos at) {
            return Optional.of(blocks.getOrDefault(at, Blocks.AIR.defaultBlockState()));
        }
    }

    private static RoutePlanner.Attach attach(BlockPos cell, Direction towardEndpoint) {
        return new RoutePlanner.Attach(cell, towardEndpoint.getAxis(), cell.relative(towardEndpoint));
    }

    private static RoutePlanner.Plan found(RoutePlanner.Result result) {
        return assertInstanceOf(RoutePlanner.Found.class, result, result.toString()).plan();
    }

    @Test
    void 同一条直线上一路铺轴_不拐弯() {
        TestGround ground = new TestGround();
        RoutePlanner.Plan plan = found(RoutePlanner.plan(List.of(attach(new BlockPos(0, 64, 0), Direction.WEST)),
                List.of(), List.of(attach(new BlockPos(5, 64, 0), Direction.EAST)), new TestMaterials(), ground, false));
        assertEquals(6, plan.cells().size());
        assertEquals(0, plan.turns());
        assertTrue(plan.cells().stream().allMatch(placed -> placed.state().is(Blocks.OAK_LOG)
                && placed.state().getValue(BlockStateProperties.AXIS) == Direction.Axis.X));
        assertTrue(plan.span().isEmpty());
    }

    @Test
    void 不在一条线上_拐一次弯接到侧面() {
        RoutePlanner.Plan plan = found(RoutePlanner.plan(List.of(attach(new BlockPos(0, 64, 0), Direction.WEST)),
                List.of(), List.of(attach(new BlockPos(4, 64, 3), Direction.SOUTH)), new TestMaterials(),
                new TestGround(), false));
        assertEquals(1, plan.turns());
        assertEquals(Direction.Axis.Z, plan.cells().getLast().state().getValue(BlockStateProperties.AXIS),
                "最后一格沿目标的接线面接上");
    }

    @Test
    void 占着的格绕开走() {
        TestGround ground = new TestGround();
        for (int y = 60; y <= 68; y++) ground.blocks.put(new BlockPos(3, y, 0), Blocks.STONE.defaultBlockState());
        RoutePlanner.Plan plan = found(RoutePlanner.plan(List.of(attach(new BlockPos(0, 64, 0), Direction.WEST)),
                List.of(), List.of(attach(new BlockPos(6, 64, 0), Direction.EAST)), new TestMaterials(), ground, false));
        assertTrue(plan.cells().stream().noneMatch(placed -> ground.blocks.containsKey(placed.pos())));
        assertTrue(plan.turns() > 0);
    }

    @Test
    void 拐弯件不贴着别的机器放() {
        TestGround ground = new TestGround();
        // 最省的拐弯处旁边摆一台别的机器：拐弯件六面都接，放在那儿会把它也连进来。
        ground.blocks.put(new BlockPos(4, 64, -1), Blocks.FURNACE.defaultBlockState());
        RoutePlanner.Plan plan = found(RoutePlanner.plan(List.of(attach(new BlockPos(0, 64, 0), Direction.WEST)),
                List.of(), List.of(attach(new BlockPos(4, 64, 3), Direction.SOUTH)), new TestMaterials(), ground, false));
        for (RoutePlanner.Placed placed : plan.cells()) {
            if (!placed.state().is(Blocks.STONE)) continue;
            for (Direction side : Direction.values()) {
                assertFalse(ground.blocks.getOrDefault(placed.pos().relative(side), Blocks.AIR.defaultBlockState())
                        .is(Blocks.FURNACE), "拐弯件贴着别的机器：" + placed.pos());
            }
        }
    }

    @Test
    void 远处有已有的跨越一头_跨过去再短接() {
        TestGround ground = new TestGround();
        BlockPos existing = new BlockPos(0, 70, 0);
        ground.blocks.put(existing, Blocks.LANTERN.defaultBlockState());
        RoutePlanner.Plan plan = found(RoutePlanner.plan(List.of(attach(new BlockPos(0, 64, 0), Direction.WEST)),
                List.of(existing), List.of(attach(new BlockPos(28, 64, 0), Direction.EAST)), new TestMaterials(),
                ground, true));
        assertTrue(plan.span().isPresent(), "远了用跨越");
        assertEquals(existing, plan.span().get().from());
        assertTrue(plan.cells().stream().anyMatch(placed -> placed.state().is(Blocks.LANTERN)), "新放的一头在线路里");
        assertTrue(plan.cells().size() < 20, "跨过去之后只剩短线：" + plan.cells().size());
    }

    @Test
    void 不许新放跨越的头时老实铺长线() {
        TestGround ground = new TestGround();
        BlockPos existing = new BlockPos(0, 70, 0);
        ground.blocks.put(existing, Blocks.LANTERN.defaultBlockState());
        RoutePlanner.Plan plan = found(RoutePlanner.plan(List.of(attach(new BlockPos(0, 64, 0), Direction.WEST)),
                List.of(existing), List.of(attach(new BlockPos(28, 64, 0), Direction.EAST)), new TestMaterials(),
                ground, false));
        assertTrue(plan.span().isEmpty());
        assertEquals(29, plan.cells().size());
    }

    @Test
    void 目标被围死_如实说找不到() {
        TestGround ground = new TestGround();
        BlockPos target = new BlockPos(5, 64, 0);
        for (Direction side : Direction.values()) ground.blocks.put(target.relative(side), Blocks.STONE.defaultBlockState());
        ground.blocks.remove(target.relative(Direction.EAST));
        ground.blocks.put(target, Blocks.STONE.defaultBlockState());
        RoutePlanner.Result result = RoutePlanner.plan(List.of(attach(new BlockPos(0, 64, 0), Direction.WEST)),
                List.of(), List.of(attach(target, Direction.EAST)), new TestMaterials(), ground, false);
        assertInstanceOf(RoutePlanner.NotFound.class, result);
    }
}
