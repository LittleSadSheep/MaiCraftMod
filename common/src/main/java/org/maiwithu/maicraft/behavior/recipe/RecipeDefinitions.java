// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.recipe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.crafting.Recipe;

/**
 * 配方在游戏里的原始定义：用配方自己的序列化格式转成 JSON。加热要求、加工时间、带几率的副产物都在里面，
 * 原样给，不解释。游戏配方表与各配方查看器的读写端共用这一份写法。
 */
public final class RecipeDefinitions {
    private RecipeDefinitions() {}

    /** 一条配方的原始定义；配方的序列化器转不出来（模组没写全）时为 null，不编一个。 */
    public static JsonObject of(Recipe<?> recipe, HolderLookup.Provider registries) {
        try {
            return Recipe.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), recipe).result()
                    .filter(JsonElement::isJsonObject)
                    .map(JsonElement::getAsJsonObject)
                    .orElse(null);
        } catch (RuntimeException unencodable) {
            return null;
        }
    }
}
