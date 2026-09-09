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

/**
 * 落地用品不足时做一次有时间上限的原地 AE2 取物尝试，并负责关好相关界面。优先水等免伤办法，干草保留为减伤后备。
 * 同一身体、世界和控制权内才继续；已经尝试过的失败供料不再循环重发。
 */
public final class LandingMaterialSupply {
    public enum State { AVAILABLE, ACQUIRING, CLEANING, UNAVAILABLE }
    public record Result(State state, ResourceLocation itemId, String detail) {
        public boolean finished() { return state == State.AVAILABLE || state == State.UNAVAILABLE; }
    }
    private static final int ACTION_RESERVE_TICKS = 6;
    private static final int MAX_SUPPLY_TICKS = 240;
    private static final ResourceLocation WATER = ResourceLocation.parse("minecraft:water_bucket");
    private static final ResourceLocation HAY = ResourceLocation.parse("minecraft:hay_block");
    private final List<ResourceLocation> accepted;
    private final BiFunction<LocalPlayer, Ae2ResourceSupply.Request, Ae2ResourceSupply.Session> begin;
    private Ae2ResourceSupply.Session session;
    private Result result = new Result(State.ACQUIRING, null, "checking carried landing material");
    private long started = Long.MIN_VALUE, lastTick = Long.MIN_VALUE, bodyEpoch, controlRevision;
    private Object world;
    private UUID playerId;
    private boolean stopping;
    private boolean acquisitionAttempted;
    private boolean allowCrafting, craftSubmitted;
    private String stopReason = "landing action window reached";

    public LandingMaterialSupply(List<ResourceLocation> acceptableIds) {
        this(acceptableIds, Ae2ResourceSupply::beginInPlace);
    }
    public static LandingMaterialSupply boats(List<ResourceLocation> acceptableIds) {
        var supply = new LandingMaterialSupply(acceptableIds); supply.allowCrafting = true; return supply;
    }
    public boolean craftSubmitted() { return craftSubmitted; }

    LandingMaterialSupply(List<ResourceLocation> acceptableIds,
            BiFunction<LocalPlayer, Ae2ResourceSupply.Request, Ae2ResourceSupply.Session> begin) {
        accepted = List.copyOf(Objects.requireNonNull(acceptableIds)).stream().distinct()
                .sorted(java.util.Comparator.comparingInt(id -> id.equals(WATER) ? 0 : id.equals(HAY) ? 2 : 1)).toList();
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
        // 有免伤用品就不启动 AE2；只有干草且时间还够时，会先尝试取得更好的候选。
        if (session == null && carried != null && (!carried.equals(HAY)
                || remainingTicks <= ACTION_RESERVE_TICKS || accepted.stream().allMatch(HAY::equals)))
            return available(carried, carried.equals(HAY)
                    ? "carried hay is the remaining mitigation fallback; no further supply window"
                    : "damage-free landing material already carried; AE was not used");
        if (session == null && (accepted.isEmpty() || remainingTicks <= ACTION_RESERVE_TICKS))
            return unavailable("no carried landing material before the native action window");
        if (session == null && acquisitionAttempted) return carried != null
                ? available(carried,"carried fallback retained after the bounded supply attempt")
                : unavailable("the bounded supply attempt already ended; no retry");
        try {
            if (session == null) {
                // Existing hay is retained as a fallback, never duplicated or allowed to hide
                // an obtainable damage-free aid in the network.
                var requested = carried != null && carried.equals(HAY)
                        ? accepted.stream().filter(id -> !id.equals(HAY)).toList() : accepted;
                var group = new Ae2ResourceSupply.Group(requested.getFirst(), requested, 1,
                        Ae2ResourceSupply.SelectionMode.SINGLE_VARIANT);
                acquisitionAttempted = true;
                session = begin.apply(context.player(), new Ae2ResourceSupply.Request(List.of(group), allowCrafting));
            }
            // 已经拿到合适物品、快进入操作窗口或补料超过二百四十刻，就停止新取物并结清已有事务。
            stopping |= carried != null && !carried.equals(HAY) || remainingTicks <= ACTION_RESERVE_TICKS
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
            craftSubmitted |= receipt.craftingJobsSubmitted() > 0 || receipt.craftingRequests() > 0;
            carried = carried(context);
            if (carried != null && worldReady(context)) return available(carried,
                    (carried.equals(HAY) ? "carried hay mitigation fallback" : "damage-free landing material observed in inventory")
                            + "; AE receipt=" + receipt.code()
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
            carried = carried(context);
            if (carried != null && worldReady(context))
                return available(carried, "carried material retained after supply ended: " + stopReason);
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
