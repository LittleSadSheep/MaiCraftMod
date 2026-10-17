// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import baritone.api.utils.PathCalculationResult;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.Permissions.BlockChanges;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 不许挖时走不通的只读复算怎么写进说明：有路就列出要挖、要垫的每一格并建议放宽到哪一档，
 * 没路如实说复算也没找到，没复算过的问题原样不动。
 */
class TerrainProbeTest {

    private static final Problem NO_ROUTE = Problem.of(Problem.Kind.UNREACHABLE, "没有能走到目标的路线", null);

    private static TerrainProbe.Verdict reached(Map<String, List<BlockPos>> breaks, Set<BlockPos> places,
                                                Set<String> outside) {
        return new TerrainProbe.Verdict(PathCalculationResult.Type.SUCCESS_TO_GOAL, breaks, places, outside);
    }

    @Test
    void aRouteThatDigsListsEveryCellAndSuggestsNatural() {
        Map<String, List<BlockPos>> breaks = new LinkedHashMap<>();
        breaks.put("minecraft:stone", List.of(new BlockPos(1, 64, 2), new BlockPos(1, 65, 2)));
        Set<BlockPos> places = new LinkedHashSet<>(List.of(new BlockPos(3, 63, 2)));
        Problem explained = TerrainProbe.explain(NO_ROUTE, reached(breaks, places, Set.of()), null,
                BlockChanges.NONE, true, 0);
        assertEquals(Problem.Kind.UNREACHABLE, explained.kind());
        assertTrue(explained.message().contains("要挖 2 格：minecraft:stone×2 (1, 64, 2)、(1, 65, 2)"),
                explained.message());
        assertTrue(explained.message().contains("要垫 1 格：(3, 63, 2)"), explained.message());
        assertTrue(explained.message().endsWith("这次什么都没动"), explained.message());
        assertTrue(explained.suggestion().contains("提到 natural"), explained.suggestion());
    }

    @Test
    void digsOutsideTheWhitelistSuggestAnyAndMissingScaffoldIsCalledOut() {
        Map<String, List<BlockPos>> breaks = Map.of("minecraft:oak_planks", List.of(new BlockPos(0, 70, 0)));
        Set<BlockPos> places = Set.of(new BlockPos(0, 69, 1));
        Problem explained = TerrainProbe.explain(NO_ROUTE, reached(breaks, places, Set.of("minecraft:oak_planks")),
                null, BlockChanges.TEMPORARY, false, 0);
        assertTrue(explained.message().contains("minecraft:oak_planks×1（不在可挖名单）"), explained.message());
        assertTrue(explained.suggestion().contains("提到 any"), explained.suggestion());
        assertTrue(explained.suggestion().contains("垫脚料"), explained.suggestion());
    }

    @Test
    void placingOnlyUnderNoneSuggestsTemporary_underTemporaryAsksForScaffold() {
        Set<BlockPos> places = Set.of(new BlockPos(5, 64, 5));
        var verdict = reached(Map.of(), places, Set.of());
        assertTrue(TerrainProbe.explain(NO_ROUTE, verdict, null, BlockChanges.NONE, true, 0)
                .suggestion().contains("提到 temporary"));
        assertTrue(TerrainProbe.explain(NO_ROUTE, verdict, null, BlockChanges.TEMPORARY, false, 0)
                .suggestion().startsWith("身上没有能垫的料"));
    }

    @Test
    void anUnreachedProbeOrNoProbeKeepsTheOriginalSuggestion() {
        var failed = new TerrainProbe.Verdict(PathCalculationResult.Type.FAILURE, Map.of(), Set.of(), Set.of());
        Problem explained = TerrainProbe.explain(NO_ROUTE, failed, null, BlockChanges.NONE, true, 0);
        assertTrue(explained.message().contains("就算允许挖开、垫方块，只读复算也找不到到目标的路"), explained.message());
        assertEquals(null, explained.suggestion());
        assertSame(NO_ROUTE, TerrainProbe.explain(NO_ROUTE, null, null, BlockChanges.NONE, true, 0));
        assertTrue(TerrainProbe.explain(NO_ROUTE, null, "等了 15 秒没算完", BlockChanges.NONE, true, 0)
                .message().endsWith("没给出结论：等了 15 秒没算完"));
    }
}
