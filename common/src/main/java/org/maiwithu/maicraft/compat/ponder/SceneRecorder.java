// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ponder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.compat.ponder.PonderReads.ScenePlayback;
import org.maiwithu.maicraft.compat.ponder.PonderReads.ShownInScene;

/**
 * 把一个思索场景从头放到尾，记下这一篇资料要的东西：开场的演示结构、按出现顺序的旁白与操作提示，
 * 以及每一段（两个关键帧之间）结束时相对这一段开始时被场景改了哪些格。
 *
 * <p>放一遍可能要几百上千刻，一次读资料做不完：每次给一点时间预算往下放，没放完就等下次接着放。
 * 关键帧之前出现的文字归上一段，关键帧那一刻出现的文字归新的一段（作者常把关键帧挂在一段新旁白上）。
 * 场景超过 {@link #MAX_TICKS} 刻还没完就停，如实标出没放完。
 */
final class SceneRecorder {
    /** 一个场景最多放多少刻：正常的场景远不到这么长，超过多半是哪里卡住了。 */
    static final int MAX_TICKS = 12000;

    private final ScenePlayback playback;
    private final LongSupplier clock;
    private final Map<BlockPos, BlockState> opening;
    private Map<BlockPos, BlockState> segmentStart;
    private final List<Line> lines = new ArrayList<>();
    private final List<Segment> segments = new ArrayList<>();
    private int keyframesSeen;
    private int segmentFirstLine;
    private boolean done;
    private boolean cutShort;

    /**
     * @param playback 刚开始、停在第 0 刻的一次播放
     * @param clock    纳秒时钟，控制每次往下放的时间预算；测试里换成假的
     */
    SceneRecorder(ScenePlayback playback, LongSupplier clock) {
        this.playback = Objects.requireNonNull(playback, "playback");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.opening = snapshot();
        this.segmentStart = opening;
        collect();
    }

    /** 往下放，直到放完或用完这次的时间预算；放完了返回 true。 */
    boolean advance(long budgetNanos) {
        long deadline = clock.getAsLong() + budgetNanos;
        while (!done) {
            if (playback.finished() || playback.tick() >= playback.totalTicks()) {
                finish(false);
                break;
            }
            if (playback.tick() >= MAX_TICKS) {
                finish(true);
                break;
            }
            playback.advance();
            // 先看有没有过关键帧：过了就把上一段收尾，这一刻新出现的文字归新的一段。
            if (playback.keyframesPassed() > keyframesSeen) {
                keyframesSeen = playback.keyframesPassed();
                closeSegment();
            }
            collect();
            if (clock.getAsLong() >= deadline) break;
        }
        return done;
    }

    /** 放完了没有。 */
    boolean done() {
        return done;
    }

    /** 放到哪了：写给 LLM 看的一句话。 */
    String progress() {
        return "思索场景还在演示世界里放：第 " + playback.tick() + " 刻，共 " + playback.totalTicks() + " 刻";
    }

    /** 场景的 ID 与标题。 */
    String sceneId() {
        return playback.sceneId();
    }

    String title() {
        return playback.title();
    }

    /** 演示世界的大小（各方向的格数）。 */
    BlockPos size() {
        BlockPos min = playback.minCorner();
        BlockPos max = playback.maxCorner();
        return new BlockPos(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1);
    }

    /** 开场的演示结构：不是空气的每一格，坐标相对演示世界的最小角。 */
    Map<BlockPos, BlockState> opening() {
        return relative(opening);
    }

    /** 按出现顺序的旁白与操作提示。 */
    List<Line> lines() {
        return List.copyOf(lines);
    }

    /** 每一段：从哪一刻开始、这一段的旁白编号、这一段结束时相对开始改了哪些格。 */
    List<Segment> segments() {
        return List.copyOf(segments);
    }

    /** 超过最长刻数被截断了。 */
    boolean cutShort() {
        return cutShort;
    }

    private void finish(boolean truncated) {
        closeSegment();
        cutShort = truncated;
        done = true;
    }

    // 把这一段收尾：这一段的旁白、这一段里被改了的格（换成别的方块的、拆成空气的）。
    private void closeSegment() {
        Map<BlockPos, BlockState> now = snapshot();
        Map<BlockPos, BlockState> changed = new LinkedHashMap<>();
        List<BlockPos> removed = new ArrayList<>();
        Set<BlockPos> positions = new HashSet<>(segmentStart.keySet());
        positions.addAll(now.keySet());
        for (BlockPos position : sorted(positions)) {
            BlockState before = segmentStart.get(position);
            BlockState after = now.get(position);
            if (after == null && before != null) removed.add(relative(position));
            else if (after != null && !after.equals(before)) changed.put(relative(position), after);
        }
        List<Integer> numbers = new ArrayList<>();
        for (int index = segmentFirstLine; index < lines.size(); index++) numbers.add(index + 1);
        segments.add(new Segment(segments.size() + 1, numbers, changed, removed));
        segmentStart = now;
        segmentFirstLine = lines.size();
    }

    private void collect() {
        for (ShownInScene shown : playback.newlyShown()) {
            lines.add(new Line(playback.tick(), shown));
        }
    }

    // 演示世界里不是空气的每一格，按坐标排好。
    private Map<BlockPos, BlockState> snapshot() {
        BlockPos min = playback.minCorner();
        BlockPos max = playback.maxCorner();
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        for (BlockPos position : BlockPos.betweenClosed(min, max)) {
            BlockState state = playback.blockAt(position);
            if (state != null && !state.isAir()) blocks.put(position.immutable(), state);
        }
        return blocks;
    }

    private Map<BlockPos, BlockState> relative(Map<BlockPos, BlockState> blocks) {
        Map<BlockPos, BlockState> shifted = new LinkedHashMap<>();
        blocks.forEach((position, state) -> shifted.put(relative(position), state));
        return shifted;
    }

    private BlockPos relative(BlockPos position) {
        return position.subtract(playback.minCorner());
    }

    private static List<BlockPos> sorted(Set<BlockPos> positions) {
        List<BlockPos> list = new ArrayList<>(positions);
        list.sort((a, b) -> a.getY() != b.getY() ? Integer.compare(a.getY(), b.getY())
                : a.getZ() != b.getZ() ? Integer.compare(a.getZ(), b.getZ()) : Integer.compare(a.getX(), b.getX()));
        return list;
    }

    /** 一段旁白或操作提示，以及它在第几刻出现。 */
    record Line(int tick, ShownInScene shown) {}

    /**
     * 两个关键帧之间的一段。
     *
     * @param index   第几段，从 1 起
     * @param lines   这一段的旁白编号（从 1 起，对应 lines() 里的顺序）
     * @param changed 这一段结束时相对开始换了方块的格，坐标相对演示世界的最小角
     * @param removed 这一段里被拆成空气的格
     */
    record Segment(int index, List<Integer> lines, Map<BlockPos, BlockState> changed, List<BlockPos> removed) {
        Segment {
            lines = List.copyOf(lines);
            changed = Collections.unmodifiableMap(new LinkedHashMap<>(changed));
            removed = List.copyOf(removed);
        }
    }
}
