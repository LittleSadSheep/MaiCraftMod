package org.maiwithu.maicraft.core.task.acquire;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** 跨取材和炉子子任务保留正在制作的祖先；允许石头再烧平滑石，但不能为原料递归制作同一祖先成品。 */
public record ProductionLineage(Set<ResourceLocation> ancestors, int depth) {
    public static final int MAX_DEPTH = 8;
    public static final ProductionLineage ROOT = new ProductionLineage(Set.of(), 0);
    public ProductionLineage {
        ancestors = Set.copyOf(ancestors);
        if (depth < 0 || depth > MAX_DEPTH) throw new IllegalArgumentException("invalid cooking dependency depth");
    }
    public boolean blocks(ResourceLocation output) { return ancestors.contains(output); }
    public boolean mayDescend() { return depth < MAX_DEPTH; }
    public ProductionLineage include(Collection<ResourceLocation> items) {
        var next = new LinkedHashSet<>(ancestors); next.addAll(items); return new ProductionLineage(next, depth);
    }
    public ProductionLineage descend(ResourceLocation output) {
        var next = new LinkedHashSet<>(ancestors); next.add(output); return new ProductionLineage(next, depth + 1);
    }
}
