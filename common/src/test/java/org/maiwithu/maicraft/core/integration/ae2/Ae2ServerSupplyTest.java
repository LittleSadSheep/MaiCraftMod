// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ae2;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.Set;
import java.util.List;
import java.util.Map;
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
        serverFallbackRestoresAccess();
        confirmedProvenanceIsBounded();
        inventoryArrivalSettlesSubmittedReceipt();
        settlementDoesNotStartAnotherGroupOrNativeCraft();
        Ae2ServerMenuPresentationTest.main(args);
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void serverFallbackRestoresAccess() throws Exception {
        Ae2ServerMenuPresentationTest.serverFallbackRestoresAccess();
    }

    private static void confirmedProvenanceIsBounded() throws Exception {
        var supply = serverSupply();
        var record = Ae2ServerSupply.class.getDeclaredMethod("recordConfirmed", ClientRequestReceipt.Snapshot.class, int.class);
        record.setAccessible(true);
        for (int index = 0; index < 65; index++) record.invoke(supply, applied(), 7);
        var evidence = supply.evidence();
        var receipts = (List<?>) evidence.get("server_supply_receipts");
        check(receipts.size() == 64 && evidence.get("server_supply_receipt_count").equals(65)
                        && evidence.get("server_supply_transferred").equals(65L)
                        && evidence.get("server_supply_receipts_truncated").equals(true),
                "bounded provenance retains exact confirmed totals without unbounded receipt payloads");
        var first = (Map<?, ?>) receipts.getFirst();
        UUID.fromString((String) first.get("request_id"));
        check(first.get("backend").equals("server") && first.get("amount").equals(1)
                        && first.get("resource_id").equals("opaque-component-key") && first.get("player_slot").equals(7),
                "provenance records the authoritative exact-key transfer and reconciled player destination");
        for (var status : List.of(ClientRequestReceipt.Status.PENDING, ClientRequestReceipt.Status.UNKNOWN,
                ClientRequestReceipt.Status.REJECTED)) {
            var observed = applied();
            var invalid = new ClientRequestReceipt.Snapshot(observed.requestId(), observed.operationId(), 1, true,
                    observed.backend(), status, ClientRequestReceipt.Effect.UNKNOWN, false, "", "", 10, observed.result());
            try { Ae2ServerSupply.confirmedReceipt(invalid, 7); throw new AssertionError("unconfirmed provenance accepted"); }
            catch (IllegalStateException expected) { /* pending or uncertain extraction cannot become evidence */ }
        }
        var outcome = new Ae2ResourceSupply.Outcome(Ae2ResourceSupply.Status.SUCCEEDED, "resources_supplied", "",
                List.of(), Ae2ResourceSupply.Operation.SUPPLY, false, 0, 0, true, false,
                "server_fixed_terminal", List.of(), evidence);
        check(outcome.data().get("server_supply_receipts").equals(receipts),
                "actual supply result carries bounded provenance into acquire child_data");
    }

    private static Ae2ServerSupply serverSupply() {
        var request = new Ae2ResourceSupply.Request(List.of(new Ae2ResourceSupply.Group(
                ResourceLocation.parse("minecraft:iron_ingot"), 1)), false);
        return new Ae2ServerSupply(null, request, null, Set.of(), ignored -> 0);
    }

    private static void inventoryArrivalSettlesSubmittedReceipt() throws Exception {
        var memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        var player = (LocalPlayer) memory.allocateInstance(LocalPlayer.class);
        var inventory = new Inventory(player); field(Player.class, "inventory").set(player, inventory);
        var group = new Ae2ResourceSupply.Group(ResourceLocation.parse("minecraft:iron_ingot"), 1);
        var request = new Ae2ResourceSupply.Request(List.of(group), false);
        var supply = new Ae2ServerSupply(player, request, null, Set.of(), ignored -> inventory.getItem(7).getCount());
        var sample = new ItemStack(Items.IRON_INGOT); sample.set(DataComponents.CUSTOM_NAME, Component.literal("exact"));
        var allocation = new Ae2SupplyPlanner.Allocation(group.itemId(), sample, 1, false);
        var plan = new Ae2SupplyPlanner.Plan(List.of(new Ae2SupplyPlanner.PlannedGroup(group, List.of(allocation))));
        var stock = (Ae2ServerStock) field(Ae2ServerSupply.class, "stock").get(supply);
        var page = new JsonObject(); page.addProperty("schema", "maicraft.ae2_network.v1");
        page.addProperty("membership", "network-one"); var resources = new JsonArray();
        resources.add(resource(sample, "opaque-component-key", 1, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)));
        page.add("resources", resources); stock.append(page, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        field(Ae2ServerSupply.class, "plan").set(supply, plan);
        field(Ae2ServerSupply.class, "allocation").set(supply, allocation);
        field(Ae2ServerSupply.class, "entry").set(supply, stock.select(allocation));
        field(Ae2ServerSupply.class, "slot").set(supply, 7);
        field(Ae2ServerSupply.class, "requested").set(supply, 1);
        field(Ae2ServerSupply.class, "destinationBefore").set(supply, ItemStack.EMPTY);
        var constructor = ClientRequestReceipt.class.getDeclaredConstructor(
                org.maiwithu.maicraft.client.server.ClientOperation.class, JsonObject.class, long.class, long.class,
                Runnable.class, java.util.function.Consumer.class);
        constructor.setAccessible(true);
        var receipt = constructor.newInstance(new org.maiwithu.maicraft.client.server.ClientOperation("inventory.ae2_supply", 1, true, null),
                new JsonObject(), 1L, 1L, (Runnable) () -> {}, (java.util.function.Consumer<Runnable>) Runnable::run);
        field(Ae2ServerSupply.class, "pending").set(supply, receipt);
        var session = (Ae2SupplySession) memory.allocateInstance(Ae2SupplySession.class);
        field(Ae2SupplySession.class, "serverSupply").set(session, supply);
        var task = new Ae2SupplyTask(player, null); field(Ae2SupplyTask.class, "session").set(task, session);
        check(!task.mustSettleBeforeSatisfiedCancellation(), "a queued unsent supply cannot extend acquisition after its goal is satisfied");
        field(ClientRequestReceipt.class, "backend").set(receipt, ClientRequestReceipt.Backend.SERVER);
        field(ClientRequestReceipt.class, "status").set(receipt, ClientRequestReceipt.Status.PENDING);
        field(ClientRequestReceipt.class, "effect").set(receipt, ClientRequestReceipt.Effect.UNKNOWN);
        check(task.mustSettleBeforeSatisfiedCancellation(), "a submitted extraction must settle even before the inventory packet arrives");
        var applied = applied(); field(ClientRequestReceipt.class, "status").set(receipt, applied.status());
        field(ClientRequestReceipt.class, "effect").set(receipt, applied.effect());
        field(ClientRequestReceipt.class, "result").set(receipt, applied.result());
        field(ClientRequestReceipt.class, "serverTick").set(receipt, applied.serverTick());
        inventory.setItem(7, sample.copy());
        check(task.mustSettleBeforeSatisfiedCancellation() && allocation.confirmedCount() == 0,
                "inventory arriving before the next child tick cannot discard the unconsumed authoritative receipt");
        check(!field(Ae2ServerSupply.class, "settlingSatisfied").getBoolean(supply), "barrier inspection is read-only");
        task.requestSatisfiedSettlement();
        var context = (org.maiwithu.maicraft.client.actor.LocalPlayerContext) java.lang.reflect.Proxy.newProxyInstance(
                getClassLoader(), new Class<?>[]{org.maiwithu.maicraft.client.actor.LocalPlayerContext.class},
                (proxy, method, args) -> { if (method.getName().equals("tickRevision")) return 10L;
                    throw new AssertionError("receipt reconciliation must not perform another native action: " + method.getName()); });
        check(supply.tick(context).state() == Ae2ServerSupply.State.RUNNING && allocation.confirmedCount() == 1,
                "the existing receipt reconciles the exact player slot before planning or submitting another request");
        check(supply.tick(context).state() == Ae2ServerSupply.State.SUCCEEDED
                        && supply.evidence().get("server_supply_receipt_count").equals(1)
                        && supply.evidence().get("server_supply_transferred").equals(1L)
                        && inventory.getItem(7).getCount() == 1,
                "one server receipt produces one audited transfer and settles without another extraction");
        check(task.mustSettleBeforeSatisfiedCancellation(), "the parent also waits for the owned session cleanup");
        field(Ae2SupplyTask.class, "terminal").set(task, org.maiwithu.maicraft.task.TaskState.SUCCESS);
        check(!task.mustSettleBeforeSatisfiedCancellation(), "settled task releases its terminal barrier");
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void settlementDoesNotStartAnotherGroupOrNativeCraft() throws Exception {
        var iron = new Ae2ResourceSupply.Group(ResourceLocation.parse("minecraft:iron_ingot"), 1);
        var stick = new Ae2ResourceSupply.Group(ResourceLocation.parse("minecraft:stick"), 1);
        var completed = new Ae2SupplyPlanner.Allocation(iron.itemId(), new ItemStack(Items.IRON_INGOT), 1, false);
        completed.confirm(1);
        var remaining = new Ae2SupplyPlanner.Allocation(stick.itemId(), new ItemStack(Items.STICK), 1, true);
        var supply = new Ae2ServerSupply(null, new Ae2ResourceSupply.Request(List.of(iron, stick), true),
                null, Set.of(), group -> group == iron ? 1 : 0);
        field(Ae2ServerSupply.class, "plan").set(supply, new Ae2SupplyPlanner.Plan(List.of(
                new Ae2SupplyPlanner.PlannedGroup(iron, List.of(completed)),
                new Ae2SupplyPlanner.PlannedGroup(stick, List.of(remaining)))));
        field(Ae2ServerSupply.class, "effectsStarted").setBoolean(supply, true);
        supply.requestSatisfiedSettlement();
        check(supply.tick(null).code().equals("satisfied_before_owned_supply_completed") && remaining.confirmedCount() == 0,
                "a completed first group never authorizes extraction or crafting for another group during settlement");

        var memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        try (var world = new org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness()) {
            field(net.minecraft.world.inventory.AbstractContainerMenu.class, "carried")
                    .set(world.player.inventoryMenu, ItemStack.EMPTY);
            var bridge = (Ae2ReflectionBridge) memory.allocateInstance(Ae2ReflectionBridge.class);
            for (String phaseName : List.of("SUBMIT_CRAFT_AMOUNT", "WAIT_CRAFT_PLAN")) {
                var session = new Ae2SupplySession(world.player,
                        new Ae2ResourceSupply.Request(List.of(iron), true), bridge);
                var phase = field(Ae2SupplySession.class, "phase");
                phase.set(session, Enum.valueOf((Class) phase.getType(), phaseName));
                field(Ae2SupplySession.class, "effectsStarted").setBoolean(session, true);
                world.inventory.setItem(7, new ItemStack(Items.IRON_INGOT));
                var task = new Ae2SupplyTask(world.player, null); field(Ae2SupplyTask.class, "session").set(task, session);
                check(task.mustSettleBeforeSatisfiedCancellation(), "existing native menu transaction requires cleanup");
                task.requestSatisfiedSettlement();
                session.tick(org.maiwithu.maicraft.client.runtime.ClientRuntime.requireContext(world.player));
                check(session.phase().equals("clean_close")
                                && field(Ae2SupplySession.class, "craftingJobsSubmitted").getInt(session) == 0,
                        "an externally satisfied inventory skips native crafting submission and enters physical cleanup");
                world.inventory.setItem(7, ItemStack.EMPTY);
            }
        }
    }
    private static ClassLoader getClassLoader() { return Ae2ServerSupplyTest.class.getClassLoader(); }
    private static ClientRequestReceipt.Snapshot applied() {
        var result = new JsonObject(); result.addProperty("transferred", 1); result.addProperty("requested", 1);
        result.addProperty("resource_id", "opaque-component-key"); result.addProperty("membership", "network-one");
        return new ClientRequestReceipt.Snapshot(UUID.randomUUID(), "inventory.ae2_supply", 1, true,
                ClientRequestReceipt.Backend.SERVER, ClientRequestReceipt.Status.SUCCEEDED,
                ClientRequestReceipt.Effect.APPLIED, false, "", "", 10, result);
    }
    private static java.lang.reflect.Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
