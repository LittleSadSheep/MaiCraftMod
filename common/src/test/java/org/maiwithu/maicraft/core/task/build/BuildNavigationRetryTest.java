// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.task.TaskState;

/** The production failure branch must skip every aim variant at an unreachable stance. */
public final class BuildNavigationRetryTest {
    public static void main(String[] args) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var target = new BuildTaskRecord.Target(Blocks.STONE, Items.STONE,
                    new BlockPos(12, 4, 12), "remote wall", null, null, null);
            h.set(target.pos().below(), Blocks.STONE.defaultBlockState());
            var task = new FirstPersonBuildCompanionTask(h.player,
                    new BuildTaskRecord("navigation-duplicates", 1000, List.of(target), false, false));
            var cellType = Class.forName(FirstPersonBuildCompanionTask.class.getName() + "$CellPlan");
            var constructor = cellType.getDeclaredConstructor(BuildTaskRecord.Target.class, List.class); constructor.setAccessible(true);
            Object cell = constructor.newInstance(target, List.of());
            field(task, "cell").set(task, cell); field(task, "queue").set(task, new ArrayList<>(List.of(cell)));
            field(task, "worksiteSearched").setBoolean(task, true);
            var stance = new BlockPos(10, 4, 12);
            var clicks = new ArrayList<BuildPlacementGeometry.Gesture>();
            for (int i = 0; i < 3; i++) clicks.add(new BuildPlacementGeometry.Gesture(stance, target.pos().below(),
                    Direction.UP, new Vec3(12.3 + i * .2, 4, 12.5), -90, 40, false, "fixture"));
            field(task, "liveGestures").set(task, clicks); field(task, "gesture").set(task, clicks.getFirst());
            var routes = (BuildStanceNavigation) field(task, "stanceNavigation").get(task);
            routes.startAt(PlayerNav.playerFeet(h.player));
            routes.forTarget(target.pos(), PlayerNav.playerFeet(h.player)); routes.attempted();
            // An already terminated native navigator produces FAILED without bootstrapping a live route.
            var nav = PlayerNav.toGoal(h.player, () -> NavGoal.exact(stance), 1, () -> false, routes.contextFor(stance));
            Object transport = field(nav, "navigator").get(nav); field(transport, "stopped").setBoolean(transport, true);
            field(task, "nav").set(task, nav);
            check(invoke(task, "placeNavTick") == TaskState.RUNNING, "failed navigation should move on within the construction task");
            check(!routes.allows(stance), "one failed route must reject all aim variants sharing that stance in the current pass");
            var diagnostic = (java.util.Map<?, ?>) task.progress().get("construction_navigation");
            check(diagnostic.get("route_attempts").equals(1) && diagnostic.get("failed_stances").equals(1)
                            && diagnostic.get("target_index").equals(0),
                    "live progress identifies the active target and route failures without reusing historical attempts");
            check(invoke(task, "placeNavTick") == TaskState.RUNNING && field(task, "nav").get(task) == null
                            && routes.stage().equals("existing_footing") && field(task, "gestureAt").getInt(task) == 0,
                    "duplicate aim points must be skipped before advancing to the next existing-footing pass");
            check(routes.allows(stance), "a relaxed navigation pass must reconsider the stance");
            check(h.blockUses() == 0 && h.itemUses() == 0, "failed-route filtering cannot issue placement or item-use actions");
        }
        System.out.println("BuildNavigationRetryTest: failed routes skip duplicate aim points and preserve the next pass");
    }
    private static Field field(Object owner, String name) throws Exception {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try { var field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException inherited) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static Object invoke(Object owner, String name) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(owner);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
