// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.ae2;

import com.google.gson.JsonObject;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerAccess;

/** Helpers for the physical target already authorized by ServerMachineOperations. */
final class Ae2ConfigurationAccess {
    static final String PART_HOST = "appeng.api.parts.IPartHost";
    static final String INTERNAL = "appeng.api.inventories.InternalInventory";
    static final String GENERIC = "appeng.api.stacks.GenericStack";
    static final String KEY = "appeng.api.stacks.AEKey";
    private Ae2ConfigurationAccess() {}

    static Object host(BlockEntity entity, JsonObject body) {
        if (NativeApi.is(entity, PART_HOST)) {
            Object part = NativeApi.call(entity, PART_HOST, "getPart", ServerAccess.side(body));
            if (part == null) throw ServerAccess.denied("target_missing", "No AE2 part exists on the requested side");
            return part;
        }
        return entity;
    }

    static ItemStack item(String definition) {
        return (ItemStack) NativeApi.call(NativeApi.constant("appeng.core.definitions.AEItems", definition),
                "appeng.core.definitions.ItemDefinition", "stack");
    }

    static Object generic(Object key, long amount) {
        try { return NativeApi.type(GENERIC).getConstructor(NativeApi.type(KEY), long.class).newInstance(key, amount); }
        catch (ReflectiveOperationException unavailable) {
            throw ServerAccess.denied("unsupported_resource", "AE2 could not represent this recipe resource");
        }
    }

    static void savePart(Object part) {
        Object host = NativeApi.call(part, "appeng.parts.AEBasePart", "getHost");
        NativeApi.call(host, PART_HOST, "markForSave");
        NativeApi.call(host, PART_HOST, "markForUpdate");
    }
}
