// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.core.pathing.baritone.GroundCorridor;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;

/** 真实落脚搜索先证明每一步，再只合并完全相同的直线；精确施工的禁跑意图必须一直传到原生导航执行器。 */
public final class BuildWorksiteRouteTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        preserveCornersStepsAndReversals();
        actualFootingRouteKeepsAllCollisionAndProtectionChecks();
        constructionPaceReachesTheNativeNavigator();
        System.out.println("BuildWorksiteRouteTest: passed");
    }

    private static void preserveCornersStepsAndReversals() {
        var straight = new ArrayList<Vec3>(); for (int x = 0; x <= 12; x++) straight.add(new Vec3(x + .5, 1, .5));
        check(BuildWorksiteRoute.compact(straight).equals(List.of(straight.getFirst(), straight.getLast())),
                "twelve proved flat grid edges become one continuous navigation segment");
        var corner = List.of(new Vec3(.5, 1, .5), new Vec3(1.5, 1, .5), new Vec3(2.5, 1, .5), new Vec3(2.5, 1, 1.5), new Vec3(2.5, 1, 2.5));
        check(BuildWorksiteRoute.compact(corner).equals(List.of(corner.getFirst(), corner.get(2), corner.getLast())),
                "turning keeps the original corner instead of cutting diagonally across unproved floor");
        var step = List.of(new Vec3(.5, 1, .5), new Vec3(1.5, 1, .5), new Vec3(2.5, 2, .5), new Vec3(3.5, 2, .5), new Vec3(4.5, 2, .5));
        check(BuildWorksiteRoute.compact(step).equals(List.of(step.getFirst(), step.get(1), step.get(2), step.getLast())),
                "the exact one-block ascent and its approach remain separate from flat walking");
        var reverse = List.of(straight.get(0), straight.get(1), straight.get(2), straight.get(1), straight.get(0));
        check(BuildWorksiteRoute.compact(reverse).equals(List.of(straight.get(0), straight.get(2), straight.get(0))), "a return route retains its turnaround point");
    }

    private static void actualFootingRouteKeepsAllCollisionAndProtectionChecks() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            field(Entity.class, "dimensions").set(h.player, EntityDimensions.scalable(.6F, 1.8F));
            h.position(new Vec3(3.5, 1, 8.5)); BlockPos destination = new BlockPos(12, 1, 8), middle = new BlockPos(8, 1, 8);
            var route = route(h, destination, LongSets.emptySet());
            check(route != null && route.points().equals(List.of(h.player.position(), Vec3.atBottomCenterOf(destination)))
                    && route.distance() == 9 && route.lowestY() == 1,
                    "the actual footing graph preserves distance and minimum height while eliminating per-block navigation restarts");
            check(clear(h, LongSets.emptySet(), route.points().getFirst(), route.points().getLast()), "the merged segment still passes the full native swept-body/floor proof");
            h.set(middle, Blocks.STONE.defaultBlockState()); h.set(middle.above(), Blocks.STONE.defaultBlockState());
            check(!clear(h, LongSets.emptySet(), route.points().getFirst(), route.points().getLast()), "a new physical obstacle still invalidates the formerly open straight corridor");
            var around = route(h, destination, LongSets.emptySet());
            check(around != null && around.points().size() >= 4, "a real obstacle retains the detour corners");
            for (int i = 1; i < around.points().size(); i++) check(clear(h, LongSets.emptySet(), around.points().get(i - 1), around.points().get(i)),
                    "no merged detour leg may cross the physical obstacle");
            h.set(middle, Blocks.AIR.defaultBlockState()); h.set(middle.above(), Blocks.AIR.defaultBlockState());
            var forbidden = new LongOpenHashSet(); forbidden.add(middle.asLong());
            var protectedRoute = route(h, destination, forbidden);
            check(protectedRoute != null && protectedRoute.points().size() >= 4, "a forbidden body cell remains a detour even when visually empty");
            for (int i = 1; i < protectedRoute.points().size(); i++) check(clear(h, forbidden, protectedRoute.points().get(i - 1), protectedRoute.points().get(i)),
                    "compaction cannot remove a live protection constraint");
            check(h.blockUses() == 0 && h.itemUses() == 0, "route planning neither changes terrain nor moves inventory");
        }
    }

    private static void constructionPaceReachesTheNativeNavigator() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var policy = new BuildStanceNavigation(PlayerNav.ContextProvider.TERRAFORM).walkingContext(1);
            var precise = PlayerNav.toGoal(h.player, () -> NavGoal.exact(new BlockPos(6, 1, 6)), BuildStanceNavigation.PRECISE_WALK, () -> false, policy).walkingOnly();
            var warehouse = PlayerNav.toGoal(h.player, () -> NavGoal.exact(new BlockPos(6, 1, 6)), 1.0, () -> false, PlayerNav.ContextProvider.DEFAULT).walkingOnly();
            // 检查真正创建出的两层执行器字段，防止只改估算值或误把 walkingOnly 当成禁疾跑。
            Object walking = field(PlayerNav.class, "navigator").get(precise), travel = field(PlayerNav.class, "navigator").get(warehouse);
            check(!(boolean) field(walking.getClass(), "sprint").get(walking) && (boolean) field(travel.getClass(), "sprint").get(travel),
                    "precise construction disables sprint while ordinary warehouse travel keeps it enabled");
            Object groundWalk = field(walking.getClass(), "ground").get(walking), groundTravel = field(travel.getClass(), "ground").get(travel);
            check(!(boolean) field(groundWalk.getClass(), "sprintAllowed").get(groundWalk)
                    && (boolean) field(groundTravel.getClass(), "sprintAllowed").get(groundTravel),
                    "the distinction reaches the actual embedded native executor rather than stopping at a planner or transport hint");
            check(policy.permit() == TerrainPermit.PRESERVE && policy.minimumFeetY() == 1,
                    "walking pace does not widen terrain permission or erase the retained-height floor");
        }
    }

    private static BuildFootingSearch.Route route(InteractionWorldTestHarness h, BlockPos destination, LongSet forbidden) {
        var target = new BuildTaskRecord.Target(Blocks.AIR, Items.AIR, destination, "route destination", null, null, null);
        var search = new BuildFootingSearch(h.player, List.of(target), forbidden);
        for (int i = 0; i < 10000; i++) if (search.advance()) return search.route(destination);
        throw new AssertionError("bounded footing search did not finish");
    }
    private static boolean clear(InteractionWorldTestHarness h, LongSet forbidden, Vec3 from, Vec3 to) {
        return new GroundCorridor(h.level, h.level::isLoaded, .6, 1.8, forbidden, PhysicalObstacleSnapshot.EMPTY).clear(from, to);
    }
    private static Field field(Class<?> owner, String name) throws Exception { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
