// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.travel.spi.OfferSink;
import org.maiwithu.maicraft.behavior.travel.spi.TravelOffer;
import org.maiwithu.maicraft.behavior.travel.spi.TravelRequest;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 坐矿车的报价：线路没读完先不答；附近没铁轨、连不成一条、没通电动力轨又没下坡、身上没车轨道旁也没停的，
 * 都不出报价并说一句原因；齐了就出一份，起点终点各取离得最近的那格铁轨；
 * 人正坐在矿车里时非它不可：连得出去坐到头，连不出去也给一份就近下车的报价。
 */
class MinecartModeTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 铁轨扫描替身：给死的命中清单和扫完没扫完。 */
    private static final class FakeScout implements RailScout {
        List<BlockPos> hits = List.of();
        boolean complete = true;

        @Override public Scan scanRails(int searchDistance, int want) {
            return new Scan(hits, complete);
        }
    }

    private final DimensionFakes.FakeWorld world = new DimensionFakes.FakeWorld();
    private final FakeScout scout = new FakeScout();
    private final DimensionTravelServices services =
            new DimensionTravelServices(world, () -> null, null, null, null, null, null, null, null, null);
    private final MinecartMode mode = new MinecartMode(scout, services);
    private final RecordingSink sink = new RecordingSink();

    /** 一条沿 x 轴的直线：起点 0，终点到 length-1，要不要起点通电的动力轨、要不要给人身上塞一辆矿车。 */
    private void 直线(int length, boolean poweredStart) {
        for (int x = 0; x < length; x++) {
            world.blocks.put(new BlockPos(x, 64, 0), x == 0 && poweredStart
                    ? Blocks.POWERED_RAIL.defaultBlockState()
                            .setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT, RailShape.EAST_WEST)
                            .setValue(BlockStateProperties.POWERED, true)
                    : Blocks.RAIL.defaultBlockState()
                            .setValue(BlockStateProperties.RAIL_SHAPE, RailShape.EAST_WEST));
        }
        scout.hits = List.copyOf(world.blocks.keySet());
    }

    private void 带上矿车(int count) {
        if (count > 0) {
            world.stacks.add(new BackpackStack(MinecartMode.CART_ITEM, count, 64, false, false, true, false));
        }
    }

    /** 骑乘中的方式：替身把"人正坐在矿车里"换成死的真。 */
    private MinecartMode 骑乘中的方式() {
        return new MinecartMode(scout, services) {
            @Override boolean ridingMinecart() {
                return true;
            }
        };
    }

    private TravelRequest 请求(BlockPos from, BlockPos to) {
        return new TravelRequest(from, to, "minecraft:overworld", 2, Permissions.DEFAULT, Map.of(), null);
    }

    private void 报价(BlockPos from, BlockPos to) {
        mode.offer(请求(from, to), sink);
    }

    // 线路这一刻没读完：什么都不答，下一刻再看。
    @Test
    void 线路没读完先不答() {
        直线(5, true);
        scout.complete = false;
        报价(new BlockPos(0, 65, 0), new BlockPos(4, 65, 0));
        assertNull(sink.problem);
        assertEquals(0, sink.offers.size());
    }

    // 附近没有铁轨：不出报价，说清哪里没有。
    @Test
    void 附近没有铁轨不出报价() {
        scout.hits = List.of();
        报价(new BlockPos(0, 65, 0), new BlockPos(40, 65, 0));
        assertEquals(0, sink.offers.size());
        assertTrue(sink.problem.message().contains("没有铁轨"), sink.problem.message());
    }

    // 通电直线 + 身上有矿车：出一份报价，起点终点各取离得最近的轨道格。
    @Test
    void 通电直线身上有车出报价() {
        直线(40, true);
        带上矿车(1);
        报价(new BlockPos(0, 65, 2), new BlockPos(39, 65, 1));
        assertEquals(1, sink.offers.size());
        TravelOffer offer = sink.offers.getFirst();
        assertEquals("minecart", offer.via().name());
        assertEquals(new BlockPos(0, 64, 0), offer.from());
        assertEquals(new BlockPos(39, 64, 0), offer.to());
        assertNull(sink.problem);
    }

    // 线路连得通但没动力也没下坡：不出报价，原因说清。
    @Test
    void 没动力又没下坡不出报价() {
        直线(40, false);
        带上矿车(1);
        报价(new BlockPos(0, 65, 0), new BlockPos(39, 65, 0));
        assertEquals(0, sink.offers.size());
        assertEquals(Problem.Kind.UNREACHABLE, sink.problem.kind());
        assertTrue(sink.problem.message().contains("滑不到头") || sink.problem.message().contains("刹停"),
                sink.problem.message());
    }

    // 两头的铁轨连不成一条：不出报价，说清连不成。
    @Test
    void 两头连不成不出报价() {
        for (int x = 0; x <= 5; x++) {
            world.blocks.put(new BlockPos(x, 64, 0), Blocks.RAIL.defaultBlockState()
                    .setValue(BlockStateProperties.RAIL_SHAPE, RailShape.EAST_WEST));
        }
        for (int x = 50; x <= 55; x++) {
            world.blocks.put(new BlockPos(x, 64, 0), Blocks.RAIL.defaultBlockState()
                    .setValue(BlockStateProperties.RAIL_SHAPE, RailShape.EAST_WEST));
        }
        scout.hits = List.copyOf(world.blocks.keySet());
        带上矿车(1);
        报价(new BlockPos(0, 65, 0), new BlockPos(55, 65, 0));
        assertEquals(0, sink.offers.size());
        assertTrue(sink.problem.message().contains("连不成"), sink.problem.message());
    }

    // 线路通、也有动力，但身上没车、轨道旁也没停的：不出报价，说不现合成。
    @Test
    void 没车不出报价() {
        直线(10, true);
        报价(new BlockPos(0, 65, 0), new BlockPos(9, 65, 0));
        assertEquals(0, sink.offers.size());
        assertEquals(Problem.Kind.NOT_FOUND, sink.problem.kind());
        assertTrue(sink.problem.message().contains("矿车"), sink.problem.message());
    }

    // 没给目的地：坐矿车要有目的地，如实说。
    @Test
    void 没目的地不出报价() {
        报价(new BlockPos(0, 65, 0), null);
        assertEquals(0, sink.offers.size());
        assertTrue(sink.problem.message().contains("目的地"), sink.problem.message());
    }

    // 不在矿车里时这一段不是非它不可。
    @Test
    void 走路时不算非它不可() {
        assertFalse(mode.claims(请求(new BlockPos(0, 65, 0), new BlockPos(9, 65, 0))));
    }

    // 骑乘中（非它不可）线路连不到目的地旁：仍要给一份就近下车的报价，不 decline。
    @Test
    void 骑乘中连不出线路给就近下车的报价() {
        直线(10, true);
        // 目的地旁另有一段孤立的铁轨，和身边的线路接不上。
        for (int x = 200; x <= 205; x++) {
            world.blocks.put(new BlockPos(x, 64, 0), Blocks.RAIL.defaultBlockState()
                    .setValue(BlockStateProperties.RAIL_SHAPE, RailShape.EAST_WEST));
        }
        scout.hits = List.copyOf(world.blocks.keySet());
        MinecartMode riding = 骑乘中的方式();
        riding.offer(请求(new BlockPos(0, 65, 0), new BlockPos(202, 65, 0)), sink);
        assertEquals(1, sink.offers.size());
        TravelOffer offer = sink.offers.getFirst();
        assertEquals("minecart", offer.via().name());
        assertEquals(offer.from(), offer.to(), "就近下车的报价起点就是终点");
        assertNull(sink.problem);
    }

    // 骑乘中连得出去：照常给坐到头的报价，不挑供电也不挑车。
    @Test
    void 骑乘中连得出去坐到头() {
        直线(10, true);
        MinecartMode riding = 骑乘中的方式();
        riding.offer(请求(new BlockPos(0, 65, 0), new BlockPos(9, 65, 0)), sink);
        assertEquals(1, sink.offers.size());
        TravelOffer offer = sink.offers.getFirst();
        assertEquals(new BlockPos(0, 64, 0), offer.from());
        assertEquals(new BlockPos(9, 64, 0), offer.to());
        assertNull(sink.problem);
    }

    /** 记下报价与拒绝的 sinks。 */
    private static final class RecordingSink implements OfferSink {
        final List<TravelOffer> offers = new ArrayList<>();
        Problem problem;

        @Override public void offer(TravelOffer offer) {
            offers.add(offer);
        }

        @Override public void decline(Problem.Kind kind, String reason) {
            problem = Problem.of(kind, reason, null);
        }
    }
}
