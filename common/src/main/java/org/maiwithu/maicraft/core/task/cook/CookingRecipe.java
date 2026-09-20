// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;

/** 一条具体加工路线：哪台炉子、哪种原料、每份能得到几件；规划与实际装料读取同一份选择。 */
record CookingRecipe(ResourceLocation recipeId, AbstractCookingRecipe recipe, CookingDevice device,
                     Item input, int outputCount) {}
