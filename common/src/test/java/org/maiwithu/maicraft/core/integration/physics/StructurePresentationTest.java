package org.maiwithu.maicraft.core.integration.physics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.phys.AABB;

// 检查很多微小结构不会挤掉大船信息，但准星所指、当前登船目标和挡住身体的小结构仍保留；展示不修改原列表。
public final class StructurePresentationTest {
    public static void main(String[] args) {
        var many = new ArrayList<SableStructureBridge.Structure>();
        for (int i = 0; i < 200; i++) many.add(structure(new AABB(i,0,0,i+1,1,1)));
        var vessel = structure(new AABB(0,10,0,22,24,12)); many.add(vessel);
        var compact = StructurePresentation.select(many,null,null,null);
        check(compact.shown().equals(List.of(vessel)) && compact.smallCollapsed() == 200,
                "single-block assemblies must collapse rather than consume the vessel context budget");
        var pointed = many.get(13); var active = many.get(25);
        var explicit = StructurePresentation.select(many,pointed.id(),active.id(),null);
        check(explicit.shown().containsAll(List.of(pointed,active,vessel)) && explicit.smallCollapsed() == 198,
                "gaze and boarding identities must survive small-object filtering");
        var collision = StructurePresentation.select(many,null,null,new AABB(-.1,0,-.1,.1,1,.1));
        check(collision.shown().contains(many.getFirst()), "the small object actually blocking the body remains relevant");
        check(many.size() == 201, "presentation cannot mutate the collision observation source");
        for (int i = 0; i < 20; i++) many.add(structure(new AABB(0,10,0,22,24,12)));
        var capped = StructurePresentation.select(many,null,null,null);
        check(capped.shown().size() == 4 && capped.otherOmitted() == 17, "large-object details must also have a context budget");
        System.out.println("StructurePresentationTest: passed");
    }
    private static SableStructureBridge.Structure structure(AABB box) {
        return new SableStructureBridge.Structure(UUID.randomUUID(),null,true,null,null,box,null,null,List.of(),Map.of());
    }
    private static void check(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
}
