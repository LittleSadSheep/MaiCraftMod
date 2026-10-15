// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 岩浆池勘察：直岸合规、弯岸给出要填的格、余量不够拒、模板里有格没加载不下结论、
 * 受保护格拒、有站位的候选排前、起手行已是黑曜石的接着认。
 */
class LavaPoolSurveyTest {

    private static final PortalCastingLayout LAYOUT = new PortalCastingLayout(new BlockPos(10, 64, 10), Direction.NORTH);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Function<BlockPos, Optional<BlockState>> readOf(Map<BlockPos, BlockState> world) {
        return at -> Optional.ofNullable(world.get(at));
    }

    @Test
    void straightShoreIsAcceptedWithNothingToFill() {
        Map<BlockPos, BlockState> world = CastingFakes.straightShore(LAYOUT, 2);
        LavaPoolSurvey.Verdict verdict = LavaPoolSurvey.judge(LAYOUT, readOf(world), at -> true, LAYOUT.cell(0, 1, 2));
        assertTrue(verdict instanceof LavaPoolSurvey.Verdict.Ok, verdict.toString());
        LavaPoolSurvey.Candidate candidate = ((LavaPoolSurvey.Verdict.Ok) verdict).candidate();
        assertTrue(candidate.bankFill().isEmpty(), "直岸不用填");
        assertTrue(candidate.standable(), "岸上站得住");
        assertTrue(candidate.remainingSources() >= PortalCastingLayout.MIN_REMAINING_SOURCES);
    }

    @Test
    void curvedShoreListsThePlatformCellsToFillAndDoesNotCountThemAsReserve() {
        Map<BlockPos, BlockState> world = CastingFakes.straightShore(LAYOUT, 2);
        // 岸是弯的：站台右后角两格还是岩浆，要填掉；填掉的源格不算余量。
        List<BlockPos> bay = List.of(LAYOUT.cell(3, 0, 1), LAYOUT.cell(3, 0, 2));
        for (BlockPos at : bay) world.put(at, Blocks.LAVA.defaultBlockState());
        LavaPoolSurvey.Verdict verdict = LavaPoolSurvey.judge(LAYOUT, readOf(world), at -> true, LAYOUT.cell(0, 1, 2));
        assertTrue(verdict instanceof LavaPoolSurvey.Verdict.Ok, verdict.toString());
        LavaPoolSurvey.Candidate candidate = ((LavaPoolSurvey.Verdict.Ok) verdict).candidate();
        assertEquals(new HashSet<>(bay), new HashSet<>(candidate.bankFill()));
        int enough = PortalCastingLayout.MIN_REMAINING_SOURCES;
        assertEquals(enough, LavaPoolSurvey.connectedSources(LAYOUT, readOf(world), Set.copyOf(bay), enough));
    }

    @Test
    void smallPoolIsRejectedForReserve() {
        Map<BlockPos, BlockState> world = CastingFakes.straightShore(LAYOUT, 1);
        // 池面只剩起手行周围八格岩浆，其余都是石头：不够浇一扇门。
        for (int across = -8; across <= 9; across++) {
            for (int behind = -8; behind <= 0; behind++) {
                BlockPos at = LAYOUT.cell(across, 0, behind);
                boolean keep = behind >= -1 && across >= -1 && across <= 2;
                if (!keep) world.put(at, Blocks.STONE.defaultBlockState());
            }
        }
        LavaPoolSurvey.Verdict verdict = LavaPoolSurvey.judge(LAYOUT, readOf(world), at -> true, LAYOUT.cell(0, 1, 2));
        assertTrue(verdict instanceof LavaPoolSurvey.Verdict.Rejected rejected
                && rejected.why() == LavaPoolSurvey.Rejection.RESERVE_SHORT, verdict.toString());
    }

    @Test
    void unloadedCellInTemplateIsNotTakenAsNoPool() {
        Map<BlockPos, BlockState> world = CastingFakes.straightShore(LAYOUT, 2);
        world.remove(LAYOUT.cell(2, 2, 1));
        LavaPoolSurvey.Verdict verdict = LavaPoolSurvey.judge(LAYOUT, readOf(world), at -> true, LAYOUT.cell(0, 1, 2));
        assertTrue(verdict instanceof LavaPoolSurvey.Verdict.Rejected rejected
                && rejected.why() == LavaPoolSurvey.Rejection.UNLOADED, verdict.toString());
        LavaPoolSurvey.Report report = LavaPoolSurvey.survey(List.of(LAYOUT.origin()), readOf(world), at -> true,
                LAYOUT.cell(0, 1, 2));
        assertTrue(report.describe().contains("没加载"), report.describe());
    }

    @Test
    void protectedCellRejectsTheTemplate() {
        Map<BlockPos, BlockState> world = CastingFakes.straightShore(LAYOUT, 2);
        BlockPos chest = LAYOUT.cell(1, 0, 3);
        LavaPoolSurvey.Verdict verdict = LavaPoolSurvey.judge(LAYOUT, readOf(world), at -> !at.equals(chest), LAYOUT.cell(0, 1, 2));
        assertTrue(verdict instanceof LavaPoolSurvey.Verdict.Rejected rejected
                && rejected.why() == LavaPoolSurvey.Rejection.PROTECTED, verdict.toString());
    }

    @Test
    void standableCandidatesComeFirstThenNearer() {
        Map<BlockPos, BlockState> world = CastingFakes.straightShore(LAYOUT, 2);
        // 同一池面，两套岸向都合规；把其中一套的站台上方堵成石头，它就没有站位，排到后面。
        PortalCastingLayout far = new PortalCastingLayout(LAYOUT.cell(4, 0, 0), Direction.NORTH);
        for (BlockPos floor : LAYOUT.bank()) {
            world.put(floor.above(), Blocks.STONE.defaultBlockState());
            world.put(floor.above(2), Blocks.STONE.defaultBlockState());
        }
        for (BlockPos at : LAYOUT.cellsToClear()) world.putIfAbsent(at, Blocks.AIR.defaultBlockState());
        List<BlockPos> seeds = new ArrayList<>(List.of(LAYOUT.origin(), far.origin()));
        LavaPoolSurvey.Report report = LavaPoolSurvey.survey(seeds, readOf(world), at -> true, LAYOUT.cell(0, 1, 2));
        assertFalse(report.candidates().isEmpty(), report.describe());
        LavaPoolSurvey.Candidate first = report.candidates().get(0);
        assertTrue(first.standable(), "有站位的排前：" + first);
        assertEquals(far.origin(), first.layout().origin());
    }

    @Test
    void halfCastStartRowIsStillRecognised() {
        Map<BlockPos, BlockState> world = CastingFakes.straightShore(LAYOUT, 2);
        // 上次浇了一半：两侧底脚与底行已是黑曜石，门内两格的岩浆被水替掉、水又收走了。
        for (BlockPos at : LAYOUT.startFrameCells()) world.put(at, Blocks.OBSIDIAN.defaultBlockState());
        for (BlockPos at : LAYOUT.startInterior()) world.put(at, Blocks.AIR.defaultBlockState());
        LavaPoolSurvey.Verdict verdict = LavaPoolSurvey.judge(LAYOUT, readOf(world), at -> true, LAYOUT.cell(0, 1, 2));
        assertTrue(verdict instanceof LavaPoolSurvey.Verdict.Ok, verdict.toString());
        Optional<LavaPoolSurvey.Candidate> fitting = LavaPoolSurvey.fitting(LAYOUT.frame(), readOf(world), at -> true,
                LAYOUT.cell(0, 1, 2));
        assertTrue(fitting.isPresent(), "残缺框认回同一套模板");
        assertEquals(LAYOUT.frame(), fitting.get().layout().frame());
    }
}
