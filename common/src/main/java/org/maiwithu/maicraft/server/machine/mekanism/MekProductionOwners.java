// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.mekanism;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** Native monitor ownership and recipe-holder identity, including Mekanism's generated smelting recipes. */
final class MekProductionOwners {
    private static final int LIMIT = 4096, RECIPE_LIMIT = 8192;
    private static final String LOOKUP = "mekanism.common.recipe.lookup.IRecipeLookupHandler";
    private static final String PROVIDER = "mekanism.common.recipe.IMekanismRecipeTypeProvider";
    private static final WeakKeys<Owner> OWNERS = new WeakKeys<>();
    private static final WeakKeys<RecipeId> IDS = new WeakKeys<>();
    record Owner(WeakReference<BlockEntity> block, int index) {}
    private record RecipeId(WeakReference<ServerLevel> level, WeakReference<Object> provider, String id) {}

    private MekProductionOwners() {}

    static synchronized void register(Object monitor, Object handler, int index) {
        if (monitor == null) return;
        OWNERS.remove(monitor);
        if (handler instanceof BlockEntity block && index >= 0 && index < 256 && OWNERS.size() < LIMIT) {
            OWNERS.put(monitor, new Owner(new WeakReference<>(block), index));
        }
    }

    static synchronized Owner owner(Object monitor) { return OWNERS.get(monitor); }

    static synchronized String recipeId(BlockEntity owner, ServerLevel level, Object recipe) {
        Object provider = NativeApi.call(owner, LOOKUP, "getRecipeType");
        RecipeId cached = IDS.get(recipe);
        if (cached != null && cached.level.get() == level && cached.provider.get() == provider) return cached.id;
        Object raw = NativeApi.call(provider, PROVIDER, "getRecipes", level);
        if (!(raw instanceof List<?> recipes) || recipes.size() > RECIPE_LIMIT) return null;
        String found = null;
        for (Object value : recipes) {
            if (!(value instanceof RecipeHolder<?> holder) || holder.value() != recipe) continue;
            String id = holder.id().toString();
            if (found != null && !found.equals(id)) return null;
            found = id;
        }
        if (found != null && IDS.size() < LIMIT) {
            IDS.put(recipe, new RecipeId(new WeakReference<>(level), new WeakReference<>(provider), found));
        }
        return found;
    }

    private static final class Key extends WeakReference<Object> {
        private final int hash;
        private Key(Object value, ReferenceQueue<Object> queue) { super(value, queue); hash = System.identityHashCode(value); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            Object value = get();
            return this == other || value != null && other instanceof Key key && value == key.get();
        }
    }
    private static final class WeakKeys<T> {
        private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
        private final Map<Key, T> values = new HashMap<>();
        private void clean() { for (var key = queue.poll(); key != null; key = queue.poll()) values.remove(key); }
        T get(Object key) { clean(); return key == null ? null : values.get(new Key(key, null)); }
        void remove(Object key) { clean(); if (key != null) values.remove(new Key(key, null)); }
        void put(Object key, T value) { clean(); values.put(new Key(key, queue), value); }
        int size() { clean(); return values.size(); }
    }
}
