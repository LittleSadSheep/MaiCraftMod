// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;

/** Actual production approach/observe paths; only walking arrival and fixture movement are controlled. */
public final class ProductionInteractionNavigationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            field(Level.class, "worldBorder").set(h.level, new WorldBorder());
            BlockPos first = new BlockPos(3, 1, 8), second = new BlockPos(11, 1, 8);
            h.set(first, Blocks.STONE.defaultBlockState()); h.set(second, Blocks.STONE.defaultBlockState());
            h.position(new Vec3(10.5, 1, 8.5));
            var routes = new ArrayList<BlockPos>();
            var arrivalChecks = new ArrayList<java.util.function.BooleanSupplier>();
            MachineProductionTask task = new MachineProductionTask(h.player, record(), (stance, reached) -> {
                routes.add(stance);
                arrivalChecks.add(reached);
                // First route finishes at its real stance; later routes expose a premature native ARRIVED.
                if (routes.size() == 1) {
                    try { h.position(Vec3.atBottomCenterOf(stance)); }
                    catch (Exception failure) { throw new IllegalStateException(failure); }
                }
                return PlayerNav.toGoal(h.player, () -> NavGoal.exact(stance), 1, () -> true).walkingOnly();
            });
            check(task.approach(first), "the first machine must acquire and retain its own visible interaction stance");
            BlockPos oldStance = (BlockPos) field(task.getClass(), "interactionStance").get(task);
            check(oldStance != null && routes.size() == 1, "first approach must exercise native stance selection");
            check(task.observe(second), "the second machine is within observation range before interaction range");
            check(field(task.getClass(), "navigationTarget").get(task).equals(second)
                            && field(task.getClass(), "interactionStance").get(task) == null,
                    "observing another target must invalidate the old target's interaction stance immediately");
            check(arrivalChecks.getFirst().getAsBoolean(),
                    "an old navigation callback must retain its own immutable stance after target switching clears the task cache");
            check(ProductionInteractionSight.visible(h.level, h.player, second), "the second machine is visible despite being too far to operate");
            check(!task.approach(second), "native ARRIVED cannot authorize a distant machine operation");
            BlockPos newStance = routes.getLast();
            check(routes.size() == 2 && !newStance.equals(oldStance)
                            && Vec3.atBottomCenterOf(newStance).distanceToSqr(second.getCenter()) <= 9,
                    "approach after observation must select a close stance for the second machine, never route back to the first");
            @SuppressWarnings("unchecked") var rejected = (java.util.Set<Long>) field(task.getClass(), "rejectedStances").get(task);
            check(rejected.contains(newStance.asLong()), "premature arrival must release that stance for bounded alternatives");
            var requests = (ProductionRequestSlot) field(task.getClass(), "requests").get(task);
            check(!requests.pending() && requests.report().isEmpty(), "navigation cannot enqueue a remote effect before range is valid");
            var observationRoute = PlayerNav.toGoal(h.player, () -> NavGoal.near(second, 10), 1, () -> false).walkingOnly();
            field(task.getClass().getSuperclass(), "nav").set(task, observationRoute);
            check(!task.approach(second) && routes.size() == 3,
                    "an existing observation-range route must be replaced by an interaction stance route");
            h.position(Vec3.atBottomCenterOf(routes.getLast()));
            check(task.approach(second), "real in-range visible arrival remains usable");
            check(h.blockUses() == 0 && h.itemUses() == 0, "the regression performs only navigation/readiness checks");
        }
        System.out.println("ProductionInteractionNavigationTest: target-scoped stances and strict arrival range passed");
    }

    private static MachineProductionTaskRecord record() {
        var manifest = JsonParser.parseString("""
                {"schema_version":1,"nodes":[
                  {"id":"process","kind":"process","offset":[0,0,0],"recipe_id":"test:stone","batches":3},
                  {"id":"sink","kind":"sink","offset":[1,0,0]}],
                 "ports":[{"id":"out","node":"process","offset":[0,0,0],"face":"east","medium":"items","direction":"output"},
                   {"id":"in","node":"sink","offset":[1,0,0],"face":"west","medium":"items","direction":"input"}],
                 "links":[{"id":"delivery","from":"out","to":"in","medium":"items","resource":"minecraft:stone","amount":3,"path":[[0,0,0],[1,0,0]]}],
                 "configurations":[],"target":{"node":"sink","medium":"items","resource":"minecraft:stone"},
                 "observation":{"window_ticks":20,"minimum_output":3,"minimum_events":3,"max_idle_ticks":20}}
                """).getAsJsonObject();
        return new MachineProductionTaskRecord("interaction-navigation", 1000,
                new ProductionRunPlan(BlockPos.ZERO, "minecraft:overworld", manifest), null, List.of());
    }
    private static Field field(Class<?> type, String name) throws Exception { var field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
