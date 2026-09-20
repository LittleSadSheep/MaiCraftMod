// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.emi;

import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.item.Item;

/** 用小型公开读取接口与私有实现核验EMI元数据契约；不实现EMI配方引擎，不执行任何合成或界面操作。 */
public final class EmiRecipeReaderTest {
    private static final Category CATEGORY = new Category(ResourceLocation.parse("example:machine"), Component.literal("示例工序"));
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        exactMetadata(); displayIdentity(); unknownAndBounds(); NativeRecipeDefinitionTest.main(args);
        System.out.println("EmiRecipeReaderTest: native references, exact stack metadata and unknown media passed");
    }

    private static void exactMetadata() {
        var patch = DataComponentPatch.builder().set(DataComponents.CUSTOM_NAME, Component.literal("保留全部组件"))
                .remove(DataComponents.RARITY).build();
        var input = new ItemView(Items.BOOK, Long.MAX_VALUE - 2, .5f, patch);
        input.remainder = new ItemView(Items.BUCKET, 1, 1, DataComponentPatch.EMPTY);
        var fluid = new FluidView(81_000, 1, DataComponentPatch.EMPTY);
        var ingredient = new IngredientView(List.of(input), 3, .75f);
        var workstation = new IngredientView(List.of(new ItemView(Items.CRAFTING_TABLE, 1, 1, DataComponentPatch.EMPTY)), 1, 1);
        var catalyst = new IngredientView(List.of(new ItemView(Items.BLAZE_ROD, 1, 1, DataComponentPatch.EMPTY)), 1, 1);
        var nativeRecipe = new RecipeHolder<>(ResourceLocation.parse("example:native_recipe"), new ShapelessRecipe("", CraftingBookCategory.MISC,
                new ItemStack(Items.BRICK), NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.STICK))));
        var recipe = new RecipeView(ResourceLocation.parse("example:display_recipe"), List.of(ingredient), List.of(fluid), List.of(catalyst), nativeRecipe, true);
        JsonObject row = reader(List.of(workstation)).read(recipe);
        check(row.get("details_complete").getAsBoolean() && row.get("supports_recipe_tree").getAsBoolean(), "完整原生读取保留树支持标志但不授予执行能力");
        check(row.get("display_recipe_id").getAsString().equals("example:display_recipe")
                && row.getAsJsonObject("backing_recipe").get("id").getAsString().equals("example:native_recipe")
                && row.getAsJsonObject("backing_recipe").get("type").getAsString().equals("minecraft:crafting"), "展示ID与原生背后配方分别保留");
        JsonObject backing = row.getAsJsonObject("backing_recipe");
        check(backing.get("definition_status").getAsString().equals("available")
                && backing.get("provenance").getAsString().equals("installed_native_recipe_serializer")
                && backing.getAsJsonObject("definition").get("type").getAsString().equals("minecraft:crafting_shapeless")
                && backing.getAsJsonObject("definition").getAsJsonObject("result").get("id").getAsString().equals("minecraft:brick")
                && !row.get("recipe_semantics_complete").getAsBoolean(), "背后配方保留原生完整定义，编码成功不冒充执行语义已验证");
        JsonObject in = row.getAsJsonArray("inputs").get(0).getAsJsonObject();
        JsonObject stack = in.getAsJsonArray("alternatives").get(0).getAsJsonObject();
        check(in.get("amount").getAsLong() == 3 && in.get("display_chance").getAsFloat() == .75f && !in.has("chance")
                && stack.get("amount").getAsLong() == Long.MAX_VALUE - 2 && stack.get("display_chance").getAsFloat() == .5f && !stack.has("chance"),
                "原料需求量与候选堆金额/概率分开，long金额不能被ItemStack的int数量截断");
        check(stack.getAsJsonObject("component_changes").has("!minecraft:rarity")
                && stack.getAsJsonObject("components").has("minecraft:custom_name")
                && stack.getAsJsonObject("components").has("minecraft:max_stack_size")
                && !stack.getAsJsonObject("components").has("minecraft:rarity"), "完整保留补丁移除项和默认组件展开结果");
        check(stack.getAsJsonObject("remainder").get("id").getAsString().equals("minecraft:bucket"), "返还物不是额外消费材料");
        JsonObject output = row.getAsJsonArray("outputs").get(0).getAsJsonObject();
        check(output.get("medium").getAsString().equals("fluids") && output.get("amount").getAsLong() == 81_000
                && output.get("unit").getAsString().equals("emi_native_fluid_units"), "保留EMI原生流体金额而不猜测跨加载器换算");
        check(output.get("display_chance").getAsFloat() == 1 && !output.has("chance")
                && row.get("display_chance_scope").getAsString().contains("not native"), "展示默认概率1不能被当成原生保底产量");
        check(row.getAsJsonArray("catalysts").size() == 1 && row.getAsJsonArray("workstations").size() == 1
                && row.getAsJsonObject("category").get("id").getAsString().equals("example:machine"), "催化剂与分类工作站不塞进普通输入");
    }

    private static void displayIdentity() {
        for (String id : new String[]{"example:/synthetic", null}) {
            var display = new RecipeView(id == null ? null : ResourceLocation.parse(id), List.of(), List.of(), List.of(), null, false);
            JsonObject row = reader(List.of()).read(display);
            check(row.get("id_kind").getAsString().equals(id == null ? "null" : "synthetic")
                    && row.getAsJsonObject("backing_recipe").get("status").getAsString().equals("none")
                    && !row.get("supports_recipe_tree").getAsBoolean(), "合成ID、空ID和无原生配方明确区分，特殊展示不得推导递归材料树");
            check(id != null || row.get("display_recipe_id").isJsonNull(), "不能给不可序列化展示发明原生配方ID");
            check(row.get("execution_support").getAsString().equals("not_inferred_from_emi"), "EMI展示成功不等于普通craft支持");
        }
    }

    private static void unknownAndBounds() {
        // 第三方资源即便使用Item作key也保持unknown；任意getItemStack转换入口禁止被知识读取调用。
        var unknown = new UnknownView(); JsonObject row = reader(List.of()).read(output(unknown));
        check(row.getAsJsonArray("outputs").get(0).getAsJsonObject().get("medium").getAsString().equals("unknown")
                && !row.get("details_complete").getAsBoolean(), "未知扩展媒体不能冒充物品或流体");
        var unit = new ItemView(Items.BOOK, 1, 1, DataComponentPatch.EMPTY);
        var variants = new IngredientView(Collections.nCopies(40, unit), 1, 1);
        row = reader(List.of()).read(new RecipeView(null, List.of(variants), List.of(), List.of(), null, false));
        JsonObject in = row.getAsJsonArray("inputs").get(0).getAsJsonObject();
        check(in.get("alternatives_count").getAsInt() == 40 && in.getAsJsonArray("alternatives").size() == 32
                && in.get("alternatives_truncated").getAsBoolean() && !row.get("details_complete").getAsBoolean(), "展示候选有界且如实标记未展开部分");
        unit.remainder = unit; row = reader(List.of()).read(output(unit));
        check(row.getAsJsonArray("outputs").get(0).getAsJsonObject().get("remainder_truncated").getAsBoolean(), "循环返还物不能无限展开");
        var huge = new ItemView(Items.BOOK, 1, 1, DataComponentPatch.builder().set(DataComponents.CUSTOM_NAME, Component.literal("大".repeat(20_000))).build());
        row = reader(List.of()).read(output(huge)); JsonObject encoded = row.getAsJsonArray("outputs").get(0).getAsJsonObject();
        check(!encoded.get("components_complete").getAsBoolean() && !encoded.has("component_changes") && !encoded.has("components"),
                "过大的组件明确不可读，不能交出被截断的物品身份");
    }

    private static RecipeView output(StackApi stack) { return new RecipeView(null, List.of(), List.of(stack), List.of(), null, false); }
    private static EmiRecipeReader reader(List<?> workstations) {
        var api = new EmiPublicApi(Object.class, ManagerApi.class, RecipeApi.class, CategoryApi.class, IngredientApi.class,
                StackApi.class, ItemView.class, FluidView.class, EmptyView.class, Object.class);
        return new EmiRecipeReader(api, new Manager(workstations), RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }
    public interface IngredientApi { List<?> getEmiStacks(); long getAmount(); float getChance(); }
    public interface StackApi extends IngredientApi {
        ResourceLocation getId(); Object getKey(); DataComponentPatch getComponentChanges(); boolean isEmpty(); StackApi getRemainder();
        default List<?> getEmiStacks() { return List.of(this); }
    }
    public interface CategoryApi { ResourceLocation getId(); Component getName(); }
    public interface ManagerApi { List<?> getWorkstations(CategoryApi category); }
    public interface RecipeApi {
        ResourceLocation getId(); CategoryApi getCategory(); List<?> getInputs(); List<?> getOutputs(); List<?> getCatalysts();
        Object getBackingRecipe(); boolean supportsRecipeTree();
    }
    private record IngredientView(List<?> values, long amount, float chance) implements IngredientApi {
        public List<?> getEmiStacks() { return values; } public long getAmount() { return amount; } public float getChance() { return chance; }
    }
    private record Category(ResourceLocation id, Component name) implements CategoryApi {
        public ResourceLocation getId() { return id; } public Component getName() { return name; }
    }
    private record Manager(List<?> workstations) implements ManagerApi { public List<?> getWorkstations(CategoryApi category) { return workstations; } }
    private record RecipeView(ResourceLocation id, List<?> inputs, List<?> outputs, List<?> catalysts, Object backing, boolean tree) implements RecipeApi {
        public ResourceLocation getId() { return id; } public CategoryApi getCategory() { return CATEGORY; }
        public List<?> getInputs() { return inputs; } public List<?> getOutputs() { return outputs; } public List<?> getCatalysts() { return catalysts; }
        public Object getBackingRecipe() { return backing; } public boolean supportsRecipeTree() { return tree; }
        public void addWidgets(Object ignored) { throw new AssertionError("知识读取不得创建展示控件"); }
        public void craftRecipe() { throw new AssertionError("知识读取不得提交合成"); }
    }
    private abstract static class StackView implements StackApi {
        final long amount; final float chance; final DataComponentPatch patch; StackApi remainder;
        StackView(long amount, float chance, DataComponentPatch patch) { this.amount = amount; this.chance = chance; this.patch = patch; }
        public long getAmount() { return amount; } public float getChance() { return chance; }
        public DataComponentPatch getComponentChanges() { return patch; } public boolean isEmpty() { return false; }
        public StackApi getRemainder() { return remainder; }
        public ItemStack getItemStack() { throw new AssertionError("不能靠数量截断的ItemStack转换猜测媒体身份"); }
    }
    private static class ItemView extends StackView {
        final Item item;
        ItemView(Item item, long amount, float chance, DataComponentPatch patch) { super(amount, chance, patch); this.item = item; }
        public ResourceLocation getId() { return BuiltInRegistries.ITEM.getKey(item); } public Object getKey() { return item; }
    }
    private static final class FluidView extends StackView {
        FluidView(long amount, float chance, DataComponentPatch patch) { super(amount, chance, patch); }
        public ResourceLocation getId() { return ResourceLocation.parse("minecraft:water"); } public Object getKey() { return Fluids.WATER; }
    }
    private static final class EmptyView extends ItemView {
        EmptyView() { super(Items.AIR, 0, 1, DataComponentPatch.EMPTY); } @Override public boolean isEmpty() { return true; }
    }
    private static final class UnknownView extends ItemView { UnknownView() { super(Items.BOOK, 1, 1, DataComponentPatch.EMPTY); } }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
