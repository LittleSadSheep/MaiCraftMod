// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSets;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** Plan access for a wall batch instead of navigating to hundreds of floating stances. */
public final class BuildSharedWorksiteTest {
    public static void main(String[] args) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var dimensions = net.minecraft.world.entity.Entity.class.getDeclaredField("dimensions"); dimensions.setAccessible(true);
            dimensions.set(h.player, net.minecraft.world.entity.EntityDimensions.scalable(.6F, 1.8F));
            h.position(new Vec3(3.5, 1, 8.5));
            var targets = new ArrayList<BuildTaskRecord.Target>();
            for (int z = 6; z <= 10; z++) {
                for (int y = 1; y <= 3; y++) h.set(new BlockPos(6, y, z), Blocks.STONE.defaultBlockState());
                targets.add(new BuildTaskRecord.Target(Blocks.STONE, Items.STONE, new BlockPos(6, 4, z), "wall course", null, null, null));
            }
            var map = new LinkedHashMap<Long, BuildTaskRecord.Target>(); targets.forEach(t -> map.put(t.pos().asLong(), t));
            var existing = new BuildWorksitePlanner.Search(h.player, targets, map, p -> true,
                    LongSets.emptySet(), Set.of(), (t, g) -> true);
            check(finish(existing).best() == null, "the vertical wall has no existing route up from ground level");
            var access = new BuildWorksitePlanner.Search(h.player, targets, map, p -> true,
                    LongSets.emptySet(), Set.of(), (t, g) -> true, 2,
                    p -> h.level.isLoaded(p) && h.level.isLoaded(p.below()) && !map.containsKey(p.below().asLong()));
            var site = finish(access).best();
            check(site != null && site.constructionAccess() && site.coverage() == targets.size(),
                    "one proposed access point must serve the whole wall course");
            check(site.feet().y == 4, "access permits stepping onto the new blocks without an unnecessary height level");
            check(h.blockUses() == 0, "access scoring cannot build a hypothetical floor");

            var task = new FirstPersonBuildCompanionTask(h.player,
                    new BuildTaskRecord("shared-wall-access", 1000, targets, false, false));
            var cellType = Class.forName(FirstPersonBuildCompanionTask.class.getName() + "$CellPlan");
            var constructor = cellType.getDeclaredConstructor(BuildTaskRecord.Target.class, List.class); constructor.setAccessible(true);
            var queue = new ArrayList<Object>(); for (var target : targets) queue.add(constructor.newInstance(target, List.of()));
            field(task, "queue").set(task, queue); field(task, "cell").set(task, queue.getFirst());
            var step = FirstPersonBuildCompanionTask.class.getDeclaredMethod("worksiteTick"); step.setAccessible(true);
            for (int tick = 0; tick < 10_000 && field(task, "worksite").get(task) == null; tick++) step.invoke(task);
            var selected = (BuildWorksitePlanner.Worksite) field(task, "worksite").get(task);
            check(selected != null && selected.constructionAccess() && selected.coverage() == 5,
                    "the task switches empty existing-worksite searches to shared construction access");
            check(field(task, "nav").get(task) == null && h.blockUses() == 0,
                    "choose a useful batch before submitting any route or block action");
            var routes = (BuildStanceNavigation) field(task, "stanceNavigation").get(task);
            check(routes.stage().equals("construction_access") && routes.evidence().get("route_attempts").equals(0),
                    "unavailable upper stances must not generate individual existing-footing route requests");
        }
        boundedRoutesRetainSupportFallback();
        BuildWorksiteProgressTest.main(args);
        System.out.println("BuildSharedWorksiteTest: shared wall access and bounded repeated movement passed");
    }
    private static void boundedRoutesRetainSupportFallback() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var dimensions = net.minecraft.world.entity.Entity.class.getDeclaredField("dimensions"); dimensions.setAccessible(true);
            dimensions.set(h.player, net.minecraft.world.entity.EntityDimensions.scalable(.6F, 1.8F));
            h.inventory.setItem(0,new net.minecraft.world.item.ItemStack(Items.COBBLESTONE,32));
            var target=new BuildTaskRecord.Target(Blocks.STONE,Items.STONE,new BlockPos(8,4,8),"floating course",null,null,null);
            var task=new FirstPersonBuildCompanionTask(h.player,new BuildTaskRecord("support-fallback",1000,List.of(target),false,false));
            var type=Class.forName(FirstPersonBuildCompanionTask.class.getName()+"$CellPlan");
            var constructor=type.getDeclaredConstructor(BuildTaskRecord.Target.class,List.class);constructor.setAccessible(true);
            field(task,"cell").set(task,constructor.newInstance(target,List.of()));
            field(task,"worksitePass").setInt(task,2);field(task,"worksiteAttempts").setInt(task,4);
            var advance=FirstPersonBuildCompanionTask.class.getDeclaredMethod("nextWorksitePass",String.class);advance.setAccessible(true);
            Object state=advance.invoke(task,"bounded shared routes exhausted");
            check(state==org.maiwithu.maicraft.task.TaskState.RUNNING&&field(task,"phase").get(task).toString().equals("SUPPORT_VERIFY"),
                    "four failed route attempts must still reach the separately bounded support verification");
            check(h.blockUses()==0&&h.itemUses()==0,"support fallback must be proved before placing anything");
        }
    }
    private static BuildWorksitePlanner.Progress finish(BuildWorksitePlanner.Search search) {
        for (int tick = 0; tick < 10_000; tick++) { var result = search.advance(256); if (result.complete()) return result; }
        throw new AssertionError("bounded worksite evaluation did not finish");
    }
    private static Field field(Object owner, String name) throws Exception {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException inherited) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
