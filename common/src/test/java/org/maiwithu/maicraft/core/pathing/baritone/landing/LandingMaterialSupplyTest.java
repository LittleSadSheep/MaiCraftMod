// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone.landing;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.integration.ae2.Ae2ResourceSupply;

/** Reflex policy executes the real coordinator across native supply receipt boundaries. */
public final class LandingMaterialSupplyTest {
    private static final ResourceLocation WATER = ResourceLocation.parse("minecraft:water_bucket");
    private static final List<ResourceLocation> ACCEPTED = List.of(WATER, ResourceLocation.parse("minecraft:cobweb"));

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var world = new InteractionWorldTestHarness()) {
            field(Entity.class, "onGround").setBoolean(world.player, false);
            field(AbstractContainerMenu.class, "carried").set(world.player.inventoryMenu, ItemStack.EMPTY);
            world.inventory.setItem(17, new ItemStack(Items.WATER_BUCKET));
            var carried = new LandingMaterialSupply(ACCEPTED, (p, r) -> {
                throw new AssertionError("existing inventory must outrank AE even while airborne");
            });
            check(carried.tick(context(world), 0).state() == LandingMaterialSupply.State.AVAILABLE,
                    "storage inventory is usable without an LLM pre-equip request");
            world.inventory.setItem(17, ItemStack.EMPTY);
            var receipt = new Supply();
            int[] begins = {0};
            var supply = new LandingMaterialSupply(ACCEPTED, (p, request) -> {
                begins[0]++;
                check(!request.allowCrafting() && request.totalCount() == 1
                                && request.groups().getFirst().acceptableItemIds().containsAll(ACCEPTED),
                        "one existing-stock request accepts only the caller's suitable materials");
                return receipt;
            });
            check(supply.tick(context(world), 30).state() == LandingMaterialSupply.State.ACQUIRING,
                    "airborne supply is attempted");
            supply.tick(context(world), 30);
            check(receipt.ticks == 1 && begins[0] == 1, "repeated same-tick observations do not repeat protocol work");
            world.nextTick();
            check(supply.tick(context(world), 6).state() == LandingMaterialSupply.State.CLEANING,
                    "deadline yields to cleanup instead of an unbounded network wait");
            world.inventory.setItem(18, new ItemStack(Items.WATER_BUCKET));
            world.nextTick();
            check(supply.tick(context(world), 5).state() == LandingMaterialSupply.State.CLEANING,
                    "a synchronized item does not bypass a pending native menu close");
            receipt.completed = true;
            world.nextTick();
            check(supply.tick(context(world), 4).state() == LandingMaterialSupply.State.AVAILABLE,
                    "observed inventory is handed off only after cleanup settles");
            check(begins[0] == 1 && receipt.ticks == 1, "deadline never retries a submitted transaction");
            world.inventory.setItem(18, ItemStack.EMPTY);
            var preempted = new Supply();
            var preemption = new LandingMaterialSupply(ACCEPTED, (p, r) -> preempted);
            world.nextTick(); preemption.tick(context(world), 50);
            preemption.finish(context(world), "fall plan was replaced");
            check(preemption.cleanupPending() && preempted.ticks == 1,
                    "same-tick preemption retains cleanup ownership without a second submission");
            var falseSuccess = new Supply(); falseSuccess.completed = true;
            var missing = new LandingMaterialSupply(ACCEPTED, (p, r) -> falseSuccess);
            world.nextTick();
            check(missing.tick(context(world), 30).state() == LandingMaterialSupply.State.UNAVAILABLE,
                    "a supplied outcome without actual inventory is not material availability");
            var invalidated = new Supply();
            var ownership = new LandingMaterialSupply(ACCEPTED, (p, r) -> invalidated);
            world.nextTick(); ownership.tick(context(world), 30);
            LocalPlayerContext current = context(world);
            LocalPlayerContext changed = (LocalPlayerContext) Proxy.newProxyInstance(
                    LocalPlayerContext.class.getClassLoader(), new Class<?>[]{LocalPlayerContext.class},
                    (proxy, method, values) -> method.getName().equals("controlRevision")
                            ? current.controlRevision() + 1 : method.invoke(current, values));
            check(ownership.tick(changed, 29).state() == LandingMaterialSupply.State.UNAVAILABLE
                            && invalidated.finishes == 0,
                    "a changed body/control owner is never operated on as the prior transaction");
            int[] unavailableCalls = {0};
            var absent = new LandingMaterialSupply(ACCEPTED, (p, r) -> {
                unavailableCalls[0]++; throw new IllegalStateException("optional AE integration absent");
            });
            world.nextTick(); absent.tick(context(world), 100);
            world.nextTick(); absent.tick(context(world), 99);
            check(unavailableCalls[0] == 1, "an unavailable integration is bounded and is not mechanically retried");
        }
        System.out.println("LandingMaterialSupplyTest: passed");
    }

    private static LocalPlayerContext context(InteractionWorldTestHarness world) {
        return ClientRuntime.requireContext(world.player);
    }
    private static final class Supply implements Ae2ResourceSupply.Session {
        int ticks, finishes;
        boolean completed;
        public Optional<Ae2ResourceSupply.Outcome> tick(LocalPlayerContext context) { ticks++; return outcome(); }
        public Optional<Ae2ResourceSupply.Outcome> outcome() {
            return completed ? Optional.of(new Ae2ResourceSupply.Outcome(Ae2ResourceSupply.Status.SUCCEEDED,
                    "test_receipt", "", List.of(), Ae2ResourceSupply.Operation.SUPPLY,
                    false, 0, 0, true, false, "wireless", List.of())) : Optional.empty();
        }
        public Optional<Ae2ResourceSupply.Outcome> finishInPlace(LocalPlayerContext context, String reason) {
            finishes++; return outcome();
        }
        public String phase() { return "native_receipt_pending"; }
        public boolean livenessActive() { return !completed; }
        public void pause(LocalPlayerContext context) { }
        public Ae2ResourceSupply.Outcome cancel(LocalPlayerContext context, String reason) {
            throw new AssertionError("reflex deadline uses asynchronous cleanup, not terminal cancellation");
        }
    }
    private static Field field(Class<?> type, String name) throws Exception {
        var value = type.getDeclaredField(name); value.setAccessible(true); return value;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
