// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Review state and immutable authored geometry can be checked without a game or graphics context. */
public final class PreviewSessionTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(1, 64, 2);
        Map<BlockPos, BlockState> source = new LinkedHashMap<>();
        source.put(mutable, Blocks.OAK_STAIRS.defaultBlockState());
        List<PreviewPart> parts = new ArrayList<>();
        parts.add(new PreviewPart(mutable, "ae2:cable", "center"));
        var session = new PreviewSession("task-1", "minecraft:overworld", "house", source, parts);
        source.clear(); parts.clear(); mutable.set(10, 80, 20);
        check(session.cells().size() == 1 && session.cells().containsKey(new BlockPos(1, 64, 2)),
                "the preview must freeze positions and caller collections before review");
        check(session.parts().getFirst().position().equals(new BlockPos(1, 64, 2)), "multipart positions are frozen");
        try { session.cells().clear(); throw new AssertionError("authored geometry remained mutable"); }
        catch (UnsupportedOperationException expected) { }
        session.visible(false);
        check(session.decision() == PreviewSession.Decision.WAITING && !session.visible(),
                "hiding cannot accidentally authorize construction");
        session.layers(63, 64);
        check(session.includes(new BlockPos(1, 64, 2)) && !session.includes(new BlockPos(1, 65, 2)),
                "layer endpoints are inclusive and only affect presentation");
        check(session.cells().size() == 1 && session.confirm(), "slice review confirms the entire frozen plan");
        check(!session.confirm(), "a confirmation is consumed only once");
        session.cancel();
        session.visible(true);
        check(session.decision() == PreviewSession.Decision.CANCELLED && !session.confirm() && !session.visible(),
                "world changes and cancellation revoke approval permanently");
        var second = new PreviewSession("task-2", "minecraft:the_nether", "new plan",
                Map.of(BlockPos.ZERO, Blocks.STONE.defaultBlockState()));
        check(second.decision() == PreviewSession.Decision.WAITING, "new plans never inherit old approval");
        rejects(() -> second.layers(5, 4));
        rejects(() -> new PreviewSession("task", "dimension", "empty", Map.of()));
        rejects(() -> new PreviewPart(BlockPos.ZERO, "ae2:cable", "unknown"));
        check(PreviewPartGeometry.localBox("north").maxZ < .5
                && PreviewPartGeometry.localBox("south").minZ > .5
                && PreviewPartGeometry.localBox("center").getXsize() < 1,
                "multipart side and centre geometry retain their distinct physical locations");
        System.out.println("PreviewSessionTest: passed");
    }

    private static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("invalid preview accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
