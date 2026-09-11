// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.border.WorldBorder;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.machine.MachineBuildTaskRecord;
import org.maiwithu.maicraft.core.integration.machine.MachineConstructionPlan;
import org.maiwithu.maicraft.core.integration.machine.layout.SemanticMachineLayout;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator.MaterialPolicy;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** Exercises real descendant constructor capture and placement preflight, without placing blocks or opening MCP. */
public final class ProductionConstructionProtectionTest {
    private static final BlockPos TARGET = new BlockPos(4, 1, 4), USER_PROTECTED = new BlockPos(10, 1, 10);

    public static void main(String[] args) throws Exception {
        java.io.PrintStream errors = System.err;
        java.io.PrintStream output = System.out;
        try {
            net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
            preflightPreservesOnlyInheritedUserProtection(false, false);
            preflightPreservesOnlyInheritedUserProtection(true, false);
            preflightPreservesOnlyInheritedUserProtection(false, true);
            output.println("ProductionConstructionProtectionTest passed");
        } catch (Throwable failure) {
            failure.printStackTrace(errors); throw new IllegalStateException("Production protection regression failed", failure);
        }
    }

    private static void preflightPreservesOnlyInheritedUserProtection(boolean userProtectsTarget, boolean childFails) throws Exception {
        try (var harness = new InteractionWorldTestHarness()) {
            field(Level.class, "worldBorder").set(harness.level, new WorldBorder());
            Object chunks = field(harness.level.getClass(), "chunks").get(harness.level);
            Object chunk = field(chunks.getClass(), "chunk").get(chunks);
            field(chunk.getClass(), "level").set(chunk, harness.level);
            field(net.minecraft.world.level.chunk.ChunkAccess.class, "levelHeightAccessor").set(chunk, harness.level);
            harness.inventory.setItem(0, new ItemStack(Items.STONE, 1));
            var layout = new SemanticMachineLayout.Result(true, JsonParser.parseString("""
                    {"blocks":[{"offset":[0,0,0],"block_id":"minecraft:stone"}]}
                    """).getAsJsonObject(), new JsonObject());
            var constructionPlan = MachineConstructionPlan.compile(TARGET, layout, false);
            var construction = new MachineBuildTaskRecord("protection-child", 1000, constructionPlan,
                    "minecraft:overworld", MaterialPolicy.INVENTORY_ONLY, List.of());
            var record = new MachineProductionTaskRecord("protection-parent", 1000,
                    new ProductionRunPlan(TARGET, "minecraft:overworld", production()), construction, List.of());
            var root = new MachineProductionTask(harness.player, record);
            var target = constructionPlan.blocks().getFirst();
            var child = new PreflightDescendant(new BuildTaskRecord("actual-build-preflight", 1000,
                    List.of(target), false, false), childFails);
            // Arrange only the active construction boundary; assertions exercise the actual Root call and build validation.
            Field phase = field(MachineProductionTask.class, "phase");
            boolean arranged = false;
            for (Object value : phase.getType().getEnumConstants()) if (((Enum<?>) value).name().equals("BUILD")) {
                phase.set(root, value); arranged = true;
            }
            check(arranged, "Construction boundary is required; this fixture never starts backend negotiation");
            field(MachineProductionTask.class, "construction").set(root, child);
            BlockPos protectedCell = userProtectsTarget ? TARGET : USER_PROTECTED;
            var before = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(NavigationSafetyContext.protectedMutationCells());
            NavigationSafetyContext.withProtectedArea(List.of(protectedCell), List.of(), () -> {
                TaskState state = root.tick(harness.player);
                check(state == (childFails ? TaskState.FAILED : TaskState.RUNNING), "Root must execute the supplied construction child");
                check(NavigationSafetyContext.protectsMutation(protectedCell), "Root must not clear inherited user protection");
                if (!userProtectsTarget) check(!NavigationSafetyContext.protectsMutation(TARGET), "Root context must unwind to the user's original scope");
                return null;
            });
            check(NavigationSafetyContext.protectedMutationCells().equals(before), "Normal and failed child execution must restore the outer thread context");
            check(child.builder != null, "The real build descendant must have been constructed inside Root BUILD");
            LongSet inherited = (LongSet) field(child.builder.getClass(), "inheritedProtectedMutationCells").get(child.builder);
            check(inherited.contains(protectedCell.asLong()), "Descendant must retain the user's constructor-time protection");
            // Run after the parent scope has unwound: cached inherited protection must still govern real preflight.
            var inspect = child.builder.getClass().getDeclaredMethod("inspectPrimary", BuildTaskRecord.Target.class);
            inspect.setAccessible(true); inspect.invoke(child.builder, target);
            @SuppressWarnings("unchecked") var blocked = (List<Map<String, Object>>) field(child.builder.getClass(), "blocked").get(child.builder);
            boolean denied = blocked.stream().anyMatch(row -> "inherited_semantic_area_protection".equals(row.get("code"))
                    && TARGET.toShortString().equals(row.get("pos")));
            check(denied == userProtectsTarget, "Planned cells may be built, but a user-protected target must still be rejected by actual preflight");
            check(harness.blockUses() == 0 && harness.itemUses() == 0 && harness.level.getBlockState(TARGET).isAir(), "This regression must remain read-only");
        }
    }

    private static JsonObject production() {
        return JsonParser.parseString("""
                {"schema_version":1,"nodes":[
                  {"id":"process","kind":"process","offset":[0,0,0],"recipe_id":"test:stone","batches":3},
                  {"id":"sink","kind":"sink","offset":[1,0,0]}],
                 "ports":[{"id":"out","node":"process","offset":[0,0,0],"face":"east","medium":"items","direction":"output"},
                   {"id":"in","node":"sink","offset":[1,0,0],"face":"west","medium":"items","direction":"input"}],
                 "links":[{"id":"delivery","from":"out","to":"in","medium":"items","resource":"minecraft:stone","amount":3,"path":[[0,0,0],[1,0,0]]}],
                 "configurations":[],"target":{"node":"sink","medium":"items","resource":"minecraft:stone"},
                 "observation":{"window_ticks":20,"minimum_output":3,"minimum_events":3,"max_idle_ticks":20}}
                """).getAsJsonObject();
    }

    private static final class PreflightDescendant implements Task {
        private final BuildTaskRecord plan;
        private final boolean fail;
        private Task builder;
        private PreflightDescendant(BuildTaskRecord plan, boolean fail) { this.plan = plan; this.fail = fail; }
        @Override public void start(LocalPlayer player) {
            try {
                Class<?> type = Class.forName("org.maiwithu.maicraft.core.task.build.FirstPersonBuildCompanionTask");
                var constructor = type.getDeclaredConstructor(LocalPlayer.class, BuildTaskRecord.class); constructor.setAccessible(true);
                builder = (Task) constructor.newInstance(player, plan);
            } catch (ReflectiveOperationException invalid) {
                throw new IllegalStateException("Could not construct actual build descendant", invalid);
            }
        }
        @Override public TaskState tick(LocalPlayer player) {
            if (fail) throw new IllegalStateException("fixture child failure after constructor capture");
            return TaskState.RUNNING;
        }
        @Override public void stop(LocalPlayer player, StopReason reason) {}
        @Override public String name() { return "actual build preflight descendant"; }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Field result = current.getDeclaredField(name); result.setAccessible(true); return result; }
            catch (NoSuchFieldException inherited) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
