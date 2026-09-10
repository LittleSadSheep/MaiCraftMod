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

/**
 * 把取材料时明确指定的地标变成不能修改的格子，并记录找不到的地标名。
 * 这里只保护地标坐标这一格；建筑的完整保护范围由外层任务另行提供。
 */
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
        // 把这些格子并入外层已有的禁止修改范围，供挖掘和寻路共同读取；它们本身不禁止玩家经过。
        return NavigationSafetyContext.withProtectedArea(markedCells, LongSets.emptySet(), operation);
    }

    private static String normalize(String label) {
        return label.strip().toLowerCase(Locale.ROOT);
    }
}
