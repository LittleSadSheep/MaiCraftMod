// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ae2;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.Set;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.server.ClientRequestReceipt;
import sun.misc.Unsafe;

/** Component-exact authoritative stock enters the established planner without inventing resource keys. */
public final class Ae2ServerSupplyTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        exactStockAndDestination();
        fallbackAndCraftIdentity();
        System.out.println("Ae2ServerSupplyTest: passed");
    }

    private static void exactStockAndDestination() throws Exception {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        ItemStack a = new ItemStack(Items.STICK);
        ItemStack b = new ItemStack(Items.STICK);
        a.set(DataComponents.CUSTOM_NAME, Component.literal("A"));
        b.set(DataComponents.CUSTOM_NAME, Component.literal("B"));
        JsonObject page = new JsonObject();
        page.addProperty("schema", "maicraft.ae2_network.v1");
        page.addProperty("membership", "network-one");
        JsonArray resources = new JsonArray();
        resources.add(resource(a, "opaque-A", 8, registries));
        resources.add(resource(b, "opaque-B", 200, registries));
        page.add("resources", resources);
        var stock = new Ae2ServerStock();
        stock.append(page, registries);
        stock.append(page, registries);
        check(stock.entries().size() == 2, "repeated pages do not double-count exact-key inventory");
        var allocation = new Ae2SupplyPlanner.Allocation(ResourceLocation.parse("minecraft:stick"), a, 8, false);
        var selected = stock.select(allocation);
        check(stock.identity(selected.serial()).equals("opaque-A"), "same item ID cannot substitute different components or manufacture a key");
        stock.debit(selected.serial(), 8);
        check(stock.select(allocation) == null, "depleted exact stock cannot silently switch to another component variant");
        var changed = page.deepCopy();
        changed.addProperty("membership", "network-two");
        try { stock.append(changed, registries); throw new AssertionError("network change accepted"); }
        catch (IllegalStateException expected) { /* preserves original network binding */ }

        var memoryField = Unsafe.class.getDeclaredField("theUnsafe"); memoryField.setAccessible(true);
        Unsafe memory = (Unsafe) memoryField.get(null);
        var player = (LocalPlayer) memory.allocateInstance(LocalPlayer.class);
        var inventory = new Inventory(player);
        var inventoryField = Player.class.getDeclaredField("inventory"); inventoryField.setAccessible(true);
        inventoryField.set(player, inventory);
        inventory.setItem(5, a.copyWithCount(5));
        inventory.setItem(6, b.copyWithCount(5));
        check(Ae2ServerSupply.destination(player, a, Set.of()) == 5, "server supply merges into a component-compatible destination");
        check(Ae2ServerSupply.destination(player, a, Set.of(5)) == 0, "reserved and differently named stacks are not reused");
    }

    private static JsonObject resource(ItemStack stack, String key, int amount, RegistryAccess registries) {
        JsonObject encoded = ItemStack.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), stack).getOrThrow().getAsJsonObject();
        JsonObject identity = new JsonObject();
        identity.addProperty("kind", "items");
        identity.add("id", encoded.get("id"));
        identity.add("components", encoded.get("components"));
        JsonObject resource = new JsonObject();
        resource.add("identity", identity);
        resource.addProperty("resource_id", key);
        resource.addProperty("amount", amount);
        return resource;
    }

    private static void fallbackAndCraftIdentity() {
        check(Ae2ServerSupply.safeFallback(receipt("unsupported_operation", ClientRequestReceipt.Effect.NOT_APPLIED)),
                "unsupported untouched server supply can use the existing native route");
        check(!Ae2ServerSupply.safeFallback(receipt("permission_denied", ClientRequestReceipt.Effect.NOT_APPLIED)),
                "AE2 permissions cannot be bypassed through a visible native menu");
        check(!Ae2ServerSupply.safeFallback(receipt("unsupported_operation", ClientRequestReceipt.Effect.UNKNOWN)),
                "unknown effects cannot be replayed even when the code looks unsupported");
        JsonObject job = new JsonObject();
        job.addProperty("schema", "maicraft.ae2_craft_job.v1");
        job.addProperty("job_id", "owned-job");
        job.addProperty("resource_id", "opaque-key");
        job.addProperty("membership", "network-one");
        job.addProperty("amount", 8);
        job.addProperty("status", "ready");
        check(Ae2ServerCraftJob.validResult(job, "owned-job", "opaque-key", "network-one", 8), "owned exact crafting plan matches");
        check(!Ae2ServerCraftJob.validResult(job, "other-job", "opaque-key", "network-one", 8)
                && !Ae2ServerCraftJob.validResult(job, "owned-job", "opaque-key", "network-two", 8)
                && !Ae2ServerCraftJob.validResult(job, "owned-job", "opaque-key", "network-one", 9),
                "crafting results bind the original job, network, component key and approved amount");
    }

    private static ClientRequestReceipt.Snapshot receipt(String code, ClientRequestReceipt.Effect effect) {
        return new ClientRequestReceipt.Snapshot(UUID.randomUUID(), "inventory.ae2_supply", 1, true,
                ClientRequestReceipt.Backend.SERVER, ClientRequestReceipt.Status.REJECTED, effect, false,
                code, "", 1, new JsonObject());
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
