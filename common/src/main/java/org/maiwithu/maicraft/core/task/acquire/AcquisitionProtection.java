package org.maiwithu.maicraft.core.task.acquire;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.intent.IntentRuntime;

/** Explicit resource markers augment the parent's measured footprint without inventing an area. */
record AcquisitionProtection(LongSet markedCells, List<String> problems) {
    static AcquisitionProtection resolve(List<String> labels,
                                         Collection<IntentRuntime.Landmark> landmarks,
                                         String dimension) {
        var remembered = new HashMap<String, IntentRuntime.Landmark>();
        for (var landmark : landmarks) remembered.put(normalize(landmark.label()), landmark);
        LongSet marked = new LongOpenHashSet();
        List<String> problems = new ArrayList<>();
        for (String label : labels) {
            var landmark = remembered.get(normalize(label));
            if (landmark == null || landmark.position() == null) {
                problems.add("unknown protected label: " + label);
                continue;
            }
            var position = landmark.position();
            if (position.dimension() == null || position.dimension().equals(dimension)) {
                marked.add(BlockPos.asLong(position.x(), position.y(), position.z()));
            }
        }
        return new AcquisitionProtection(LongSets.unmodifiable(marked), List.copyOf(problems));
    }

    <T> T run(Supplier<T> operation) {
        if (!problems.isEmpty()) throw new IllegalStateException(String.join("; ", problems));
        // IntentTask supplies measured protected_labels footprints in the outer scope. The same
        // union is read by mining target pruning, BlockDigger and the terrain path calculation.
        return NavigationSafetyContext.withProtectedArea(markedCells, LongSets.emptySet(), operation);
    }

    private static String normalize(String label) {
        return label.strip().toLowerCase(Locale.ROOT);
    }
}
