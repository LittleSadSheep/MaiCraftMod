// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluids;

/** 以普通原料构造不同世界配方，验证 DTO 不依赖固定材料、重复原料不丢失及组件快照独立。 */
public final class NativeTransformRecipesTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        Ingredient alternatives = Ingredient.of(Items.IRON_INGOT, Items.COPPER_INGOT);
        var inputs = new ArrayList<>(List.of(alternatives, alternatives, Ingredient.of(Items.CLAY_BALL)));
        ItemStack result = new ItemStack(Items.BRICK, 3); result.set(DataComponents.CUSTOM_NAME, Component.literal("批次成品"));
        JsonObject water = new JsonObject(); water.addProperty("type", "fluid"); water.addProperty("tag", "minecraft:water");
        var first = new NativeTransformRecipes.Recipe(ResourceLocation.parse("test:three_inputs"), inputs, result, water,
                state -> state.getType() == Fluids.WATER || state.getType() == Fluids.FLOWING_WATER, registries);
        inputs.clear(); result.setCount(19); water.addProperty("type", "explosion");
        check(first.inputs().size() == 3 && first.inputs().get(0) == first.inputs().get(1)
                        && first.inputs().get(0).test(new ItemStack(Items.COPPER_INGOT)) && first.triggerInputIndex() == 0,
                "原生谓词与重复项应保留，触发项由适配器声明");
        check(first.isFluid() && first.supports(Fluids.WATER.defaultFluidState()) && first.supports(Fluids.FLOWING_WATER.defaultFluidState())
                        && !first.supports(Fluids.LAVA.defaultFluidState()) && !first.supports(Fluids.EMPTY.defaultFluidState()),
                "流体条件交给领域谓词，空流体和不匹配流体不能通过");
        check(first.requireFluid() == first && first.result().getCount() == 3, "DTO 不跟随调用方修改原物品或环境");
        first.result().remove(DataComponents.CUSTOM_NAME);
        check(first.result().has(DataComponents.CUSTOM_NAME), "结果 getter 必须返回完整组件的防御副本");
        JsonObject described = first.describe(); var requirements = described.getAsJsonArray("inputs");
        check(requirements.size() == 3 && requirements.get(0).getAsJsonObject().get("amount").getAsInt() == 1
                        && requirements.get(0).getAsJsonObject().getAsJsonArray("alternatives").size() == 2,
                "公开 DTO 不能把重复原料合成一份，也不能丢失原生候选");
        check(described.getAsJsonObject("result").get("count").getAsInt() == 3
                        && described.getAsJsonObject("result").getAsJsonObject("components").has("minecraft:custom_name"),
                "产物数量与自定义组件必须由原生 ItemStack 编码保留");
        described.getAsJsonObject("result").addProperty("count", 77);
        check(first.describe().getAsJsonObject("result").get("count").getAsInt() == 3, "修改描述不能改变后续配方读回");
        try { first.inputs().clear(); throw new AssertionError("原料列表必须不可变"); } catch (UnsupportedOperationException expected) { }
        // 第二族使用完全不同的原料与结果数量，执行器不能暗含某一水晶配方的三输入或双倍产量。
        JsonObject fluid = new JsonObject(); fluid.addProperty("type", "fluid"); fluid.addProperty("tag", "test:coolant");
        var second = new NativeTransformRecipes.Recipe(ResourceLocation.parse("test:one_input"), List.of(Ingredient.of(Items.STICK)),
                new ItemStack(Items.PAPER), fluid, state -> state.getType() == Fluids.WATER, registries);
        check(second.inputs().size() == 1 && second.result().is(Items.PAPER) && second.result().getCount() == 1,
                "不同配方族只依赖自己的原料和产物定义");
        JsonObject explosion = new JsonObject(); explosion.addProperty("type", "explosion");
        var refused = new NativeTransformRecipes.Recipe(ResourceLocation.parse("test:explosive"), List.of(Ingredient.of(Items.GUNPOWDER)),
                new ItemStack(Items.STONE), explosion, state -> { throw new AssertionError("爆炸不能走流体匹配"); }, registries);
        check(!refused.isFluid() && !refused.supports(Fluids.WATER.defaultFluidState()), "水中执行不能把爆炸环境忽略");
        try { refused.requireFluid(); throw new AssertionError("爆炸配方必须在准入时拒绝"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("requires_fluid"), "拒绝必须明确指出环境不支持"); }
        System.out.println("NativeTransformRecipesTest: passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
