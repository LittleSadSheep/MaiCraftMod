// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 量光：可行走格与庄稼按档进样本，敏感面记成保护事实，没加载的列如实记下；复测在冻结样本上重新量。 */
class LightSurveyorTest {

    @BeforeAll
    static void bootMinecraft() {
        // 方块注册表要等引导完才能碰：这些静态字段在这里才取。
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        STONE = Blocks.STONE.defaultBlockState();
        WHEAT = Blocks.WHEAT.defaultBlockState();
        FARMLAND = Blocks.FARMLAND.defaultBlockState();
        CHEST = Blocks.CHEST.defaultBlockState();
    }

    private static BlockState STONE;
    private static BlockState WHEAT;
    private static BlockState FARMLAND;
    private static BlockState CHEST;

    private static BlockPos at(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    @Test
    void 可行走格进样本_敏感面记保护事实() {
        LightingFakes.FakeWorld world = new LightingFakes.FakeWorld();
        world.floor(STONE, 63, -1, 1, -1, 1);
        world.put(at(0, 64, 0), CHEST);
        LightingFakes.FakeLights lights = new LightingFakes.FakeLights().light(at(1, 64, 1), 3);
        LightSurveyor.SampleScan scan = new LightSurveyor.SampleScan(world, lights,
                List.of(at(0, 64, 0), at(1, 64, 1)), false);
        while (!scan.tick()) { }
        LightSurveyor.LightSurvey survey = scan.survey();
        List<BlockPos> positions = survey.samples().stream().map(LightSample::pos).toList();
        assertTrue(positions.contains(at(1, 64, 1)), "能站住的格进样本");
        assertFalse(positions.contains(at(0, 64, 0)), "箱子那格不是可行走样本");
        assertTrue(survey.sensitive().contains(at(0, 64, 0)), "箱子记成敏感面");
        assertEquals(3, survey.lights().get(at(1, 64, 1)));
    }

    @Test
    void 农田档只采庄稼与耕地上方() {
        LightingFakes.FakeWorld world = new LightingFakes.FakeWorld();
        world.floor(FARMLAND, 63, 0, 1, 0, 0);
        world.put(at(0, 64, 0), WHEAT);
        world.put(at(1, 64, 0), Blocks.AIR.defaultBlockState());
        LightSurveyor.SampleScan scan = new LightSurveyor.SampleScan(world, new LightingFakes.FakeLights(),
                List.of(at(0, 64, 0), at(1, 64, 0)), true);
        while (!scan.tick()) { }
        LightSurveyor.LightSurvey survey = scan.survey();
        assertEquals(2, survey.samples().size());
        assertEquals(SampleRules.SampleKind.CROP, survey.samples().getFirst().kind(), "庄稼格按庄稼记");
        assertEquals(SampleRules.SampleKind.FARMLAND, survey.samples().get(1).kind(), "没种东西的耕地上方按耕地记");
        assertTrue(survey.samples().stream().noneMatch(sample -> sample.kind() == SampleRules.SampleKind.WALKABLE),
                "农田档不收普通可行走格");
    }

    @Test
    void 耕地上方没种东西_记耕地上方样本() {
        LightingFakes.FakeWorld world = new LightingFakes.FakeWorld();
        world.floor(FARMLAND, 63, 0, 0, 0, 0);
        world.put(at(0, 64, 0), Blocks.AIR.defaultBlockState());
        LightSurveyor.SampleScan scan = new LightSurveyor.SampleScan(world, new LightingFakes.FakeLights(),
                List.of(at(0, 64, 0)), true);
        while (!scan.tick()) { }
        assertEquals(1, scan.survey().samples().size());
        assertEquals(SampleRules.SampleKind.FARMLAND, scan.survey().samples().getFirst().kind());
    }

    @Test
    void 没加载的列如实记下_不冒充样本() {
        LightingFakes.FakeWorld world = new LightingFakes.FakeWorld();
        world.floor(STONE, 63, 0, 0, 0, 0);
        LightSurveyor.SampleScan scan = new LightSurveyor.SampleScan(world, new LightingFakes.FakeLights(),
                List.of(at(0, 64, 0), at(9, 64, 9)), false);
        while (!scan.tick()) { }
        LightSurveyor.LightSurvey survey = scan.survey();
        assertTrue(survey.unloaded().contains(at(9, 64, 9)));
        assertEquals(1, survey.samples().size());
    }

    @Test
    void 复测在冻结样本上重新量() {
        LightingFakes.FakeWorld world = new LightingFakes.FakeWorld();
        world.floor(STONE, 63, 0, 0, 0, 0);
        LightingFakes.LiveLights lights = new LightingFakes.LiveLights(world);
        List<LightSample> frozen = List.of(new LightSample(at(0, 64, 0), SampleRules.SampleKind.WALKABLE));
        assertEquals(0, new LightSurveyor.Recheck(lights, frozen).result().size(),
                "没量之前没有结论");
        LightSurveyor.Recheck recheck = new LightSurveyor.Recheck(lights, frozen);
        while (!recheck.tick()) { }
        assertEquals(0, recheck.result().getFirst().light());
        // 放一支火把在旁边，复测量到新的光。
        world.put(at(1, 64, 0), Blocks.TORCH.defaultBlockState());
        LightSurveyor.Recheck after = new LightSurveyor.Recheck(lights, frozen);
        while (!after.tick()) { }
        assertTrue(after.result().getFirst().light() >= 8, "火把相邻格亮起来了");
        // 火把没了，复测又暗回去。
        world.blocks.remove(at(1, 64, 0));
        LightSurveyor.Recheck gone = new LightSurveyor.Recheck(lights, frozen);
        while (!gone.tick()) { }
        assertEquals(0, gone.result().getFirst().light());
    }

    @Test
    void 复测时格没加载_如实记空() {
        LightingFakes.FakeWorld world = new LightingFakes.FakeWorld();
        world.floor(STONE, 63, 0, 0, 0, 0);
        LightSurveyor.Recheck recheck = new LightSurveyor.Recheck(new LightingFakes.LiveLights(world),
                List.of(new LightSample(at(5, 64, 5), SampleRules.SampleKind.WALKABLE)));
        while (!recheck.tick()) { }
        assertNull(recheck.result().getFirst().light(), "没加载的格不猜");
    }
}
