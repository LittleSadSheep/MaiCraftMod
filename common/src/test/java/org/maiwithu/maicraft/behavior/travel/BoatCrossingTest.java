// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 划原版船的只读判断（离线）：隔水勘测、放船站位、靠岸点、划船转向、"非它不可"。
 * 世界用一张方块表替身摆出来，纯函数只读它。
 */
class BoatCrossingTest {

    private final Map<Long, BlockState> cells = new HashMap<>();

    private Function<BlockPos, Optional<BlockState>> world() {
        return pos -> Optional.ofNullable(cells.get(pos.asLong()));
    }

    /** 铺一柱：给方块就放，没给就是空气。 */
    private void put(int x, int y, int z, BlockState state) {
        cells.put(new BlockPos(x, y, z).asLong(), state);
    }

    /** 铺一条东西向的地：x 在 [waterFrom, waterTo] 之间是水（顶上给 waterOrIce），其余是石头，地表都在 groundY。 */
    private void lake(int waterFrom, int waterTo, int groundY, BlockState waterOrIce) {
        for (int x = waterFrom - 6; x <= waterTo + 6; x++) {
            put(x, groundY, 0, waterFrom <= x && x <= waterTo ? waterOrIce : Blocks.STONE.defaultBlockState());
        }
    }

    @BeforeAll
    static void 引导方块注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 隔着一片湖算出两岸和水宽() {
        lake(10, 30, 64, Blocks.WATER.defaultBlockState());
        WaterCrossing.Survey survey = WaterCrossing.between(world(), new BlockPos(0, 65, 0), new BlockPos(45, 65, 0));
        assertEquals(WaterCrossing.Answer.ACROSS_WATER, survey.answer());
        assertEquals(21, survey.span().waterWidth());
        // 岸柱记的是连线上那一点（脚下高度），放船站位与靠岸点都拿它当找的圆心。
        assertEquals(new BlockPos(9, 65, 0), survey.span().nearShore());
        assertEquals(new BlockPos(31, 65, 0), survey.span().farShore());
    }

    @Test
    void 一整片实地不算隔水() {
        lake(50, 60, 64, Blocks.WATER.defaultBlockState());
        assertEquals(WaterCrossing.Answer.NO_WATER,
                WaterCrossing.between(world(), new BlockPos(0, 65, 0), new BlockPos(45, 65, 0)).answer());
    }

    @Test
    void 出发地泡在水里不报价放船() {
        lake(-10, 30, 64, Blocks.WATER.defaultBlockState());
        assertEquals(WaterCrossing.Answer.WATER_AT_START,
                WaterCrossing.between(world(), new BlockPos(0, 65, 0), new BlockPos(45, 65, 0)).answer());
    }

    @Test
    void 水面冻住了不报价() {
        lake(10, 30, 64, Blocks.ICE.defaultBlockState());
        assertEquals(WaterCrossing.Answer.FROZEN,
                WaterCrossing.between(world(), new BlockPos(0, 65, 0), new BlockPos(45, 65, 0)).answer());
    }

    @Test
    void 目的地在水中央划过去也上不了岸() {
        lake(10, 44, 64, Blocks.WATER.defaultBlockState());
        assertEquals(WaterCrossing.Answer.WATER_AT_END,
                WaterCrossing.between(world(), new BlockPos(0, 65, 0), new BlockPos(40, 65, 0)).answer());
    }

    @Test
    void 窄溪沟不算隔水() {
        // 五格宽的小沟：踩水或一步就过，不值得放船。
        lake(10, 14, 64, Blocks.WATER.defaultBlockState());
        assertEquals(WaterCrossing.Answer.NO_WATER,
                WaterCrossing.between(world(), new BlockPos(0, 65, 0), new BlockPos(45, 65, 0)).answer());
    }

    @Test
    void 耗时按水上每秒八格加零头估() {
        lake(10, 30, 64, Blocks.WATER.defaultBlockState());
        WaterCrossing.Survey survey = WaterCrossing.between(world(), new BlockPos(0, 65, 0), new BlockPos(45, 65, 0));
        long ticks = WaterCrossing.estimateTicks(new BlockPos(0, 65, 0), survey.span(), new BlockPos(45, 65, 0));
        // 21 格水约 53 划刻，加放船上下船的零头与两头走路，怎么也比干划久。
        assertTrue(ticks > 20 * 6 + 20, "估时太短：" + ticks);
    }

    @Test
    void 岸边挑得出放船的站位() {
        lake(10, 30, 64, Blocks.WATER.defaultBlockState());
        List<BoatLaunchSpots.Spot> spots = BoatLaunchSpots.around(world(), new BlockPos(9, 64, 0),
                new BlockPos(0, 65, 0), new BlockPos(45, 65, 0), 3);
        assertFalse(spots.isEmpty());
        // 站位在岸上、水面前一格是那片湖。
        assertTrue(spots.stream().anyMatch(spot -> spot.water().equals(new BlockPos(10, 64, 0))),
                "要有正对湖面的站位：" + spots);
    }

    @Test
    void 水面比脚下低太多不放船() {
        // 岸是一堵高两格的坎，水面低在下面：够不着水面不放。
        for (int x = 0; x <= 8; x++) {
            put(x, 64, 0, Blocks.STONE.defaultBlockState());
            put(x, 65, 0, Blocks.STONE.defaultBlockState());
        }
        for (int x = 9; x <= 30; x++) put(x, 62, 0, Blocks.WATER.defaultBlockState());
        for (int x = 31; x <= 40; x++) put(x, 64, 0, Blocks.STONE.defaultBlockState());
        assertTrue(BoatLaunchSpots.around(world(), new BlockPos(8, 65, 0), new BlockPos(0, 66, 0),
                new BlockPos(40, 65, 0), 3).isEmpty());
    }

    @Test
    void 对岸挑紧挨水面的靠岸点() {
        lake(10, 30, 64, Blocks.WATER.defaultBlockState());
        List<BlockPos> landings = BoatLandings.around(world(), new BlockPos(31, 64, 0), new BlockPos(45, 65, 0));
        assertFalse(landings.isEmpty());
        // 靠岸点都在实地、紧挨水面，按离目的地近先排。
        for (BlockPos landing : landings) {
            assertTrue(BoatLandings.touchesWater(world(), landing), "要挨着水：" + landing);
        }
        // 目的地在东边（x=45）：排在最前的是最靠东的那个靠岸点。
        assertEquals(landings.stream().mapToInt(BlockPos::getX).max().orElseThrow(),
                landings.getFirst().getX(), "离目的地近的排前面");
    }

    @Test
    void 对准了直划差得多就转() {
        // 船头朝南（yaw 0），目标在东边（+x，目标 yaw -90）：要向左转（平移 +1），同时继续划。
        BoatSteering.Decision turning = BoatSteering.decide(-90, false, new BoatSteering.Pace(0, 0));
        assertEquals(1.0F, turning.forward());
        assertEquals(1.0F, turning.strafe());
        assertFalse(turning.switchLanding());
        BoatSteering.Decision straight = BoatSteering.decide(5, false, new BoatSteering.Pace(0, 0));
        assertEquals(0.0F, straight.strafe());
    }

    @Test
    void 顶到东西先倒退() {
        BoatSteering.Decision reversing = BoatSteering.decide(0, true, new BoatSteering.Pace(0, 0));
        assertEquals(-1.0F, reversing.forward());
        assertFalse(reversing.switchLanding());
        // 还在倒退的刻数里继续倒退。
        assertEquals(-1.0F, BoatSteering.decide(0, false, new BoatSteering.Pace(0, 5)).forward());
    }

    @Test
    void 连着三个观察窗没前进就换靠岸点() {
        assertFalse(BoatSteering.decide(0, false, new BoatSteering.Pace(2, 0)).switchLanding());
        assertTrue(BoatSteering.decide(0, false, new BoatSteering.Pace(3, 0)).switchLanding());
    }

    @Test
    void 朝向差算得对() {
        // 船头朝南（0），目标在正西（-x，目标 yaw 90）：差 90 度，向右转。
        assertEquals(90.0F, BoatSteering.yawError(0, new Vec3(0, 64, 0), new Vec3(-5, 64, 0)));
        // 目标几乎在正北（-z）：目标朝向约 -180 度，取负的近路转过去。
        assertEquals(-178.57F, BoatSteering.yawError(0, new Vec3(0, 64, 0), new Vec3(0.5, 64, -20)), 0.01);
    }

    @Test
    void 前方有岸睡莲冰都算顶到了() {
        BlockState water = Blocks.WATER.defaultBlockState();
        for (int x = 0; x <= 30; x++) {
            put(x, 63, 0, Blocks.STONE.defaultBlockState());
            put(x, 64, 0, water);
        }
        Vec3 boat = new Vec3(10.5, 64.0, 0.5);
        float facingEast = -90.0F;
        assertFalse(BoatSteering.blockedAhead(world(), boat, facingEast), "一汪清水不挡");
        put(12, 64, 0, Blocks.STONE.defaultBlockState());
        assertTrue(BoatSteering.blockedAhead(world(), boat, facingEast), "撞岸");
        put(12, 64, 0, water);
        put(12, 65, 0, Blocks.LILY_PAD.defaultBlockState());
        assertTrue(BoatSteering.blockedAhead(world(), boat, facingEast), "睡莲");
        put(12, 65, 0, Blocks.AIR.defaultBlockState());
        put(12, 64, 0, Blocks.ICE.defaultBlockState());
        assertTrue(BoatSteering.blockedAhead(world(), boat, facingEast), "冰");
    }

    @Test
    void 水里的草和海带不算挡路() {
        BlockState water = Blocks.WATER.defaultBlockState();
        for (int x = 0; x <= 30; x++) {
            put(x, 63, 0, Blocks.STONE.defaultBlockState());
            put(x, 64, 0, water);
        }
        Vec3 boat = new Vec3(10.5, 64.0, 0.5);
        float facingEast = -90.0F;
        put(12, 65, 0, Blocks.SEAGRASS.defaultBlockState());
        assertFalse(BoatSteering.blockedAhead(world(), boat, facingEast), "海草船穿得过去");
        put(12, 64, 0, Blocks.KELP.defaultBlockState());
        put(12, 65, 0, Blocks.KELP_PLANT.defaultBlockState());
        assertFalse(BoatSteering.blockedAhead(world(), boat, facingEast), "海带柱也穿得过去");
        put(12, 64, 0, water);
        put(12, 65, 0, Blocks.AIR.defaultBlockState());
    }

    @Test
    void 顶着睡莲的水面不放船() {
        lake(10, 30, 64, Blocks.WATER.defaultBlockState());
        put(10, 65, 0, Blocks.LILY_PAD.defaultBlockState());
        // 朝目的地那边最近的水面被睡莲占了：站位要么换到别的水面，要么没有能用的。
        List<BoatLaunchSpots.Spot> spots = BoatLaunchSpots.around(world(), new BlockPos(9, 65, 0),
                new BlockPos(0, 65, 0), new BlockPos(45, 65, 0), 3);
        assertTrue(spots.stream().noneMatch(spot -> spot.water().equals(new BlockPos(10, 64, 0))),
                "睡莲顶格的水面不能放船：" + spots);
    }

    @Test
    void 人坐在原版船上时在主世界非它不可() {
        assertTrue(BoatCrossing.claimsFor("minecraft:overworld", true));
        assertFalse(BoatCrossing.claimsFor("minecraft:overworld", false), "没坐船还有的选");
        assertFalse(BoatCrossing.claimsFor("minecraft:the_nether", true), "下界没有能划的水");
    }

    @Test
    void 认得出身上的船物品() {
        assertTrue(BoatCrossing.isBoatItem("minecraft:oak_boat"));
        assertTrue(BoatCrossing.isBoatItem("minecraft:chest_boat"));
        assertTrue(BoatCrossing.isBoatItem("minecraft:bamboo_raft"));
        assertFalse(BoatCrossing.isBoatItem("minecraft:stone"));
        assertFalse(BoatCrossing.isBoatItem("minecraft:boat_hook"));
    }
}
