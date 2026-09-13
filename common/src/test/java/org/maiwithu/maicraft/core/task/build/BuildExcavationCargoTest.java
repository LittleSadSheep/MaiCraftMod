package org.maiwithu.maicraft.core.task.build;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

public final class BuildExcavationCargoTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            h.inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            h.inventory.setItem(1, new ItemStack(Items.DIAMOND_PICKAXE));
            h.inventory.setItem(2, new ItemStack(Items.BREAD, 16));
            var record = new BuildTaskRecord("cargo", 1000, List.of(), true);
            var cargo = record.excavationCargo(); cargo.begin(h.player);
            cargo.observedTerrain(Blocks.STONE.defaultBlockState());
            cargo.observedTerrain(Blocks.DIRT.defaultBlockState());
            h.inventory.setItem(3, new ItemStack(Items.COBBLESTONE, 64));
            h.inventory.setItem(4, new ItemStack(Items.COBBLESTONE, 16));
            h.inventory.setItem(5, new ItemStack(Items.DIRT, 32));
            var result = cargo.unloadable(h.player, Map.of(Items.COBBLESTONE, 96, Items.DIRT, 40));
            check(result.equals(Map.of(ResourceLocation.parse("minecraft:cobblestone"), 48)),
                    "unloading keeps the original 64, all 96 construction needs, food and tools; insufficient dirt stays");
            var next = new BuildTaskRecord("next-batch", 1000, List.of(), true);
            record.copyExecutionContextTo(next);
            next.excavationCargo().begin(h.player);
            check(next.excavationCargo().unloadable(h.player, Map.of(Items.COBBLESTONE, 96, Items.DIRT, 40)).equals(result),
                    "a material batch must not reinterpret already collected spoil as initial possessions");
            h.inventory.getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("kept stone"));
            check(!cargo.unloadable(h.player, Map.of()).containsKey(ResourceLocation.parse("minecraft:cobblestone")),
                    "item-ID-only container transfers cannot accidentally choose a customized preexisting stack");
            check(!cargo.capacityLow(h.player), "a few collected stacks do not trigger an unnecessary warehouse trip");
        }
        System.out.println("BuildExcavationCargoTest: protected starting stock, planned materials and batch continuity passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
