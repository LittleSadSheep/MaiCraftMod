// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 保存一份不会被调用方改写的蓝图和部件列表，再另记确认、取消、显示和切层状态；不向世界写方块。
 */
public final class PreviewSession {
    public enum Decision { WAITING, CONFIRMED, CANCELLED, DISABLED, DESIGN_ONLY }
    public static final int MAX_CELLS = 65_536;
    private final String owner, dimension, title;
    private final Map<BlockPos, BlockState> cells;
    private final List<PreviewPart> parts;
    private boolean designOnly;
    private Decision decision = Decision.WAITING;
    private boolean visible = true;
    private int minY = Integer.MIN_VALUE, maxY = Integer.MAX_VALUE;

    public PreviewSession(String owner, String dimension, String title,
                          Map<BlockPos, BlockState> cells) {
        this(owner, dimension, title, cells, List.of());
    }

    public PreviewSession(String owner, String dimension, String title,
                          Map<BlockPos, BlockState> cells, List<PreviewPart> parts) {
        this.owner = Objects.requireNonNull(owner);
        this.dimension = Objects.requireNonNull(dimension);
        this.title = Objects.requireNonNull(title);
        if (owner.isBlank() || cells.isEmpty() && parts.isEmpty() || cells.size() + parts.size() > MAX_CELLS)
            throw new IllegalArgumentException("preview requires an owner and 1.." + MAX_CELLS + " cells");
        // 复制坐标和列表，避免调用方随后移动可变坐标或修改集合时，让已展示的方案跟着变。
        Map<BlockPos, BlockState> frozen = new LinkedHashMap<>();
        cells.forEach((pos, state) -> frozen.put(pos.immutable(), Objects.requireNonNull(state)));
        this.cells = Collections.unmodifiableMap(frozen);
        this.parts = List.copyOf(parts);
    }

    public String owner() { return owner; }
    public String dimension() { return dimension; }
    public String title() { return title; }
    public Map<BlockPos, BlockState> cells() { return cells; }
    public List<PreviewPart> parts() { return parts; }
    public boolean designOnly() { return designOnly; }
    public Decision decision() { return decision; }
    public boolean visible() { return visible && decision != Decision.CANCELLED; }
    public boolean includes(BlockPos pos) { return pos.getY() >= minY && pos.getY() <= maxY; }
    public int minY() { return minY; }
    public int maxY() { return maxY; }

    /**
     * 创建只读设计状态。它从来不进入 WAITING，所以调用 confirm 也不会得到施工授权。
     */
    public static PreviewSession design(String owner, String dimension, String title,
                                        Map<BlockPos, BlockState> cells) {
        PreviewSession session = new PreviewSession(owner, dimension, title, cells);
        session.designOnly = true;
        session.decision = Decision.DESIGN_ONLY;
        return session;
    }

    // 只有 WAITING 能转成 CONFIRMED；取消后再显示也不能恢复之前的确认。
    public boolean confirm() {
        if (decision != Decision.WAITING) return false;
        decision = Decision.CONFIRMED;
        return true;
    }

    /** 取消同时隐藏，并撤销已经给出的确认；若仍要施工，应由新请求重新展示和确认。 */
    public void cancel() { decision = Decision.CANCELLED; visible = false; }
    public void visible(boolean value) { visible = value; }
    public void layers(int min, int max) {
        if (min > max) throw new IllegalArgumentException("minimum layer exceeds maximum layer");
        minY = min; maxY = max;
    }
    public void allLayers() { layers(Integer.MIN_VALUE, Integer.MAX_VALUE); }
}
