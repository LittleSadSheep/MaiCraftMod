// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.lighting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 选灯位：围绕暗格找能放灯具的格，按"新增覆盖暗格最多"贪心挑出一批，平局挑离角色近的。
 * 纯函数：读现场走照明接缝，不控制角色、不发交互。
 *
 * <p>灯位资格：空气或可替换的草丛；支撑面结实（立式看脚下顶面，贴墙看墙面）；灯笼不选悬挂态；
 * 不是敏感面、不在保护里；不占只有两个以下出口的窄道；不放在角色脚下头上卡住自己。
 * 覆盖估算 = 发射量 − 曼哈顿距离 ≥ 验收亮度，只是估计（隔墙不传播），亮没亮以实测为准。
 */
public final class LampSiteChooser {

    /** 单轮最多选多少支：一批一批插，插完复测再看哪里还暗。 */
    public static final int MAX_PER_ROUND = 48;

    /** 围绕暗格找灯位的水平距离档与竖向幅度。 */
    private static final int[] DISTANCES = {1, 2, 4, 6};
    private static final int VERTICAL_SPAN = 2;

    /** 八个水平方向：斜向的暗格也照管得到。 */
    /** 围绕暗格找灯位的八个方向：四个正向加四个斜向。 */
    private static final Direction[][] RINGS = {
            {Direction.NORTH}, {Direction.SOUTH}, {Direction.EAST}, {Direction.WEST},
            {Direction.NORTH, Direction.EAST}, {Direction.NORTH, Direction.WEST},
            {Direction.SOUTH, Direction.EAST}, {Direction.SOUTH, Direction.WEST}};

    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    private LampSiteChooser() {}

    /** 一处选中的灯位：插在哪一格、放成什么样子。 */
    public record LampSite(BlockPos pos, BlockState state) {}

    /**
     * 一轮的选择：选中的灯位、没被任何灯盖住也没插上灯的暗格、候选全被保护闸拦下的暗格。
     * 后者单独列出：那些格要 LLM 同意才动得了，不是"没地方插"。
     */
    public record Selection(List<LampSite> chosen, Set<BlockPos> uncovered, Set<BlockPos> onlyProtected) {}

    /** 资格闸的结论：放得了；因保护放不了；因为别的原因放不了。 */
    private enum Verdict { QUALIFIED, PROTECTED, OTHER }

    /** 一个候选灯位：位置、放成什么样子、能盖住的暗格。 */
    private record Candidate(BlockPos pos, BlockState state, Set<BlockPos> covers) {}

    /**
     * 在暗格里挑这一轮的灯位。
     *
     * @param dark        还暗着的样本格
     * @param world       现场读数
     * @param sensitive   采样时记下的敏感面（不落灯）
     * @param protectedAt 这一格是否不许动（许可与保护判断）
     * @param near        角色位置：平局挑近的，也避开自己的脚下与头顶
     * @param lamp        这次用的光源
     * @param threshold   验收亮度
     */
    public static Selection choose(List<BlockPos> dark, LightingWorld world, Set<BlockPos> sensitive,
                                   Predicate<BlockPos> protectedAt, BlockPos near, LampItem lamp, int threshold) {
        Map<BlockPos, Candidate> candidates = new HashMap<>();
        // 每个暗格记它的候选命运，最后如实分组"没处插"与"插不得"。
        Map<BlockPos, Trace> traces = new HashMap<>();
        for (BlockPos cell : dark) {
            Trace trace = traces.computeIfAbsent(cell, ignored -> new Trace());
            for (BlockPos at : spotsAround(cell)) {
                Form form = formOf(at, world);
                Verdict verdict = verdictOf(at, form, world, sensitive, protectedAt, near);
                trace.generated++;
                if (verdict == Verdict.PROTECTED) trace.protectedOnly++;
                if (verdict != Verdict.QUALIFIED) continue;
                trace.qualified++;
                Candidate candidate = candidates.computeIfAbsent(at,
                        ignored -> new Candidate(at, stateOf(at, form, lamp), new HashSet<>()));
                candidate.covers.add(cell);
            }
        }
        // 覆盖按全部暗格算：一个候选常常被好几个暗格共同够到，只记生出它的那格会选偏。
        for (Candidate candidate : candidates.values()) {
            candidate.covers.clear();
            for (BlockPos cell : dark) {
                if (lamp.emission() - manhattan(candidate.pos(), cell) >= threshold) {
                    candidate.covers.add(cell);
                }
            }
        }
        List<LampSite> chosen = new ArrayList<>();
        Set<BlockPos> remaining = new LinkedHashSet<>(dark);
        while (chosen.size() < MAX_PER_ROUND && !remaining.isEmpty()) {
            Candidate best = null;
            int bestGain = 0;
            double bestDistance = Double.MAX_VALUE;
            for (Candidate candidate : candidates.values()) {
                int gain = 0;
                for (BlockPos cell : candidate.covers()) {
                    if (remaining.contains(cell)) gain++;
                }
                if (gain == 0) continue;
                double distance = candidate.pos().distSqr(near);
                if (gain > bestGain || (gain == bestGain && distance < bestDistance)) {
                    best = candidate;
                    bestGain = gain;
                    bestDistance = distance;
                }
            }
            if (best == null) break;
            chosen.add(new LampSite(best.pos(), best.state()));
            remaining.removeAll(best.covers());
        }
        Set<BlockPos> onlyProtected = new LinkedHashSet<>();
        for (BlockPos cell : remaining) {
            Trace trace = traces.get(cell);

            // 生出过候选但全部卡在保护闸上的暗格，与"压根没生出候选"的分开说。
            // 有候选本来放得下（只是卡在保护上）、又没有一个能用的：这格是"插不得"。
            // 周围一格能站的都没有的（比如整面是墙根），不算插不得，算没处插。
            if (trace != null && trace.qualified == 0 && trace.protectedOnly > 0) {
                onlyProtected.add(cell);
            }
        }
        return new Selection(chosen, remaining, onlyProtected);
    }

    /** 围绕一个暗格的候选位置：八个方向、几档水平距离、竖向上下各两格。 */
    private static List<BlockPos> spotsAround(BlockPos cell) {
        List<BlockPos> spots = new ArrayList<>(RINGS.length * DISTANCES.length * (VERTICAL_SPAN * 2 + 1));
        for (Direction[] ring : RINGS) {
            int dx = 0;
            int dz = 0;
            for (Direction direction : ring) {
                dx += direction.getStepX();
                dz += direction.getStepZ();
            }
            for (int distance : DISTANCES) {
                for (int dy = -VERTICAL_SPAN; dy <= VERTICAL_SPAN; dy++) {
                    spots.add(cell.offset(dx * distance, dy, dz * distance));
                }
            }
        }
        return spots;
    }

    /** 候选能放成什么样子：立式（脚下结实）与贴墙（哪个水平方向的邻面结实）；都放不了两个都空。 */
    private record Form(boolean standing, Direction wallSide) {}

    private static Form formOf(BlockPos at, LightingWorld world) {
        boolean standing = world.sturdy(at.below(), Direction.UP);
        for (Direction direction : HORIZONTAL) {
            BlockPos support = at.offset(direction.getStepX(), 0, direction.getStepZ());
            // 墙在候选格的这一侧：墙面朝着候选格才贴得上。
            if (world.sturdy(support, direction.getOpposite())) {
                return new Form(standing, direction);
            }
        }
        return new Form(standing, null);
    }

    /** 资格闸：没加载、不是空气草丛、敏感面、受保护、窄道、卡住自己、两种形态都放不了，都过不去。 */
    private static Verdict verdictOf(BlockPos at, Form form, LightingWorld world, Set<BlockPos> sensitive,
                                     Predicate<BlockPos> protectedAt, BlockPos near) {
        if (!world.loaded(at) || !SampleRules.passable(world.state(at))) return Verdict.OTHER;
        if (sensitive.contains(at) || SampleRules.sensitive(world.state(at))) return Verdict.OTHER;
        if (protectedAt.test(at)) return Verdict.PROTECTED;
        if (at.equals(near) || at.equals(near.above())) return Verdict.OTHER;
        // 灯占的是整格的立式才谈挡路：贴墙火把没有碰撞，不挡窄道。
        if (form.standing() && exitsAround(at, world) <= 2) return Verdict.OTHER;
        if (!form.standing() && form.wallSide() == null) return Verdict.OTHER;
        return Verdict.QUALIFIED;
    }

    /** 候选格里放成什么状态：有贴墙形态优先贴墙，否则立式；灯笼只有立式（不选悬挂态）。 */
    private static BlockState stateOf(BlockPos at, Form form, LampItem lamp) {
        if (form.wallSide() != null && lamp.wall() != null) {
            // wallSide 指向墙：火把指向离开墙的那一边。
            return lamp.wall().setValue(WallTorchBlock.FACING, form.wallSide().getOpposite());
        }
        if (form.standing() && lamp.standing() != null) {
            BlockState standing = lamp.standing();
            return standing.hasProperty(LanternBlock.HANGING)
                    ? standing.setValue(LanternBlock.HANGING, false) : standing;
        }
        throw new IllegalStateException("候选 " + at.toShortString() + " 过了资格闸却没有落法");
    }

    /** 候选格的水平通行出口数：四个方向上脚与头都能占的邻格。 */
    private static int exitsAround(BlockPos at, LightingWorld world) {
        int exits = 0;
        for (Direction direction : HORIZONTAL) {
            BlockPos side = at.offset(direction.getStepX(), 0, direction.getStepZ());
            if (world.loaded(side) && SampleRules.passable(world.state(side))
                    && SampleRules.passable(world.state(side.above()))) {
                exits++;
            }
        }
        return exits;
    }

    private static int manhattan(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
    }

    /** 一个暗格的候选命运计数：生出过几个、过了资格闸几个、其中多少卡在保护上。 */
    private static final class Trace {
        int generated;
        int qualified;
        int protectedOnly;
    }
}
