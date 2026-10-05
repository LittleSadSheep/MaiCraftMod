// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import org.maiwithu.maicraft.client.actor.ClientActorBoundary;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;

/**
 * 回放两次游戏刻之间的配方知识读取：没有活跃身体上下文时，原版配方表遍历仍必须可用；
 * 连续多次读取不缓存“不可用”，配方表清空时报告读得到表但没有匹配、不证明缺席。
 */
public final class MachineRecipeEvidenceAvailabilityTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            var manager = new RecipeManager(RegistryAccess.EMPTY);
            manager.replaceRecipes(List.of(new RecipeHolder<>(ResourceLocation.parse("minecraft:furnace"),
                    new ShapedRecipe("", CraftingBookCategory.MISC, ShapedRecipePattern.of(
                            Map.of('C', Ingredient.of(Items.COBBLESTONE)), List.of("C", "C", "C")),
                            new ItemStack(Items.FURNACE)))));
            field(ClientPacketListener.class, "recipeManager").set(world.player.connection, manager);
            // 知识读取经客户端线程队列在两次游戏刻之间执行，此时上一刻的身体上下文已归还：清掉它复现真实读取时机。
            field(ClientActorBoundary.class, "activeContext").set(ClientRuntime.actor(), null);

            var first = MachineRecipeEvidence.inspect(world.player, "minecraft:furnace", false);
            check(first.get("available").getAsBoolean(),
                    "a between-tick knowledge read must still traverse the client recipe manager");
            check("matching_item_recipe_evidence".equals(first.get("status").getAsString()),
                    "a furnace recipe in the manager must surface as matching evidence");
            check(first.get("examined_recipe_count").getAsInt() >= 1
                    && first.get("matching_display_result_count").getAsInt() == 1
                    && first.get("emitted_recipe_count").getAsInt() == 1,
                    "the fallback must report examined and matched counts for the observed recipe");

            // 不可用状态不能被缓存：第二次读取照常完成完整遍历。
            var second = MachineRecipeEvidence.inspect(world.player, "minecraft:furnace", false);
            check(second.get("available").getAsBoolean()
                    && second.get("examined_recipe_count").getAsInt() >= 1,
                    "repeated reads must re-traverse instead of replaying a stale unavailable verdict");

            // 配方表清空是“读了没有”，不是“读不了”：仍报告表可用、扫描完整，且不证明世界中没有配方。
            manager.replaceRecipes(Collections.emptyList());
            var empty = MachineRecipeEvidence.inspect(world.player, "minecraft:furnace", false);
            check(empty.get("available").getAsBoolean()
                    && "no_matching_display_result_observed".equals(empty.get("status").getAsString())
                    && empty.get("scan_complete").getAsBoolean()
                    && !empty.get("absence_proven").getAsBoolean(),
                    "an empty manager still reads as available and does not prove absence");
            System.out.println("MachineRecipeEvidenceAvailabilityTest: passed");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
