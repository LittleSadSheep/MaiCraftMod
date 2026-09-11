// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.inventory;

import com.google.gson.JsonObject;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerAccess;

/** Resolves keys from actual network facts; no caller-supplied stack or component is instantiated. */
public final class Ae2Keys {
    public static final String CRAFTING = "appeng.api.networking.crafting.ICraftingService";
    private Ae2Keys() {}

    public static Object crafting(Ae2Access access) { return NativeApi.call(access.grid(), Ae2Access.GRID, "getCraftingService"); }

    public static Iterable<?> craftables(Ae2Access access) {
        Object filter = NativeApi.call(null, Ae2Access.ITEM, "filter");
        return (Iterable<?>) NativeApi.call(crafting(access), CRAFTING, "getCraftables", filter);
    }

    public static JsonObject identity(ServerPlayer player, Object key) {
        if (!NativeApi.is(key, Ae2Access.ITEM)) throw ServerAccess.denied("unsupported_resource", "This transaction requires an AE2 item key");
        return ResourceIdentity.item((ItemStack) NativeApi.call(key, Ae2Access.ITEM, "toStack", 1), player.registryAccess());
    }

    public static Object find(Ae2Access access, ServerPlayer player, String resourceId, boolean allowCraftable) {
        int inspected = 0;
        for (Object raw : (Iterable<?>) access.cachedInventory()) {
            if (++inspected > 4096) break;
            Object key = ((Map.Entry<?, ?>) raw).getKey();
            if (NativeApi.is(key, Ae2Access.ITEM) && resourceId.equals(ResourceIdentity.key(identity(player, key)))) return key;
        }
        if (allowCraftable) {
            inspected = 0;
            for (Object key : craftables(access)) {
                if (++inspected > 4096) break;
                if (resourceId.equals(ResourceIdentity.key(identity(player, key)))) return key;
            }
        }
        throw ServerAccess.denied("resource_unavailable", "Exact key is absent from the bounded network inventory and craftables");
    }
}
