// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;

/** 只检验已读取日志和冻结物品的对账规则；不生成实体、不调用配方 assemble，也不代替真实生成与本人拾取验收。 */
public final class WorldProcessEventEvidenceTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var f = new Fixture()) {
            var event = f.event(); var output = f.confirm(event);
            check(output != null && output.uuid().equals(f.outputId) && output.stack().getCount() == 3
                            && output.stack().has(DataComponents.CUSTOM_NAME),
                    "a verified native assembly may add components beyond the recipe's output template");
            output.stack().remove(DataComponents.CUSTOM_NAME); event.getAsJsonArray("outputs").get(0).getAsJsonObject().getAsJsonObject("identity").add("components", new JsonObject());
            check(output.stack().has(DataComponents.CUSTOM_NAME), "caller edits and later event changes cannot rewrite confirmed output evidence");

            f.reject(e -> e.addProperty("recipe_id", "test:other"), "recipe_mismatch");
            f.reject(e -> e.getAsJsonArray("consumed_entities").get(0).getAsJsonObject().addProperty("amount", 1), "input_entities_mismatch");
            f.reject(e -> e.getAsJsonArray("consumed_entities").add(entity(UUID.randomUUID(), 1)), "input_entities_mismatch");
            f.reject(e -> e.getAsJsonArray("consumed_entities").add(entity(f.first, 1)), "input_entity_invalid");
            f.reject(e -> e.getAsJsonObject("position").addProperty("x", 9), "producer_mismatch");
            f.reject(e -> e.addProperty("producer", "99,99,99"), "producer_mismatch");
            f.reject(e -> e.addProperty("operations", 2), "operation_count_mismatch");
            f.reject(e -> e.addProperty("completed", false), "native_spawn_provenance_mismatch");
            f.reject(e -> e.addProperty("native_call", "ordinary.production.log"), "native_spawn_provenance_mismatch");
            f.reject(e -> e.remove("output_entity_uuid"), "output_entity_missing");
            f.reject(e -> e.addProperty("output_entity_uuid", "invalid"), "output_entity_missing");
            f.reject(e -> e.getAsJsonArray("outputs").set(0, f.resource(new ItemStack(Items.DIAMOND, 3))), "output_item_or_quantity_mismatch");
            f.reject(e -> e.getAsJsonArray("outputs").get(0).getAsJsonObject().addProperty("amount", 2), "output_item_or_quantity_mismatch");
            f.reject(e -> e.getAsJsonArray("outputs").add(f.resource(new ItemStack(Items.BRICK))), "output_shape_mismatch");
            f.reject(e -> e.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("resource_id", "forged"), "resource_identity_invalid");
            f.reject(e -> {
                var different = f.inputs.getFirst().copy(); different.set(DataComponents.CUSTOM_NAME, Component.literal("不同原料"));
                e.getAsJsonArray("inputs").set(0, f.resource(different));
            }, "input_resources_mismatch");
            f.reject(e -> e.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("amount", 0.5), "amount_invalid");

            var unrelated = f.event(); JsonArray others = new JsonArray(); others.add(entity(UUID.randomUUID(), 3));
            unrelated.add("consumed_entities", others); unrelated.remove("kind"); unrelated.addProperty("recipe_id", "test:old");
            check(f.confirm(unrelated) == null, "a replay with no current-batch UUID intersection is ignored before unrelated metadata is checked");
            var plainLog = f.event(); plainLog.remove("consumed_entities"); plainLog.remove("output_entity_uuid");
            check(f.confirm(plainLog) == null, "ordinary production logs without entity attribution cannot become batch evidence");
        }
        System.out.println("WorldProcessEventEvidenceTest: batch UUID, components, provenance, producer and defensive output guards passed");
    }

    private static final class Fixture implements AutoCloseable {
        final InteractionWorldTestHarness world = new InteractionWorldTestHarness();
        final RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        final UUID first = UUID.randomUUID(), second = UUID.randomUUID(), outputId = UUID.randomUUID();
        final BlockPos producer = new BlockPos(5, 1, 5);
        final Map<UUID, Integer> expected = Map.of(first, 2, second, 1);
        final List<ItemStack> inputs;
        final WorldProcessRecipe recipe;

        Fixture() throws Exception {
            var registryField = Level.class.getDeclaredField("registryAccess"); registryField.setAccessible(true); registryField.set(world.level, registries);
            ItemStack named = new ItemStack(Items.IRON_INGOT); named.set(DataComponents.CUSTOM_NAME, Component.literal("原来的材料"));
            inputs = List.of(named, named.copy(), new ItemStack(Items.CLAY_BALL));
            JsonObject fluid = new JsonObject(); fluid.addProperty("type", "fluid"); fluid.addProperty("tag", "minecraft:water");
            recipe = new NativeTransformRecipes.Recipe(ResourceLocation.parse("test:repeated_inputs"),
                    List.of(Ingredient.of(Items.IRON_INGOT), Ingredient.of(Items.IRON_INGOT), Ingredient.of(Items.CLAY_BALL)),
                    new ItemStack(Items.BRICK, 3), fluid, state -> state.getType().isSame(Fluids.WATER), registries);
        }

        JsonObject event() {
            var event = new JsonObject(); event.addProperty("kind", "recipe_output"); event.addProperty("provenance", "native_recipe_output");
            event.addProperty("completed", true); event.addProperty("native_call", "ae2.transform.tryTransform"); event.addProperty("operations", 1);
            event.addProperty("recipe_id", recipe.id().toString()); event.addProperty("producer", "5,1,5");
            JsonObject position = new JsonObject(); position.addProperty("x", 5); position.addProperty("y", 1); position.addProperty("z", 5); event.add("position", position);
            JsonArray consumed = new JsonArray(); consumed.add(entity(first, 2)); consumed.add(entity(second, 1)); event.add("consumed_entities", consumed);
            event.addProperty("output_entity_uuid", outputId.toString());
            JsonArray ingredients = new JsonArray(); inputs.forEach(stack -> ingredients.add(resource(stack))); event.add("inputs", ingredients);
            ItemStack result = recipe.result(); result.set(DataComponents.CUSTOM_NAME, Component.literal("原生赋予的成品组件"));
            JsonArray outputs = new JsonArray(); outputs.add(resource(result)); event.add("outputs", outputs); return event;
        }
        JsonObject resource(ItemStack stack) {
            var row = new JsonObject(); var identity = ResourceIdentity.item(stack, registries);
            row.add("identity", identity); row.addProperty("resource_id", ResourceIdentity.key(identity)); row.addProperty("amount", stack.getCount()); return row;
        }
        WorldProcessEventEvidence.Output confirm(JsonObject event) {
            return WorldProcessEventEvidence.confirm(world.player, event, recipe, expected, inputs, Set.of(producer));
        }
        void reject(Consumer<JsonObject> edit, String reason) {
            var event = event(); edit.accept(event);
            try { confirm(event); throw new AssertionError("expected " + reason); }
            catch (IllegalArgumentException expected) { check(expected.getMessage().contains(reason), "failure must identify " + reason + ": " + expected.getMessage()); }
        }
        @Override public void close() throws Exception { world.close(); }
    }
    private static JsonObject entity(UUID id, int count) {
        JsonObject row = new JsonObject(); row.addProperty("entity_uuid", id.toString()); row.addProperty("amount", count); return row;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
