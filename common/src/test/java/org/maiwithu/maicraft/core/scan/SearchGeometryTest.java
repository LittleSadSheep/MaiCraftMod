package org.maiwithu.maicraft.core.scan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;

/** Dense and sparse terrain must return the same nearest cells regardless of visit order. */
public final class SearchGeometryTest {
    public static void main(String[] args) {
        BlockPos center = new BlockPos(15, 79, -1);
        List<BlockPos> cells = new ArrayList<>();
        for (int y = 64; y < 80; y++) {
            for (int z = -16; z < 0; z++) {
                for (int x = 0; x < 32; x++) cells.add(new BlockPos(x, y, z));
            }
        }
        Comparator<BlockPos> order = Comparator.comparingDouble((BlockPos pos) -> pos.distSqr(center))
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ);
        List<BlockPos> expected = cells.stream().sorted(order).limit(12).toList();
        SearchGeometry.NearestPositions nearest = new SearchGeometry.NearestPositions(center, 12);
        cells.forEach(nearest::offer);
        if (!nearest.sorted().equals(expected)) {
            throw new AssertionError("Dense scan returned iteration-first cells instead of nearest cells");
        }
        Collections.shuffle(cells, new Random(5));
        SearchGeometry.NearestPositions shuffled = new SearchGeometry.NearestPositions(center, 12);
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (BlockPos cell : cells) shuffled.offer(mutable.set(cell));
        mutable.set(1_000, 1_000, 1_000);
        if (!shuffled.sorted().equals(expected)) {
            throw new AssertionError("Nearest cells depend on visit order or retain a mutable position");
        }
        SearchGeometry.NearestPositions boundary = new SearchGeometry.NearestPositions(BlockPos.ZERO, 1);
        boundary.offer(new BlockPos(1, 0, 0));
        if (boundary.canStopAfterRing(0)) {
            throw new AssertionError("Equal-distance candidates in the next ring must still be considered");
        }
        boundary.offer(BlockPos.ZERO);
        if (!boundary.canStopAfterRing(0)) throw new AssertionError("Zero-distance candidate should finish");
        // A full old window of individually rejected targets must not hide farther usable ore.
        var rejected = new java.util.HashSet<BlockPos>();
        for (int x = 1; x <= 64; x++) rejected.add(new BlockPos(x, 0, 0));
        var usable = new SearchGeometry.NearestPositions(BlockPos.ZERO, 64, rejected);
        rejected.clear(); // selection owns its exclusion snapshot across ticks
        for (int x = 128; x >= 1; x--) usable.offer(new BlockPos(x, 0, 0));
        if (usable.sorted().size() != 64 || !usable.sorted().getFirst().equals(new BlockPos(65, 0, 0))) {
            throw new AssertionError("An exhausted nearest window hid the next usable targets");
        }
        System.out.println("SearchGeometryTest: nearest candidate regressions passed");
    }
}
