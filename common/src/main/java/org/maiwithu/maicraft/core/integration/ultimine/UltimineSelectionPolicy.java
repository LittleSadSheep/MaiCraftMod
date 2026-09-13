// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ultimine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** An entire native shape must be admitted; a cropped outline never grants mining permission. */
public final class UltimineSelectionPolicy {
    public static final String SQUARE = "ftbultimine:small_square";
    public static final String SQUARE_CLASS = "dev.ftb.mods.ftbultimine.shape.SmallSquareShape";
    public record Preview(String shapeId, String implementation, int shapeIndex, int actualCount,
                          List<BlockPos> visibleBlocks, boolean pressed, boolean allowed, String reason, long revision) {
        public Preview { visibleBlocks = visibleBlocks.stream().map(BlockPos::immutable).toList(); }
    }
    public record Cell(boolean loaded, boolean authorized, boolean preserved, boolean blockEntity, boolean unbreakable, boolean fluid, boolean correctTool) {
        public Cell(boolean loaded, boolean authorized, boolean preserved, boolean blockEntity, boolean unbreakable, boolean fluid) {
            this(loaded, authorized, preserved, blockEntity, unbreakable, fluid, true);
        }
    }
    @FunctionalInterface public interface View { Cell inspect(BlockPos position); }
    public record Admission(boolean allowed, String code, List<BlockPos> completeSelection, List<BlockPos> potentialSelection) {
        public Admission { completeSelection = List.copyOf(completeSelection); potentialSelection = List.copyOf(potentialSelection); }
    }
    private UltimineSelectionPolicy() {}

    public static Admission admit(Preview preview, BlockPos origin, Direction face, View view) {
        return admit(preview, origin, face, view, Integer.MAX_VALUE);
    }
    public static Admission admit(Preview preview, BlockPos origin, Direction face, View view, int remainingDurability) {
        if (preview == null || origin == null || face == null || view == null) return rejected("ultimine_preview_missing");
        if (!preview.pressed) return rejected("ultimine_native_key_not_active");
        if (!preview.allowed) return rejected("ultimine_native_restriction: " + preview.reason);
        if (!SQUARE.equals(preview.shapeId) || !SQUARE_CLASS.equals(preview.implementation)) return rejected("ultimine_shape_needs_bounded_native_envelope");
        if (preview.actualCount <= 0 || preview.actualCount != preview.visibleBlocks.size()) return rejected("ultimine_preview_incomplete_or_truncated");
        if (preview.actualCount > 9 || new HashSet<>(preview.visibleBlocks).size() != preview.actualCount || !preview.visibleBlocks.contains(origin))
            return rejected("ultimine_native_selection_inconsistent");
        if (remainingDurability <= preview.actualCount) return rejected("ultimine_tool_durability_insufficient_for_selection");
        List<BlockPos> envelope = square(origin, face);
        if (!envelope.containsAll(preview.visibleBlocks)) return rejected("ultimine_selection_outside_native_shape");
        String unsafe = envelopeFailure(origin, face, view);
        if (unsafe != null) return rejected(unsafe);
        return new Admission(true, "ultimine_complete_native_square_admitted", preview.visibleBlocks, envelope);
    }
    /** Reject unsafe faces before pressing the key; only FTB's later preview admits a batch. */
    public static String envelopeFailure(BlockPos origin, Direction face, View view) {
        for (BlockPos at : square(origin, face)) {
            Cell cell = view.inspect(at);
            if (cell == null || !cell.loaded) return "ultimine_envelope_unloaded";
            if (!cell.authorized || cell.preserved) return "ultimine_envelope_outside_clearance_permission";
            if (cell.blockEntity) return "ultimine_envelope_contains_block_entity";
            if (cell.unbreakable || cell.fluid) return "ultimine_envelope_contains_unsafe_block";
            if (!cell.correctTool) return "ultimine_tool_cannot_harvest_entire_envelope";
        }
        return null;
    }
    public static List<BlockPos> square(BlockPos origin, Direction face) {
        List<BlockPos> result = new ArrayList<>();
        for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) result.add(switch (face.getAxis()) {
            case X -> origin.offset(0, a, b); case Y -> origin.offset(a, 0, b); case Z -> origin.offset(a, b, 0);
        });
        return List.copyOf(result);
    }
    private static Admission rejected(String code) { return new Admission(false, code, List.of(), List.of()); }
}
