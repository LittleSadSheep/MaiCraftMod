// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ultimine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.core.integration.ultimine.UltimineSelectionPolicy.*;

/** Tests the complete native envelope, including positions not drawn or not selected by the current matcher. */
public final class UltimineSelectionPolicyTest {
    private static int checks;
    private static final BlockPos ORIGIN = new BlockPos(10, 64, 10);
    private static final Cell SAFE = new Cell(true, true, false, false, false, false, true);
    public static void main(String[] args) {
        admitsOnlyTheWholeAuthorizedNativeSquare();
        croppedAndStaleNativeSelectionsNeverBecomeSafe();
        everyPotentialCellKeepsItsProtectionAndDropRequirements();
        durabilityReservesTheWholeNativeSelection();
        missingOptionalModCanBeClosedWithoutInitializingIt();
        System.out.println("UltimineSelectionPolicyTest: " + checks + " checks passed");
    }
    private static void admitsOnlyTheWholeAuthorizedNativeSquare() {
        for (Direction face : Direction.values()) {
            List<BlockPos> square = UltimineSelectionPolicy.square(ORIGIN, face);
            var admission = UltimineSelectionPolicy.admit(preview(square, 9), ORIGIN, face, at -> SAFE, 10);
            check(admission.allowed() && admission.completeSelection().size() == 9 && admission.potentialSelection().size() == 9,
                    "all nine positions are preserved for each clicked face");
            for (BlockPos at : square) {
                BlockPos offset = at.subtract(ORIGIN);
                check(face.getAxis().choose(offset.getX(), offset.getY(), offset.getZ()) == 0, "native small_square stays in the clicked face's plane");
                check(Math.abs(offset.getX()) <= 1 && Math.abs(offset.getY()) <= 1 && Math.abs(offset.getZ()) <= 1, "small_square never invents a deeper tunnel");
            }
        }
        List<BlockPos> visible = List.of(ORIGIN, ORIGIN.east());
        check(UltimineSelectionPolicy.admit(preview(visible, 2), ORIGIN, Direction.UP, at -> SAFE, 3).allowed(),
                "a genuinely complete two-block matcher result is allowed only when its whole possible square is authorized");
        Set<BlockPos> onlyVisible = Set.copyOf(visible);
        rejects(preview(visible, 2), at -> new Cell(true, onlyVisible.contains(at), false, false, false, false), 3,
                "ultimine_envelope_outside_clearance_permission");
    }
    private static void croppedAndStaleNativeSelectionsNeverBecomeSafe() {
        List<BlockPos> square = UltimineSelectionPolicy.square(ORIGIN, Direction.UP);
        rejects(preview(square.subList(0, 4), 9), at -> SAFE, 10, "ultimine_preview_incomplete_or_truncated");
        rejects(preview(List.of(), 9), at -> SAFE, 10, "ultimine_preview_incomplete_or_truncated");
        rejects(preview(List.of(ORIGIN, ORIGIN), 2), at -> SAFE, 10, "ultimine_native_selection_inconsistent");
        rejects(preview(List.of(ORIGIN, ORIGIN.above()), 2), at -> SAFE, 10, "ultimine_selection_outside_native_shape");
        rejects(preview(List.of(ORIGIN.east()), 1), at -> SAFE, 10, "ultimine_native_selection_inconsistent");
        Preview shapeless = new Preview("ftbultimine:shapeless", "dev.ftb.mods.ftbultimine.shape.ShapelessShape", 0, 9, square, true, true, "allowed", 1);
        rejects(shapeless, at -> SAFE, 10, "ultimine_shape_needs_bounded_native_envelope");
        Preview impostor = new Preview(UltimineSelectionPolicy.SQUARE, "other.CustomShape", 2, 9, square, true, true, "allowed", 1);
        rejects(impostor, at -> SAFE, 10, "ultimine_shape_needs_bounded_native_envelope");
        var denied = new Preview(UltimineSelectionPolicy.SQUARE, UltimineSelectionPolicy.SQUARE_CLASS, 2, 9, square, true, false, "no_permission", 1);
        rejects(denied, at -> SAFE, 10, "ultimine_native_restriction: no_permission");
        var released = new Preview(UltimineSelectionPolicy.SQUARE, UltimineSelectionPolicy.SQUARE_CLASS, 2, 9, square, false, true, "allowed", 1);
        rejects(released, at -> SAFE, 10, "ultimine_native_key_not_active");
    }
    private static void everyPotentialCellKeepsItsProtectionAndDropRequirements() {
        BlockPos topCorner = ORIGIN.offset(1, 0, 1);
        View boundary = at -> at.equals(topCorner) ? new Cell(true, false, false, false, false, false) : SAFE;
        check(UltimineSelectionPolicy.envelopeFailure(ORIGIN, Direction.UP, boundary) != null,
                "an unsafe horizontal envelope is rejected before acquiring any Ultimine key");
        check(UltimineSelectionPolicy.envelopeFailure(ORIGIN, Direction.EAST, boundary) == null,
                "the actual side hit uses a vertical plane instead of a fixed horizontal square");
        Preview partial = preview(List.of(ORIGIN, ORIGIN.east()), 2); BlockPos hidden = ORIGIN.west().north();
        List<Cell> unsafe = List.of(new Cell(false, true, false, false, false, false), new Cell(true, true, true, false, false, false),
                new Cell(true, true, false, true, false, false), new Cell(true, true, false, false, true, false),
                new Cell(true, true, false, false, false, true), new Cell(true, true, false, false, false, false, false));
        for (Cell cell : unsafe) check(!UltimineSelectionPolicy.admit(partial, ORIGIN, Direction.UP, at -> at.equals(hidden) ? cell : SAFE, 10).allowed(),
                "even a non-previewed potential cell rejects unloaded/protected/container/unbreakable/fluid/wrong-tool states");
        var input = new ArrayList<>(UltimineSelectionPolicy.square(ORIGIN, Direction.UP)); Preview detached = preview(input, 9); input.clear();
        check(detached.visibleBlocks().size() == 9, "native preview facts do not share the caller's mutable list");
    }
    private static void durabilityReservesTheWholeNativeSelection() {
        Preview nine = preview(UltimineSelectionPolicy.square(ORIGIN, Direction.UP), 9);
        rejects(nine, at -> SAFE, 9, "ultimine_tool_durability_insufficient_for_selection");
        rejects(nine, at -> SAFE, 3, "ultimine_tool_durability_insufficient_for_selection");
        check(UltimineSelectionPolicy.admit(nine, ORIGIN, Direction.UP, at -> SAFE, 10).allowed(), "nine-block admission retains at least one durability afterwards");
    }
    private static void missingOptionalModCanBeClosedWithoutInitializingIt() {
        if (!org.maiwithu.maicraft.server.machine.NativeApi.present("dev.ftb.mods.ftbultimine.client.FTBUltimineClient")) {
            check(!UltimineNative.available(), "missing Ultimine is detected before taking any native input");
            new UltimineSession().close(); checks++;
        }
    }
    private static Preview preview(List<BlockPos> blocks, int actual) { return new Preview(UltimineSelectionPolicy.SQUARE, UltimineSelectionPolicy.SQUARE_CLASS, 2, actual, blocks, true, true, "allowed", 1); }
    private static void rejects(Preview preview, View view, int durability, String code) {
        var admission = UltimineSelectionPolicy.admit(preview, ORIGIN, Direction.UP, view, durability);
        check(!admission.allowed() && admission.code().equals(code), "expected " + code + " but got " + admission.code());
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
