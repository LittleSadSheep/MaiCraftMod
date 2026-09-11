// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.mekanism;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** Exact, count-independent identities for native Mekanism recipe and event resources. */
public final class MekanismResourceStacks {
    public static final String FLUID = "net.neoforged.neoforge.fluids.FluidStack";
    public static final String CHEMICAL = "mekanism.api.chemical.ChemicalStack";
    private MekanismResourceStacks() {}

    public static String medium(Object stack) {
        if (stack instanceof ItemStack) return "items";
        if (NativeApi.is(stack, FLUID)) return "fluids";
        if (NativeApi.is(stack, CHEMICAL)) return "chemicals";
        throw new IllegalArgumentException("Unsupported native resource stack");
    }

    public static long amount(Object stack) {
        if (stack instanceof ItemStack item) return item.getCount();
        return NativeApi.number(NativeApi.call(stack, medium(stack).equals("fluids") ? FLUID : CHEMICAL, "getAmount"));
    }

    public static Object copy(Object stack) {
        if (stack instanceof ItemStack item) return item.copy();
        return NativeApi.call(stack, medium(stack).equals("fluids") ? FLUID : CHEMICAL, "copy");
    }

    @SuppressWarnings("unchecked")
    public static JsonObject identity(Object stack, HolderLookup.Provider registries) {
        if (stack instanceof ItemStack item) return ResourceIdentity.item(item, registries);
        String medium = medium(stack);
        if (medium.equals("chemicals")) {
            return ResourceIdentity.base(medium, NativeApi.call(stack, CHEMICAL, "getTypeRegistryName").toString());
        }
        DataComponentPatch components = (DataComponentPatch) NativeApi.call(stack, FLUID, "getComponentsPatch");
        if (components.entrySet().stream().anyMatch(entry -> entry.getKey().isTransient())) {
            throw new IllegalArgumentException("Transient fluid components cannot be represented completely");
        }
        Fluid fluid = (Fluid) NativeApi.call(stack, FLUID, "getFluid");
        JsonObject identity = ResourceIdentity.base(medium, BuiltInRegistries.FLUID.getKey(fluid).toString());
        if (amount(stack) > 0) {
            Object unit = NativeApi.call(stack, FLUID, "copyWithAmount", 1);
            Codec<Object> codec = (Codec<Object>) NativeApi.constant(FLUID, "CODEC");
            JsonObject encoded = codec.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), unit).getOrThrow().getAsJsonObject();
            identity.add("components", encoded.has("components") ? encoded.get("components") : new JsonObject());
        }
        return identity;
    }

    public static boolean sameIdentity(Object a, Object b, HolderLookup.Provider registries) {
        if (a == null || b == null || !medium(a).equals(medium(b))) return false;
        if (a instanceof ItemStack left && b instanceof ItemStack right) return ItemStack.isSameItemSameComponents(left, right);
        if (NativeApi.is(a, FLUID)) return NativeApi.truth(NativeApi.call(null, FLUID, "isSameFluidSameComponents", a, b));
        return ResourceIdentity.key(identity(a, registries)).equals(ResourceIdentity.key(identity(b, registries)));
    }

    public static JsonObject resource(Object stack, HolderLookup.Provider registries) {
        JsonObject identity = identity(stack, registries), result = new JsonObject();
        result.addProperty("medium", identity.get("kind").getAsString());
        result.addProperty("id", ResourceIdentity.key(identity)); result.add("identity", identity);
        return result;
    }
}
