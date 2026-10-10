// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 工地的账：蓝图里每一格现在是什么结局、哪些格不碰（受保护、做不了、交给机器）、没做成的按原因分组、
 * 用掉了什么材料。施工任务每做一步在这里记一笔，结束时按它出结果；不动世界。
 */
final class ConstructionSite {

    private final Blueprint blueprint;
    private final Map<BlockPos, CellEnding> endings = new LinkedHashMap<>();
    private final Map<String, List<BlockPos>> problems = new LinkedHashMap<>();
    private final Map<String, Integer> materialsUsed = new LinkedHashMap<>();
    /** 这次不再碰的格：受保护、做不了、够不着、交给机器。 */
    private final Set<BlockPos> excluded = new HashSet<>();

    ConstructionSite(Blueprint blueprint) {
        this.blueprint = blueprint;
        for (PlannedCell cell : blueprint.cells()) endings.put(cell.pos(), CellEnding.UNKNOWN);
    }

    Blueprint blueprint() {
        return blueprint;
    }

    /** 对图核一遍，更新每格的结局；这次放过、清过、倒过的仍记原来的结局。返回没加载的格。 */
    List<BlockPos> check(ReadsBlocks world) {
        BlueprintCheck.Result result = BlueprintCheck.compare(blueprint, world, Set.of());
        List<BlockPos> unloaded = new ArrayList<>();
        for (PlannedCell cell : blueprint.cells()) {
            CellState state = result.states().get(cell.pos());
            if (state == CellState.UNKNOWN) unloaded.add(cell.pos());
            if (excluded.contains(cell.pos())) continue;
            CellEnding prior = endings.get(cell.pos());
            endings.put(cell.pos(), switch (state) {
                case MATCHES -> prior == CellEnding.PLACED || prior == CellEnding.CLEARED || prior == CellEnding.POURED ? prior : CellEnding.MATCHES;
                case WRONG_BLOCK -> CellEnding.WRONG_BLOCK;
                case WRONG_STATE -> CellEnding.WRONG_STATE;
                case MISSING -> CellEnding.MISSING;
                case UNKNOWN -> CellEnding.UNKNOWN;
                case CHECKED_BY_MACHINE -> {
                    excluded.add(cell.pos());
                    yield CellEnding.CHECKED_BY_MACHINE;
                }
                case EXTRA -> prior;
            });
        }
        return unloaded;
    }

    /** 这一格这次不碰了：受保护或做不了，记原因。 */
    void exclude(BlockPos pos, CellEnding ending, String reason) {
        excluded.add(pos);
        endings.put(pos, ending);
        problem(reason, pos);
    }

    /** 够不着也垫不了：结局照核对的记，原因记一笔，这次不再回头。 */
    void unreachable(BlockPos pos, String reason) {
        excluded.add(pos);
        problem(reason, pos);
    }

    boolean needsWork(BlockPos pos) {
        CellEnding ending = endings.get(pos);
        return !excluded.contains(pos)
                && (ending == CellEnding.WRONG_BLOCK || ending == CellEnding.WRONG_STATE || ending == CellEnding.MISSING);
    }

    CellEnding ending(BlockPos pos) {
        return endings.get(pos);
    }

    /**
     * 要先清掉的格：要求清空的格里有东西；放方块的格里是别的、不可替换的方块，或方块对了但点名的属性不对
     * （要拆了重放）。从上往下清，上面的先掉。
     */
    List<PlannedCell> pendingClear(ReadsBlocks world) {
        List<PlannedCell> out = new ArrayList<>();
        for (PlannedCell cell : blueprint.cells()) {
            if (!needsWork(cell.pos()) || !world.loaded(cell.pos())) continue;
            BlockState live = world.state(cell.pos());
            if (live.isAir() || live.getBlock() instanceof LiquidBlock) continue;
            if (cell.kind() == CellKind.AIR || !live.canBeReplaced() && !PlacementPrediction.isProgress(cell, Blocks.AIR.defaultBlockState(), live)) out.add(cell);
        }
        out.sort(Comparator.comparingInt((PlannedCell cell) -> -cell.pos().getY())
                .thenComparingInt(cell -> cell.pos().getZ()).thenComparingInt(cell -> cell.pos().getX()));
        return out;
    }

    /** 要放的格：放方块的格里还缺东西；门上半、床头跟着主格一起出来，不单独放。低层先，同层骨架先。 */
    List<PlannedCell> pendingPlace() {
        List<PlannedCell> out = new ArrayList<>();
        for (PlannedCell cell : blueprint.cells()) {
            if (cell.kind() != CellKind.BLOCK || !needsWork(cell.pos()) || BlockStateRules.isSecondaryHalf(cell.state())) continue;
            out.add(cell);
        }
        out.sort(BuildOrder.LOW_TO_HIGH);
        return out;
    }

    /** 要倒的格。 */
    List<PlannedCell> pendingPour() {
        List<PlannedCell> out = new ArrayList<>();
        for (PlannedCell cell : blueprint.cells()) {
            if (cell.kind() == CellKind.FLUID_SOURCE && needsWork(cell.pos())) out.add(cell);
        }
        out.sort(BuildOrder.LOW_TO_HIGH);
        return out;
    }

    /** 声明范围内不该在的源液体：要清空或要放方块的格里现在是源液体，用空桶收走。 */
    List<BlockPos> straySources(ReadsBlocks world) {
        List<BlockPos> out = new ArrayList<>();
        for (PlannedCell cell : blueprint.cells()) {
            if (cell.kind() == CellKind.FLUID_SOURCE || !needsWork(cell.pos()) || !world.loaded(cell.pos())) continue;
            BlockState live = world.state(cell.pos());
            if (live.getBlock() instanceof LiquidBlock && live.getFluidState().isSource()) out.add(cell.pos());
        }
        return out;
    }

    /** 还缺什么料：按没做成的格算，减去身上有的。 */
    Map<String, Integer> missingMaterials(ConstructionSeams.ReadsSite site) {
        List<PlannedCell> pending = new ArrayList<>(pendingPlace());
        pending.addAll(pendingPour());
        Map<String, Integer> missing = new LinkedHashMap<>();
        if (pending.isEmpty()) return missing;
        Blueprint.materialsOf(pending).forEach((itemId, wanted) -> {
            int lacking = wanted - site.carried(itemId);
            if (lacking > 0) missing.put(itemId, lacking);
        });
        return missing;
    }

    void placed(PlannedCell cell, int consumed) {
        endings.put(cell.pos(), CellEnding.PLACED);
        for (var effect : PlacementPrediction.generatedBy(cell.pos(), cell.state())) {
            if (endings.containsKey(effect.pos()) && !excluded.contains(effect.pos())) endings.put(effect.pos(), CellEnding.PLACED);
        }
        if (consumed > 0) materialsUsed.merge(BuiltInRegistries.ITEM.getKey(cell.item()).toString(), consumed, Integer::sum);
    }

    /** 清掉了：要求清空的格就算对了；要放方块的格接着算缺。 */
    void cleared(PlannedCell cell) {
        endings.put(cell.pos(), cell.kind() == CellKind.AIR ? CellEnding.CLEARED : CellEnding.MISSING);
    }

    void poured(PlannedCell cell) {
        endings.put(cell.pos(), CellEnding.POURED);
        materialsUsed.merge(BuiltInRegistries.ITEM.getKey(cell.item()).toString(), 1, Integer::sum);
    }

    void problem(String reason, BlockPos pos) {
        problems.computeIfAbsent(reason, ignored -> new ArrayList<>()).add(pos.immutable());
    }

    /** 全部格都对了（或这次放好、清好、倒好、交给机器）。 */
    boolean allSettled() {
        for (CellEnding ending : endings.values()) {
            switch (ending) {
                case MATCHES, PLACED, CLEARED, POURED, CHECKED_BY_MACHINE -> { }
                default -> {
                    return false;
                }
            }
        }
        return true;
    }

    /** 还有几格没做成（不算交给机器的、受保护的）。 */
    int unsettled() {
        int count = 0;
        for (CellEnding ending : endings.values()) {
            if (ending == CellEnding.WRONG_BLOCK || ending == CellEnding.WRONG_STATE || ending == CellEnding.MISSING
                    || ending == CellEnding.UNKNOWN) count++;
        }
        return count;
    }

    Map<String, Integer> counts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CellEnding ending : CellEnding.values()) counts.put(ending.key(), 0);
        for (CellEnding ending : endings.values()) counts.merge(ending.key(), 1, Integer::sum);
        return counts;
    }

    Map<String, Integer> materialsUsed() {
        return Map.copyOf(materialsUsed);
    }

    List<ConstructionDetails.ProblemGroup> problemGroups() {
        List<ConstructionDetails.ProblemGroup> groups = new ArrayList<>();
        problems.forEach((reason, cells) -> groups.add(new ConstructionDetails.ProblemGroup(reason, cells.size(), cells)));
        return groups;
    }

    /** 受保护的格有几格。 */
    int protectedCount() {
        int count = 0;
        for (CellEnding ending : endings.values()) if (ending == CellEnding.PROTECTED) count++;
        return count;
    }

    ConstructionDetails details(String dimension, List<BlockPos> temporariesLeft) {
        Blueprint.Bounds bounds = blueprint.bounds();
        BlockPos anchor = blueprint.anchor();
        return new ConstructionDetails(new WorldPosition(anchor.getX(), anchor.getY(), anchor.getZ(), dimension),
                bounds.min(), bounds.max(), counts(), problemGroups(), materialsUsed(), temporariesLeft);
    }

    /** 蓝图里声明的全部格位置：垫临时方块时避开它们（那些格本来就要有方块或要空着）。 */
    Set<BlockPos> declared() {
        return endings.keySet();
    }
}
