// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone.landing;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.client.actor.DefaultBodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.integration.ae2.Ae2ResourceSupply;

/** One bounded reflex acquisition. The caller supplies items already suitable for the landing. */
public final class LandingMaterialSupply {
    public enum State { AVAILABLE, ACQUIRING, CLEANING, UNAVAILABLE }
    public record Result(State state, ResourceLocation itemId, String detail) {
        public boolean finished() { return state == State.AVAILABLE || state == State.UNAVAILABLE; }
    }
    private static final int ACTION_RESERVE_TICKS = 6;
    private static final int MAX_SUPPLY_TICKS = 240;
    private final List<ResourceLocation> accepted;
    private final BiFunction<LocalPlayer, Ae2ResourceSupply.Request, Ae2ResourceSupply.Session> begin;
    private Ae2ResourceSupply.Session session;
    private Result result = new Result(State.ACQUIRING, null, "checking carried landing material");
    private long started = Long.MIN_VALUE, lastTick = Long.MIN_VALUE, bodyEpoch, controlRevision;
    private Object world;
    private UUID playerId;
    private boolean stopping;
    private String stopReason = "landing action window reached";

    public LandingMaterialSupply(List<ResourceLocation> acceptableIds) {
        this(acceptableIds, Ae2ResourceSupply::beginInPlace);
    }

    LandingMaterialSupply(List<ResourceLocation> acceptableIds,
            BiFunction<LocalPlayer, Ae2ResourceSupply.Request, Ae2ResourceSupply.Session> begin) {
        accepted = List.copyOf(Objects.requireNonNull(acceptableIds)).stream().distinct().toList();
        if (accepted.size() > 256) throw new IllegalArgumentException("too many landing material candidates");
        this.begin = Objects.requireNonNull(begin);
    }

    /** remainingTicks ends at the required landing action, not at eventual ground contact. */
    public Result tick(LocalPlayerContext context, int remainingTicks) {
        if (result.state() == State.UNAVAILABLE) return result;
        if (!sameActor(context)) return unavailable("body, world or control changed; prior supply cleanup is unconfirmed");
        if (lastTick == context.tickRevision()) return result;
        lastTick = context.tickRevision();
        ResourceLocation carried = carried(context);
        if (session == null && carried != null) return available(carried, "landing material already carried; AE was not used");
        if (session == null && (accepted.isEmpty() || remainingTicks <= ACTION_RESERVE_TICKS))
            return unavailable("no carried landing material before the native action window");
        try {
            if (session == null) {
                var group = new Ae2ResourceSupply.Group(accepted.getFirst(), accepted, 1,
                        Ae2ResourceSupply.SelectionMode.SINGLE_VARIANT);
                session = begin.apply(context.player(), new Ae2ResourceSupply.Request(List.of(group), false));
            }
            stopping |= carried != null || remainingTicks <= ACTION_RESERVE_TICKS
                    || context.tickRevision() - started >= MAX_SUPPLY_TICKS;
            Optional<Ae2ResourceSupply.Outcome> outcome = stopping
                    ? session.finishInPlace(context, stopReason) : session.tick(context);
            // Even a terminal transaction may have submitted an asynchronous native menu close.
            if (outcome.isPresent()) outcome = session.finishInPlace(context, stopReason);
            if (outcome.isEmpty()) {
                result = new Result(stopping ? State.CLEANING : State.ACQUIRING, null,
                        "AE landing supply: " + session.phase());
                return result;
            }
            var receipt = outcome.orElseThrow();
            carried = carried(context);
            if (carried != null && worldReady(context)) return available(carried,
                    "landing material observed in inventory; AE receipt=" + receipt.code()
                            + (receipt.uncertain() ? "; transaction uncertain, never retried" : ""));
            return unavailable("landing material not ready: " + receipt.code()
                    + (worldReady(context) ? "" : "; native menu cleanup unconfirmed"));
        } catch (RuntimeException unavailable) {
            stopping = true;
            stopReason = "landing supply failed: " + unavailable.getMessage();
            if (session != null) {
                try {
                    if (session.finishInPlace(context, stopReason).isEmpty()) {
                        result = new Result(State.CLEANING, null, stopReason);
                        return result;
                    }
                } catch (RuntimeException changedOwner) {
                    stopReason += "; native cleanup unavailable: " + changedOwner.getMessage();
                }
            }
            return unavailable(stopReason);
        }
    }

    /** Preemption does not restart extraction and never relinquishes a pending owned menu close. */
    public Result finish(LocalPlayerContext context, String reason) {
        stopping = true;
        stopReason = reason == null ? "landing supply interrupted" : reason;
        if (session != null && !result.finished())
            result = new Result(State.CLEANING, null, stopReason);
        return tick(context, 0);
    }

    public boolean cleanupPending() { return result.state() == State.CLEANING; }
    public Result result() { return result; }

    private boolean sameActor(LocalPlayerContext context) {
        if (!context.isCurrent() || !context.permitsNativeActions()) return false;
        if (started == Long.MIN_VALUE) {
            started = context.tickRevision();
            bodyEpoch = context.bodyEpoch();
            controlRevision = context.controlRevision();
            world = context.level();
            playerId = context.player().getUUID();
        }
        return bodyEpoch == context.bodyEpoch() && controlRevision == context.controlRevision()
                && world == context.level() && Objects.equals(playerId, context.player().getUUID());
    }

    private ResourceLocation carried(LocalPlayerContext context) {
        for (ResourceLocation id : accepted) {
            for (int slot = 0; slot < 36; slot++) {
                var stack = context.player().getInventory().getItem(slot);
                if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id)) return id;
            }
            var offhand = context.player().getOffhandItem();
            if (!offhand.isEmpty() && BuiltInRegistries.ITEM.getKey(offhand.getItem()).equals(id)) return id;
        }
        return null;
    }

    private static boolean worldReady(LocalPlayerContext context) {
        return DefaultBodyControlPort.permitsWorldMovement(context.minecraft().screen)
                && context.player().containerMenu == context.player().inventoryMenu
                && context.player().containerMenu.getCarried().isEmpty();
    }
    private Result available(ResourceLocation item, String detail) {
        result = new Result(State.AVAILABLE, item, detail); return result;
    }
    private Result unavailable(String detail) {
        result = new Result(State.UNAVAILABLE, null, detail); return result;
    }
}
