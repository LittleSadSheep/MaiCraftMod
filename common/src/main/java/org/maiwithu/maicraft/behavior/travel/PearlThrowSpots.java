// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 对着折跃门扔珍珠的站位：门洞只有一格高，上下是基岩，只有四个侧面敞着，珍珠要从侧面飞进去。
 * 所以站在门旁 2 到 4 格、脚比门洞低 0 到 2 格，眼睛到门洞侧面中心一路都是空气、伸手够得着，才扔得进去。
 * 脚下本来就站得住的优先、离人近的优先；门悬在半空没有现成落脚处时，许可允许垫方块的话也给出要垫一块才站得住的脚位，
 * 由走到垫柱、搭桥上去。纯函数：世界只经读格的函数问，读不到的格不算数。
 */
public final class PearlThrowSpots {

    /** 离门最近、最远几格（水平，按较大的那个轴算）：贴着门框没法从侧面扔，再远就够不着。 */
    static final int NEAREST = 2;
    static final int FARTHEST = 4;
    /** 脚比门洞低几格到几格：眼睛高 1.62 格，脚低一格时正好平视门洞。 */
    static final int LOWEST = -2;
    static final int HIGHEST = 0;
    /** 伸手够得着的距离：原版方块交互距离 4.5 格，留一点余量。 */
    static final double REACH = 4.25;
    /** 眼睛离脚底的高度。 */
    static final double EYE_HEIGHT = 1.62;
    /** 沿视线每隔这么远看一格有没有挡着。 */
    private static final double SIGHT_STEP = 0.05;
    /** 最多给走到这么多个脚位：再多寻路也不会挑得更好。 */
    static final int MAX_SPOTS = 12;

    private PearlThrowSpots() {}

    /**
     * 一个能扔的脚位。
     *
     * @param feet          脚站的那一格
     * @param standsAlready 脚下本来就踩得住；为假时要先垫一块
     */
    public record Spot(BlockPos feet, boolean standsAlready) {}

    /**
     * 门旁能对着门洞扔珍珠的脚位，现成站得住的在前，再按离人远近排。
     *
     * @param gate     折跃门那一格
     * @param read     按格读方块，没加载的格给空
     * @param mayPlace 许可允许垫方块：门悬在半空时也给要垫一块才站得住的脚位
     * @param from     人现在脚下，用来排远近
     * @param skipped  这一趟已经试过不行的脚位（站上去还是看不见门洞），不再给
     */
    public static List<Spot> around(BlockPos gate, Function<BlockPos, Optional<BlockState>> read, boolean mayPlace,
            BlockPos from, Set<BlockPos> skipped) {
        List<Spot> spots = new ArrayList<>();
        for (int dx = -FARTHEST; dx <= FARTHEST; dx++) {
            for (int dz = -FARTHEST; dz <= FARTHEST; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) < NEAREST) continue;
                for (int dy = LOWEST; dy <= HIGHEST; dy++) {
                    BlockPos feet = gate.offset(dx, dy, dz);
                    if (skipped.contains(feet)) continue;
                    spotAt(gate, feet, read).filter(spot -> mayPlace || spot.standsAlready()).ifPresent(spots::add);
                }
            }
        }
        // 现成站得住的先；同样站得住的离人近的先，再挑眼睛平着门洞的（脚低一格）。
        spots.sort(Comparator.comparing((Spot spot) -> !spot.standsAlready())
                .thenComparingDouble(spot -> spot.feet().distSqr(from))
                .thenComparingInt(spot -> Math.abs(spot.feet().getY() - (gate.getY() - 1))));
        return spots.size() > MAX_SPOTS ? List.copyOf(spots.subList(0, MAX_SPOTS)) : List.copyOf(spots);
    }

    /** 这一个脚位扔不扔得进：身子放得下、够得着门洞侧面、视线一路是空气；脚下有没有东西托着另记。 */
    static Optional<Spot> spotAt(BlockPos gate, BlockPos feet, Function<BlockPos, Optional<BlockState>> read) {
        if (!passable(read.apply(feet)) || !passable(read.apply(feet.above()))) return Optional.empty();
        Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + EYE_HEIGHT, feet.getZ() + 0.5);
        Vec3 aim = sideCenter(gate, eye);
        if (eye.distanceTo(aim) > REACH || !clearSight(eye, aim, gate, read)) return Optional.empty();
        Optional<BlockState> floor = read.apply(feet.below());
        boolean stands = floor.isPresent()
                && floor.get().isFaceSturdy(EmptyBlockGetter.INSTANCE, feet.below(), Direction.UP);
        return Optional.of(new Spot(feet, stands));
    }

    /** 门洞朝着眼睛的那个侧面的中心：东西与南北两个轴里，离眼睛差得多的那个轴决定是哪一面。 */
    static Vec3 sideCenter(BlockPos gate, Vec3 eye) {
        double cx = gate.getX() + 0.5;
        double cz = gate.getZ() + 0.5;
        double y = gate.getY() + 0.5;
        if (Math.abs(eye.x - cx) >= Math.abs(eye.z - cz)) {
            return new Vec3(eye.x > cx ? gate.getX() + 1.0 : gate.getX(), y, cz);
        }
        return new Vec3(cx, y, eye.z > cz ? gate.getZ() + 1.0 : gate.getZ());
    }

    // 从眼睛到门洞侧面沿线逐点看：除了眼睛所在那格（身子放得下已经核过）和门那一格，经过的格子都要读得到、是空气；
    // 读不到的格当挡着，不凭空放行。
    private static boolean clearSight(Vec3 eye, Vec3 aim, BlockPos gate, Function<BlockPos, Optional<BlockState>> read) {
        Vec3 step = aim.subtract(eye);
        int samples = (int) Math.ceil(step.length() / SIGHT_STEP);
        BlockPos head = BlockPos.containing(eye);
        BlockPos last = null;
        for (int i = 0; i <= samples; i++) {
            BlockPos cell = BlockPos.containing(eye.add(step.scale((double) i / samples)));
            if (cell.equals(last) || cell.equals(gate) || cell.equals(head)) continue;
            last = cell;
            Optional<BlockState> state = read.apply(cell);
            if (state.isEmpty() || !state.get().isAir()) return false;
        }
        return true;
    }

    // 身子放得下：空气，或草、雪片这类能直接站进去的（不含水和岩浆）。
    private static boolean passable(Optional<BlockState> state) {
        return state.isPresent() && (state.get().isAir()
                || state.get().canBeReplaced() && state.get().getFluidState().isEmpty());
    }
}
