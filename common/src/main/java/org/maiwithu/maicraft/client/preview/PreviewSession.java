// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Immutable authored cells plus an explicit, one-way human review decision. No world writes. */
public final class PreviewSession {
    public enum Decision { WAITING, CONFIRMED, CANCELLED, DISABLED }
    public static final int MAX_CELLS = 65_536;
    private final String owner, dimension, title;
    private final Map<BlockPos, BlockState> cells;
    private final List<PreviewPart> parts;
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
    public Decision decision() { return decision; }
    public boolean visible() { return visible && decision != Decision.CANCELLED; }
    public boolean includes(BlockPos pos) { return pos.getY() >= minY && pos.getY() <= maxY; }
    public int minY() { return minY; }
    public int maxY() { return maxY; }

    public boolean confirm() {
        if (decision != Decision.WAITING) return false;
        decision = Decision.CONFIRMED;
        return true;
    }

    /** Invalidation also revokes an earlier confirmation; a new world must be reviewed again. */
    public void cancel() { decision = Decision.CANCELLED; visible = false; }
    public void visible(boolean value) { visible = value; }
    public void layers(int min, int max) {
        if (min > max) throw new IllegalArgumentException("minimum layer exceeds maximum layer");
        minY = min; maxY = max;
    }
    public void allLayers() { layers(Integer.MIN_VALUE, Integer.MAX_VALUE); }
}
