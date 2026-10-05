// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 金属与建材冶炼（铜锭、石头）与熟食走同一炉子通路：成品能不能立项只取决于
 * 原料、燃料、设备是否备得齐，不看成品是不是食物；燃料不够时点名燃料层与数量，
 * 不再用"配方存在但没有受支持路径"的合并话术。
 */
public final class CookingNonFoodSmeltingTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        copperSmeltingReachesPreparation();
        stoneSmeltingWalksSamePath();
        fuelShortfallNamesTheFuelLayer();
        System.out.println("CookingNonFoodSmeltingTest: passed");
    }

    // 006 局实机对照的铜线场景：raw_copper 11 + 木棍 22 + 已置炉，立项应选定熔炉烧铜并进入备料。
    private static void copperSmeltingReachesPreparation() throws Exception {
        try (var world = new CookingTestWorld()) {
            world.recipes(List.of(smelting("copper_ingot", Items.RAW_COPPER, Items.COPPER_INGOT)));
            stock(world, new ItemStack(Items.RAW_COPPER, 11), new ItemStack(Items.STICK, 22));
            var task = new SemanticCookCompanionTask(world.game.player,
                    request(id("copper_ingot"), 11, SemanticCookTaskRecord.Preference.SMELTING,
                            List.of(id("stick")), List.of(Source.INVENTORY)));
            task.start(world.game.player);
            check(task.tick(world.game.player) == TaskState.RUNNING, "铜锭立项不得直接失败");
            CookingRecipe candidate = (CookingRecipe) CookingTestWorld.read(task, "candidate");
            check(candidate != null && candidate.input() == Items.RAW_COPPER
                    && candidate.device() == CookingDevice.FURNACE, "应选定熔炉烧粗铜的配方");
            check(CookingTestWorld.read(task, "fuel") == Items.STICK, "燃料应选显式允许的木棍");
            world.game.nextTick();
            check(task.tick(world.game.player) == TaskState.RUNNING, "备料阶段继续推进");
            check((int) CookingTestWorld.read(task, "batchRaw") == 11, "本炉应一次装下 11 份粗铜");
            check(world.game.blockUses() == 0 && world.game.itemUses() == 0, "立项与备料估价不得提前操作方块或炉子");
        }
    }

    // 建材线同通路：圆石烧石头，煤气充足时同样只看备得齐不备得齐。
    private static void stoneSmeltingWalksSamePath() throws Exception {
        try (var world = new CookingTestWorld()) {
            world.recipes(List.of(smelting("stone", Items.COBBLESTONE, Items.STONE)));
            stock(world, new ItemStack(Items.COBBLESTONE, 8), new ItemStack(Items.COAL, 2));
            var task = new SemanticCookCompanionTask(world.game.player,
                    request(id("stone"), 4, SemanticCookTaskRecord.Preference.SMELTING,
                            List.of(id("coal")), List.of(Source.INVENTORY)));
            task.start(world.game.player);
            check(task.tick(world.game.player) == TaskState.RUNNING, "石头立项不得直接失败");
            CookingRecipe candidate = (CookingRecipe) CookingTestWorld.read(task, "candidate");
            check(candidate != null && candidate.input() == Items.COBBLESTONE
                    && candidate.device() == CookingDevice.FURNACE, "应选定熔炉烧圆石的配方");
            check(CookingTestWorld.read(task, "fuel") == Items.COAL, "燃料应选显式允许的煤");
        }
    }

    // 006 局真实病灶：目标是 11 锭但只有 2 支木棍（烧 11 锭需 22 支）。失败必须点名燃料层、
    // 需要 22、可见 2，且原料与设备不背锅；不允许回到"none has a supported path"合并话术。
    private static void fuelShortfallNamesTheFuelLayer() throws Exception {
        try (var world = new CookingTestWorld()) {
            world.recipes(List.of(smelting("copper_ingot", Items.RAW_COPPER, Items.COPPER_INGOT)));
            stock(world, new ItemStack(Items.RAW_COPPER, 11), new ItemStack(Items.STICK, 2));
            var task = new SemanticCookCompanionTask(world.game.player,
                    request(id("copper_ingot"), 11, SemanticCookTaskRecord.Preference.SMELTING,
                            List.of(id("stick")), List.of(Source.INVENTORY)));
            task.start(world.game.player);
            check(task.tick(world.game.player) == TaskState.RUNNING, "立项失败也要先交付一次运行");
            world.game.nextTick();
            check(task.tick(world.game.player) == TaskState.FAILED, "燃料不够时任务如实失败");
            check("no_reachable_cooking_plan".equals(CookingTestWorld.read(task, "failureCode")),
                    "失败码保持可检索的立项失败");
            String message = String.valueOf(CookingTestWorld.read(task, "failureMessage"));
            check(message.contains("fuel blocked") && message.contains("22x " + id("stick"))
                    && message.contains("only 2 reachable"), "话术必须点名燃料层并给出需要量与可见量");
            check(!message.contains("none has a supported path"), "不再使用合并话术");
            @SuppressWarnings("unchecked")
            Map<String, Object> diagnosis =
                    (Map<String, Object>) CookingTestWorld.read(task, "planningDiagnosis");
            check(diagnosis != null && !diagnosis.isEmpty(), "回执必须带结构化诊断表");
            check(List.of("fuel").equals(diagnosis.get("blocked_layers")), "阻碍层应只有燃料");
            check(Integer.valueOf(22).equals(diagnosis.get("fuel_required")), "燃料需要量应为 22 支");
            check(Long.valueOf(2L).equals(diagnosis.get("fuel_reachable")), "可见燃料应为 2 支");
            check(Integer.valueOf(11).equals(diagnosis.get("input_required"))
                    && Long.valueOf(11L).equals(diagnosis.get("input_reachable")), "原料不背锅：需要 11、可见 11");
            check("minecraft:furnace".equals(diagnosis.get("device")), "设备不背锅：诊断记录已置熔炉");
        }
    }

    private static void stock(CookingTestWorld world, ItemStack first, ItemStack second) {
        world.game.inventory.clearContent();
        world.game.inventory.setItem(0, first);
        world.game.inventory.setItem(1, second);
    }

    private static RecipeHolder<SmeltingRecipe> smelting(
            String path, net.minecraft.world.item.Item input, net.minecraft.world.item.Item output) {
        return new RecipeHolder<>(id(path), new SmeltingRecipe("",
                CookingBookCategory.MISC, Ingredient.of(input), new ItemStack(output), 0, 200));
    }

    private static SemanticCookTaskRecord request(
            net.minecraft.resources.ResourceLocation itemId, int count,
            SemanticCookTaskRecord.Preference preference,
            List<net.minecraft.resources.ResourceLocation> fuels,
            List<Source> sources) {
        return new SemanticCookTaskRecord("cook-nonfood", 100000, itemId, count,
                preference, fuels, sources, false, List.of());
    }

    private static net.minecraft.resources.ResourceLocation id(String path) {
        return net.minecraft.resources.ResourceLocation.withDefaultNamespace(path);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
