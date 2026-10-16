// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.suicide;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.game.world.LavaFlow;

/**
 * 随身造危险的选格：用身上的打火石、火焰弹点火，或倒岩浆桶，在身边挑一格站进去。
 *
 * <p>身边水平 8 格（不超过找死法的半径）、上下 2 格里从近到远看：格子是空气（或已经是同样的火、岩浆），
 * 头上一格进得去，脚下顶面结实能托住火与岩浆，许可让在这一格放方块。不烧房子、不淹基地：
 * 火会向四周 1 格、向下 1 格、向上 4 格蔓延；岩浆在普通维度流 3 格、下界流 7 格，流经处还会点着 3 格内的可燃物，
 * 这片范围里有没加载的格、可燃的方块或受保护的方块就换下一格。
 *
 * <p>岩浆的安全范围在下界一格就要看两千多格方块，所以分刻看：每刻读到上限就停，下一刻接着看。纯判断，只读 {@link SuicideWorld}。
 */
final class CarriedHazards {

    /** 身边找格的水平距离（格）：走几步就到，不为点把火跑远。 */
    static final int REACH = 8;
    /** 身边找格的上下距离（格）。 */
    static final int VERTICAL = 2;
    /** 每刻最多读多少格方块：超过就留到下一刻，不让一次选格卡住游戏。 */
    static final int READS_PER_TICK = 8192;
    /** 类别 F：原版的火向四周蔓延 1 格。 */
    static final int FIRE_SPREAD = 1;
    /** 类别 F：岩浆在普通维度流 3 格；流距这条事实只在岩浆流路里写一份。 */
    static final int LAVA_FLOW = LavaFlow.REACH;
    /** 类别 F：岩浆在超热维度（下界）流 7 格。 */
    static final int LAVA_FLOW_HOT = LavaFlow.REACH_HOT;
    /** 类别 F：岩浆流经处会点着 3 格内的可燃物。 */
    static final int LAVA_IGNITES = 3;
    /** 类别 F：火与岩浆往下烧 1 格、往上烧 4 格。 */
    static final int BELOW = 1;
    static final int ABOVE = 4;

    /** 看一段的结论。 */
    sealed interface Step {
        /** 挑中这一格。 */
        record Found(BlockPos cell) implements Step {}

        /** 这一刻读到上限还没看完，下一刻接着看。 */
        record Searching() implements Step {}

        /** 全看完了，一格都用不了。 */
        record Exhausted() implements Step {}
    }

    private final SuicideWorld world;
    private final WayToDie way;
    private final List<BlockPos> cells;
    private int cursor;

    /**
     * @param feet   以哪一格为中心找（角色此刻的脚位）
     * @param radius 找死法的半径：水平不超过它
     */
    CarriedHazards(SuicideWorld world, WayToDie way, BlockPos feet, int radius) {
        if (!way.carried()) throw new IllegalArgumentException("只有随身造危险的死法要选格：" + way);
        this.world = Objects.requireNonNull(world, "world");
        this.way = way;
        int reach = Math.min(REACH, radius);
        List<BlockPos> around = new ArrayList<>();
        for (BlockPos cell : BlockPos.betweenClosed(feet.offset(-reach, -VERTICAL, -reach),
                feet.offset(reach, VERTICAL, reach))) {
            around.add(cell.immutable());
        }
        // 从近到远：脚下这一格最近，能用就原地点。
        around.sort(Comparator.comparingDouble(cell -> cell.distSqr(feet)));
        this.cells = List.copyOf(around);
    }

    WayToDie way() {
        return way;
    }

    /** 接着看一段；放弃过的格子（按死法与格子记的记号）跳过。 */
    Step step(Set<String> abandoned) {
        int reads = 0;
        while (cursor < cells.size()) {
            if (reads >= READS_PER_TICK) return new Step.Searching();
            BlockPos cell = cells.get(cursor++);
            if (abandoned.contains(DeathSpot.at(way, cell, cell).key())) continue;
            Check check = check(world, way, cell);
            reads += check.reads;
            if (check.fits) return new Step.Found(cell);
        }
        return new Step.Exhausted();
    }

    /** 这一格此刻还能不能用来点火、倒岩浆；走过去、站进去之前再看一眼。 */
    static boolean fits(SuicideWorld world, WayToDie way, BlockPos cell) {
        return check(world, way, cell).fits;
    }

    private record Check(boolean fits, int reads) {}

    // 先看格子本身，再看安全范围；任何一格不行就停，不把整片都读完。
    private static Check check(SuicideWorld world, WayToDie way, BlockPos cell) {
        if (!(world.air(cell) || alreadyBurning(world, way, cell)) || !world.passable(cell.above())
                || !world.loaded(cell.below()) || !world.sturdyTop(cell.below()) || !world.mayPlaceAt(cell)) {
            return new Check(false, 5);
        }
        int spread = way == WayToDie.FIRE ? FIRE_SPREAD
                : (world.hotDimension() ? LAVA_FLOW_HOT : LAVA_FLOW) + LAVA_IGNITES;
        int reads = 5;
        for (BlockPos near : BlockPos.betweenClosed(cell.offset(-spread, -BELOW, -spread),
                cell.offset(spread, ABOVE, spread))) {
            reads++;
            if (!world.loaded(near)) return new Check(false, reads);
            if (world.air(near) || near.equals(cell)) continue;
            if (world.flammable(near) || world.protectedBlock(near)) return new Check(false, reads);
        }
        return new Check(true, reads);
    }

    // 格里已经有同样的危险（上一次点的火还在、岩浆还在）也能直接站进去。
    private static boolean alreadyBurning(SuicideWorld world, WayToDie way, BlockPos cell) {
        return way == WayToDie.FIRE ? world.fireAt(cell) : world.lava(cell);
    }
}
