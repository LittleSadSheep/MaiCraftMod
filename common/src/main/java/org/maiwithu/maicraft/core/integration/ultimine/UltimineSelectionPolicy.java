// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ultimine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** 原生形状可能涉及的完整范围都须获准；只显示出来的部分轮廓不能代表连锁挖掘安全。 */
public final class UltimineSelectionPolicy {
    public static final String SQUARE = "ftbultimine:small_square";
    public static final String SQUARE_CLASS = "dev.ftb.mods.ftbultimine.shape.SmallSquareShape";
    public static final String SHAPELESS = "ftbultimine:shapeless";
    public static final String SHAPELESS_CLASS = "dev.ftb.mods.ftbultimine.shape.ShapelessShape";
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

    /** 采矿沿原生整脉预览采集；预览本身是 FTB 提供的观察，埋藏副目标不再要求逐格先露出表面。 */
    public static Admission admitMining(Preview preview, BlockPos origin, View view) {
        if (preview == null || origin == null || view == null) return rejected("ultimine_preview_missing");
        if (!preview.pressed) return rejected("ultimine_native_key_not_active");
        if (!preview.allowed) return rejected("ultimine_native_restriction: " + preview.reason);
        if (!SHAPELESS.equals(preview.shapeId) || !SHAPELESS_CLASS.equals(preview.implementation))
            return rejected("ultimine_mining_requires_native_shapeless");
        if (preview.actualCount <= 0 || preview.actualCount != preview.visibleBlocks.size())
            return rejected("ultimine_preview_incomplete_or_truncated");
        if (new HashSet<>(preview.visibleBlocks).size() != preview.actualCount || !preview.visibleBlocks.contains(origin))
            return rejected("ultimine_native_selection_inconsistent");
        if (preview.actualCount == 1) return rejected("ultimine_only_one_remaining_block");
        // 逐格核对本次材料族、范围和保护，不能因为服务器合并了标签就顺带拆掉别种材料或机器。
        for (BlockPos at : preview.visibleBlocks) {
            Cell cell = view.inspect(at);
            if (cell == null || !cell.loaded) return rejected("ultimine_envelope_unloaded");
            if (!cell.authorized || cell.preserved) return rejected("ultimine_selection_outside_mining_permission");
            if (cell.blockEntity) return rejected("ultimine_envelope_contains_block_entity");
            if (cell.unbreakable || cell.fluid) return rejected("ultimine_envelope_contains_unsafe_block");
            if (!cell.correctTool) return rejected("ultimine_tool_cannot_harvest_entire_envelope");
        }
        // 耐久、饥饿和经验由 FTB 原生限制结算；允许它只采完半脉，任务随后报告实际变化并收取掉落。
        return new Admission(true, "ultimine_complete_native_vein_admitted", preview.visibleBlocks, preview.visibleBlocks);
    }

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
        // 手持工具须能完成整次原生选区，挖完仍至少留一点耐久，不能只够破坏准星下的一块。
        if (remainingDurability <= preview.actualCount) return rejected("ultimine_tool_durability_insufficient_for_selection");
        List<BlockPos> envelope = square(origin, face);
        if (!envelope.containsAll(preview.visibleBlocks)) return rejected("ultimine_selection_outside_native_shape");
        String unsafe = envelopeFailure(origin, face, view);
        if (unsafe != null) return rejected(unsafe);
        return new Admission(true, "ultimine_complete_native_square_admitted", preview.visibleBlocks, envelope);
    }
    /** 按键前先排除命中面上越界或受保护的九格范围；之后仍须等原生完整预览才能允许连锁。 */
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
        // 上下面对应水平九格，侧面对应竖直九格，跟随玩家真正看向的面而非固定挖掘方向。
        List<BlockPos> result = new ArrayList<>();
        for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) result.add(switch (face.getAxis()) {
            case X -> origin.offset(0, a, b); case Y -> origin.offset(a, 0, b); case Z -> origin.offset(a, b, 0);
        });
        return List.copyOf(result);
    }
    private static Admission rejected(String code) { return new Admission(false, code, List.of(), List.of()); }
}
