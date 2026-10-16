// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 隔水勘测（纯函数）：人和目的地之间隔着一片连起来的活水吗，水有多宽，两头岸在哪。
 * 沿出发点到目的地连一线，逐柱找地表：顶上是水算水柱，是冰算冻住，别的是实地。
 * 出发地、目的地脚下就是水的不算"隔着水"——那种走路会泅渡，或者人正坐在船上由"非它不可"接手。
 */
public final class WaterCrossing {

    /** 连成这么宽（格）的水才算一片要坐船过的水：更窄的溪沟走路踩得过去或一步跳得过。 */
    static final int MIN_WATER_WIDTH = 6;
    /** 勘测最远看多远（格）：更远的目的地先不做放船判断，走路与别的办法先答。 */
    static final int MAX_SURVEY_DISTANCE = 384;
    /** 找地表时从两头脚位往上、往下各看几格。 */
    static final int Y_WINDOW_ABOVE = 8;
    static final int Y_WINDOW_BELOW = 12;

    /** 勘测结论。 */
    public enum Answer {
        /** 隔着一片能划的水，两头都有岸。 */
        ACROSS_WATER,
        /** 连线上没找到成片的水。 */
        NO_WATER,
        /** 出发地脚下就是水：不用放船，走路会泅渡。 */
        WATER_AT_START,
        /** 目的地那一头到线走完还是水：划过去也上不了岸。 */
        WATER_AT_END,
        /** 水面冻住了（冰封的湖海），船放不下去。 */
        FROZEN
    }

    /** 一片水：出发侧的岸、对面的岸，水有多宽（格）。 */
    public record Span(BlockPos nearShore, BlockPos farShore, int waterWidth) {}

    /** 一次勘测的收成：结论加隔的那片水（只在 ACROSS_WATER 时有）。 */
    public record Survey(Answer answer, Span span) {}

    private WaterCrossing() {}

    /**
     * 沿出发点到目的地连一线逐柱看地表，找第一段两头都接着实地、宽度够的水。
     * 读不到的柱（没加载）按实地算：不因为那一片没加载就以为有水。
     *
     * @param read 读一格方块；没加载的格给空
     */
    public static Survey between(Function<BlockPos, Optional<BlockState>> read, BlockPos from, BlockPos to) {
        BlockPos start = from.immutable();
        BlockPos end = to.immutable();
        Kind startKind = surfaceKind(read, start);
        if (startKind == Kind.WATER || startKind == Kind.FROZEN) {
            return new Survey(Answer.WATER_AT_START, null);
        }
        double distance = Math.sqrt(start.distSqr(end));
        if (distance < 1) return new Survey(Answer.NO_WATER, null);
        // 逐柱沿连线走；太远时按比例加大步距，水宽按步距折回格数。
        int step = Math.max(1, (int) Math.ceil(distance / 256.0));
        int steps = (int) Math.ceil(distance / step);
        BlockPos previous = null;
        int run = 0;
        BlockPos runStartLand = null;
        boolean frozenSeen = false;
        for (int i = 1; i <= steps; i++) {
            BlockPos cell = lerp(start, end, i * step / distance);
            Kind kind = surfaceKind(read, cell);
            if (kind == Kind.FROZEN) frozenSeen = true;
            if (kind == Kind.WATER) {
                if (run == 0) runStartLand = previous;
                run += step;
                continue;
            }
            // 一段水到头了：够宽、两头都是实地才算隔水（出发侧没实地就不算，那是一开始就站在水里）。
            // 读不到的柱（没加载）也算实地：不因为那一片没加载就以为有水。
            if (run >= MIN_WATER_WIDTH && runStartLand != null && kind == Kind.LAND) {
                return new Survey(Answer.ACROSS_WATER, new Span(runStartLand, cell, run));
            }
            run = 0;
            runStartLand = null;
            previous = cell;
        }
        if (run > 0) return new Survey(Answer.WATER_AT_END, null);
        return new Survey(frozenSeen ? Answer.FROZEN : Answer.NO_WATER, null);
    }

    /** 这一趟划水的耗时估计（刻）：走到出发侧岸边、放船上船下船的零头、水上按约每秒 8 格、下船后走到目的地。 */
    public static long estimateTicks(BlockPos from, Span span, BlockPos to) {
        long overhead = 20L * 6;
        long walking = TravelPlanner.walkTicks(from, span.nearShore()) + TravelPlanner.walkTicks(span.farShore(), to);
        long paddling = Math.round(span.waterWidth() / 8.0 * 20);
        return walking + paddling + overhead;
    }

    /** 一柱的地表：从上往下找第一个不是空气的方块；全是空气（或整柱没加载）给空。 */
    static Optional<Top> surface(Function<BlockPos, Optional<BlockState>> read, int x, int z, int yHigh, int yLow) {
        for (int y = yHigh; y >= yLow; y--) {
            Optional<BlockState> state = read.apply(new BlockPos(x, y, z));
            if (state.isEmpty() || state.get().isAir()) continue;
            return Optional.of(new Top(new BlockPos(x, y, z), state.get()));
        }
        return Optional.empty();
    }

    /** 这一柱的地表是什么：水、冻住的水，还是实地；整柱没有实遮挡（没加载或悬空）算实地，不凭空判成水。 */
    static Kind surfaceKind(Function<BlockPos, Optional<BlockState>> read, BlockPos anywhere) {
        Optional<Top> top = surface(read, anywhere.getX(), anywhere.getZ(),
                anywhere.getY() + Y_WINDOW_ABOVE, anywhere.getY() - Y_WINDOW_BELOW);
        return top.map(t -> kindOf(t.state())).orElse(Kind.LAND);
    }

    /** 一个方块的类别：水、冰封的水，其余算实地（睡莲算水，划的时候再看它挡不挡）。 */
    static Kind kindOf(BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.ICE || block == Blocks.PACKED_ICE || block == Blocks.BLUE_ICE
                || block == Blocks.FROSTED_ICE) return Kind.FROZEN;
        if (block == Blocks.WATER || block == Blocks.LILY_PAD || block == Blocks.BUBBLE_COLUMN) return Kind.WATER;
        return Kind.LAND;
    }

    /** 顶格：那一格的位置与方块状态。 */
    record Top(BlockPos pos, BlockState state) {}

    /** 地表类别。 */
    enum Kind { WATER, FROZEN, LAND }

    /** 这一柱站得住的脚位：地表之上那一格；地表是水或没找到给空。 */
    static OptionalInt standFeet(Function<BlockPos, Optional<BlockState>> read, int x, int z, int yHint) {
        Optional<Top> top = surface(read, x, z, yHint + Y_WINDOW_ABOVE, yHint - Y_WINDOW_BELOW);
        if (top.isEmpty() || kindOf(top.get().state()) != Kind.LAND) return OptionalInt.empty();
        return OptionalInt.of(top.get().pos().getY() + 1);
    }

    /** 线上按比例取一点所在的柱。 */
    private static BlockPos lerp(BlockPos start, BlockPos end, double fraction) {
        return new BlockPos((int) Math.round(start.getX() + (end.getX() - start.getX()) * fraction),
                start.getY(), (int) Math.round(start.getZ() + (end.getZ() - start.getZ()) * fraction));
    }
}
