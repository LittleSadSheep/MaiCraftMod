package org.maiwithu.maicraft.intent;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.core.task.acquire.WorkToolPreparation.UseTool;
import org.maiwithu.maicraft.core.task.interact.InteractAtTaskRecord;
import org.maiwithu.maicraft.core.tools.BlockActionOps;
import com.google.gson.JsonObject;
import java.util.Set;
import com.google.gson.JsonParser;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;

public final class SemanticInteractionToolTest {
    public static void main(String[] args) {
        equipmentLocationContract();
        try { manualDurationAndExactApproach(); exactVariantContract(); }
        catch (Exception failure) { throw new AssertionError("finite manual use and exact approach contract failed", failure); }
        var use = new IntentAction.Tool("interact_at", "{}");
        var walk = new IntentAction.Tool("goto", "{}");
        UseTool stone = new UseTool(ResourceLocation.withDefaultNamespace("stone_hoe"), false, false);
        var chain = (IntentAction.Chain) GeneralAbilityAdapter.prepareUseTool(stone, 1, false,
                new IntentAction.Chain(List.of(walk, use)));
        check(chain.actions().size() == 3 && chain.actions().get(1) == walk && chain.actions().get(2) == use,
                "tool preparation must precede the existing travel/use plan");
        var preparation = chain.actions().getFirst();
        check(preparation.toolName().equals("acquire_items") && preparation.arguments().get("count").getAsInt() == 2,
                "a worn existing hoe must not satisfy preparation for a replacement");
        check(preparation.arguments().getAsJsonArray("allowed_sources").toString().contains("mine"),
                "ordinary tool preparation can gather its small stone prerequisite");
        UseTool diamond = new UseTool(ResourceLocation.withDefaultNamespace("diamond_hoe"), false, true);
        var upgrade = (IntentAction.Chain) GeneralAbilityAdapter.prepareUseTool(diamond, 0, true, use);
        String sources = upgrade.actions().getFirst().arguments().getAsJsonArray("allowed_sources").toString();
        check(sources.contains("storage") && !sources.contains("mine"),
                "an optional upgrade cannot initiate new rare-ore mining");
        check(GeneralAbilityAdapter.prepareUseTool(stone, 0, false, IntentAction.Pending.INSTANCE)
                        == IntentAction.Pending.INSTANCE,
                "an unresolved target must not start tool acquisition");
        check(GeneralAbilityAdapter.prepareUseTool(new UseTool(stone.itemId(), true, false), 1, false, use) == use,
                "a carried suitable tool needs no redundant acquisition");
        InteractAtTaskRecord record = (InteractAtTaskRecord) new BlockActionOps().interactAt(
                "right", 0, 64, 0, 0, "minecraft:stone_hoe", "minecraft:farmland", new ToolContext("till", 0));
        check(record.item == Items.STONE_HOE && record.expectedBlock == Blocks.FARMLAND,
                "tilling must retain its actual world postcondition in the native task");
        try {
            new BlockActionOps().interactAt("right", null, null, null, 0,
                    "minecraft:stone_hoe", "minecraft:farmland", new ToolContext("invalid", 0));
            throw new AssertionError("a world postcondition needs a concrete target");
        } catch (IllegalArgumentException expected) { }
        System.out.println("SemanticInteractionToolTest: passed");
    }

    private static void exactVariantContract() throws Exception {
        // 公开语义目标到内部工具保持同一个已观察身份，不能在编译或参数转换时丢掉组件选择约束。
        try (var h = new InteractionWorldTestHarness()) {
            h.inventory.setItem(0, new ItemStack(Items.BRICK)); h.set(new BlockPos(0, 1, 0), Blocks.STONE.defaultBlockState());
            String key = ResourceIdentity.key(ResourceIdentity.base("items", "minecraft:brick"));
            var parameters = new JsonObject(); parameters.addProperty("block_id", "minecraft:stone");
            parameters.addProperty("item_id", "minecraft:brick"); parameters.addProperty("item_resource_id", key);
            Goal goal = new Goal("maicraft:interact", "使用已观察身份的工件", new Goal.SemanticTarget("coordinates", null,
                    new Goal.WorldPosition(0, 1, 0, "minecraft:overworld"), null), parameters.toString(), "{}", List.of(), List.of());
            SemanticGoalContract.validate(goal, Set.of("maicraft:interact"));
            var command = (IntentAction.Tool) AbilityAdapter.adapt(goal, h.player, null);
            check(command.arguments().get("item_resource_id").getAsString().equals(key), "semantic compilation preserves the exact item binding");
        }
    }

    private static void equipmentLocationContract() {
        for (String location : List.of("mainhand", "offhand", "head", "chest", "legs", "feet", "armor")) {
            var json = new JsonObject();
            json.addProperty("ability", GeneralAbilityAdapter.EQUIP);
            json.addProperty("outcome", "remove the requested equipment");
            var parameters = new JsonObject();
            parameters.addProperty("action", "unequip");
            parameters.addProperty("equipment_location", location);
            json.add("parameters", parameters);
            Goal goal = Goal.fromJson(json);
            SemanticGoalContract.validate(goal, Set.of(GeneralAbilityAdapter.EQUIP));
            IntentAction result = AbilityAdapter.adapt(goal, null, null);
            check(result instanceof IntentAction.Tool, "the complete ability entry point must preserve equipment_location until native compilation");
            var command = (IntentAction.Tool) result;
            check(command.toolName().equals("equip_item") && command.arguments().get("action").getAsString().equals("unequip")
                    && command.arguments().get("slot").getAsString().equals(location),
                    "semantic location must reach the internal native equipment action unchanged");
        }
    }

    private static void manualDurationAndExactApproach() throws Exception {
        var json = JsonParser.parseString("{ability:'maicraft:interact',outcome:'operate the selected generator',target:{kind:'coordinates',position:{x:10,y:1,z:10}},parameters:{purpose:'use',duration_seconds:5}}").getAsJsonObject();
        SemanticGoalContract.validate(Goal.fromJson(json), Set.of(GeneralAbilityAdapter.INTERACT));
        json.getAsJsonObject("parameters").addProperty("duration_seconds", -1);
        try { SemanticGoalContract.validate(Goal.fromJson(json), Set.of(GeneralAbilityAdapter.INTERACT)); throw new AssertionError("negative duration accepted"); }
        catch (IllegalArgumentException expected) { }
        json.getAsJsonObject("parameters").remove("duration_seconds");
        var held = (InteractAtTaskRecord) new BlockActionOps().interactAt("right", 0, 64, 0, 600, null, null, new ToolContext("finite-use", 0));
        check(held.getDeadlineGameTime() >= 1200, "the full requested duration still leaves time for hand preparation and the last acknowledgement");
        try (var h = new InteractionWorldTestHarness()) {
            var dimensions = Entity.class.getDeclaredField("dimensions"); dimensions.setAccessible(true);
            dimensions.set(h.player, EntityDimensions.scalable(.6F, 1.8F));
            BlockPos at = new BlockPos(10, 1, 10); h.set(at, Blocks.CRAFTING_TABLE.defaultBlockState());
            var compile = GeneralAbilityAdapter.class.getDeclaredMethod("compileBlockInteraction", Goal.class, LocalPlayer.class, BlockPos.class,
                    ResourceLocation.class, String.class, boolean.class); compile.setAccessible(true);
            var plan = (IntentAction.Chain) compile.invoke(null, Goal.fromJson(json), h.player, at,
                    ResourceLocation.withDefaultNamespace("crafting_table"), null, false);
            // 自己已经选出的工作站位必须精确抵达，不能让普通旅行容差把角色留在几格外。
            check(plan.actions().getFirst().toolName().equals("goto") && plan.actions().getFirst().arguments().get("exact").getAsBoolean(),
                    "semantic interaction uses the exact approach it proved rather than ordinary travel tolerances");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
