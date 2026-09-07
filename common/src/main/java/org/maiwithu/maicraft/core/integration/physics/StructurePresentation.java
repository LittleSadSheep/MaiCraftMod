package org.maiwithu.maicraft.core.integration.physics;

import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.AABB;

/** Presentation relevance only. Small assemblies remain present in native collision observation. */
public final class StructurePresentation {
    private StructurePresentation() {}
    public static boolean small(AABB bounds) {
        return bounds != null && bounds.getXsize() <= 3 && bounds.getYsize() <= 3 && bounds.getZsize() <= 3
                && bounds.getXsize() * bounds.getYsize() * bounds.getZsize() <= 8;
    }
    public record Selection(List<SableStructureBridge.Structure> shown, int smallCollapsed, int otherOmitted) {}
    public static Selection select(List<SableStructureBridge.Structure> source, UUID looked, UUID target, AABB body) {
        var ranked = source.stream().sorted(java.util.Comparator.comparingInt(s ->
                matches(s, looked) ? 0 : matches(s, target) ? 1 : relevantCollision(s,body) ? 2 : 3)).toList();
        var shown = new java.util.ArrayList<SableStructureBridge.Structure>();
        int small = 0, omitted = 0;
        for (var structure : ranked) {
            boolean explicit = matches(structure,looked) || matches(structure,target);
            if (!explicit && small(structure.worldBounds()) && !relevantCollision(structure,body)) small++;
            else if (shown.size() < 4 || explicit) shown.add(structure);
            else omitted++;
        }
        return new Selection(List.copyOf(shown),small,omitted);
    }
    private static boolean relevantCollision(SableStructureBridge.Structure structure, AABB body) {
        return body != null && structure.worldBounds() != null && structure.worldBounds().intersects(body.inflate(.1));
    }
    private static boolean matches(SableStructureBridge.Structure structure, UUID id) { return id != null && id.equals(structure.id()); }
}
