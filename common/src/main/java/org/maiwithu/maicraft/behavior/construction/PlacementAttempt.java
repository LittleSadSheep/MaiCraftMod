// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 放一格的一次尝试：这一格能从哪几面点（先直接点目标格里可替换的东西，再按先下、四周、上的顺序点邻格的支撑面），
 * 现在试到第几面、预测到的放法、点了几下。换一面就是换一个站位重来。
 */
final class PlacementAttempt {

    /** 一个候选：点哪一格的哪一面；direct 表示点的就是目标格里现有的东西（水、草、半砖的第一片）。 */
    record Candidate(BlockPos clicked, Direction face, boolean direct) {}

    final PlannedCell cell;
    final boolean temporary;
    private final List<Candidate> candidates;
    private int index = -1;
    PlacementPrediction.Placement placement;
    PlacementConfirmation confirmation;
    int uses;

    PlacementAttempt(PlannedCell cell, boolean temporary, List<Candidate> candidates) {
        this.cell = cell;
        this.temporary = temporary;
        this.candidates = List.copyOf(candidates);
    }

    /** 从现场看这一格能从哪几面点。 */
    static List<Candidate> candidates(ReadsBlocks world, PlannedCell cell) {
        List<Candidate> out = new ArrayList<>();
        if (world.loaded(cell.pos())) {
            BlockState live = world.state(cell.pos());
            // 目标格里是水、草这类可替换的东西，或同类可叠加的方块（半砖的第一片），直接点它。
            if (!live.isAir() && (live.canBeReplaced() || live.is(cell.state().getBlock()) && PlacementPrediction.maximumUses(cell) > 1)) {
                out.add(new Candidate(cell.pos(), Direction.UP, true));
            }
        }
        for (Direction toward : PlacementPrediction.SUPPORT_ORDER) {
            BlockPos clicked = cell.pos().relative(toward);
            if (world.loaded(clicked) && TemporaryBlocks.solid(world, clicked)) out.add(new Candidate(clicked, toward.getOpposite(), false));
        }
        return out;
    }

    boolean hasNext() {
        return index + 1 < candidates.size();
    }

    Candidate next() {
        index++;
        placement = null;
        confirmation = null;
        return candidates.get(index);
    }

    Candidate current() {
        return index < 0 ? null : candidates.get(index);
    }

    int candidateCount() {
        return candidates.size();
    }

    /** 放下这一格会占住的格：目标格和它的另一半；站位的脚下与头顶都要避开。 */
    Set<BlockPos> occupied() {
        Set<BlockPos> cells = new HashSet<>();
        cells.add(cell.pos());
        for (var effect : PlacementPrediction.generatedBy(cell.pos(), cell.state())) cells.add(effect.pos());
        return cells;
    }
}
