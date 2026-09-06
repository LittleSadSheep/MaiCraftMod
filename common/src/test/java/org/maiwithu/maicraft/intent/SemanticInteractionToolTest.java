package org.maiwithu.maicraft.intent;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.core.task.acquire.WorkToolPreparation.UseTool;
import org.maiwithu.maicraft.core.task.interact.InteractAtTaskRecord;
import org.maiwithu.maicraft.core.tools.BlockActionOps;

public final class SemanticInteractionToolTest {
    public static void main(String[] args) {
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

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
