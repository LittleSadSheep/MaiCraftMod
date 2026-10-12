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
 *
 * <p>对着放的那一格（placeAt）多数就是目标格；联动的放置规则说要对着别的格放时（动力辊会被下面的实心面抬高一格），
 * 候选面围着 placeAt 找，原版以为在放 placeAt，落下来的是目标格。
 */
final class PlacementAttempt {

    /** 一个候选：点哪一格的哪一面；direct 表示点的就是目标格里现有的东西（水、草、半砖的第一片）。 */
    record Candidate(BlockPos clicked, Direction face, boolean direct) {}

    final PlannedCell cell;
    final BlockPos placeAt;
    final boolean temporary;
    private final List<Candidate> candidates;
    private int index = -1;
    PlacementPrediction.Placement placement;
    PlacementConfirmation confirmation;
    int uses;
    /** 原版放出来朝向不对、能拿扳手转到位：照放，放下后转。 */
    boolean turnAfter;
    /** 还能拿扳手点几下。 */
    int turnsLeft = 4;

    PlacementAttempt(PlannedCell cell, BlockPos placeAt, boolean temporary, List<Candidate> candidates) {
        this.cell = cell;
        this.placeAt = placeAt.immutable();
        this.temporary = temporary;
        this.candidates = List.copyOf(candidates);
    }

    /** 从现场看对着 placeAt 放这一格能从哪几面点：围着 placeAt 找。 */
    static List<Candidate> candidates(ReadsBlocks world, PlannedCell cell, BlockPos placeAt) {
        List<Candidate> out = new ArrayList<>();
        if (world.loaded(placeAt)) {
            BlockState live = world.state(placeAt);
            // 那一格里是水、草这类可替换的东西，或同类可叠加的方块（半砖的第一片），直接点它。
            boolean stacking = placeAt.equals(cell.pos()) && live.is(cell.state().getBlock()) && PlacementPrediction.maximumUses(cell) > 1;
            if (!live.isAir() && (live.canBeReplaced() || stacking)) {
                out.add(new Candidate(placeAt, Direction.UP, true));
            }
        }
        for (Direction toward : PlacementPrediction.SUPPORT_ORDER) {
            BlockPos clicked = placeAt.relative(toward);
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

    /** 放下这一格会占住的格：目标格、对着放的那一格和它的另一半；站位的脚下与头顶都要避开。 */
    Set<BlockPos> occupied() {
        Set<BlockPos> cells = new HashSet<>();
        cells.add(cell.pos());
        cells.add(placeAt);
        for (var effect : PlacementPrediction.generatedBy(cell.pos(), cell.state())) cells.add(effect.pos());
        return cells;
    }
}
