// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.watch;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;
import org.maiwithu.maicraft.server.machine.ServerProductionEvents;

/** Consumes existing native history only. A current stock count alone never establishes production. */
public final class WatchProgress {
    private final WatchGoal goal;
    private final Map<String,Long> completed = new LinkedHashMap<>();
    private Map<String,String> recipes = Map.of();
    private String state = "pending_registration", reason = "authorize_positions", scope;
    private long baselineSequence, baselineTick, baselineStock, cursor, produced, delivered, currentStock;
    private long observedTick = -1, sinkTick = -1, progressTick = -1, idleTicks, revision;
    private boolean invalid, caughtUp, nativeProgress;

    public WatchProgress(WatchGoal goal) { this.goal = goal; goal.processes().forEach(process -> completed.put(process.id(),0L)); }
    public void arm(String scope, long sequence, long tick, long sinkStock, Map<String,String> nativeRecipes) {
        if (this.scope != null || scope == null || !scope.startsWith(goal.dimension() + ":") || sequence < 0 || tick < 0 || sinkStock < 0
                || !nativeRecipes.keySet().equals(completed.keySet())) throw WatchGoal.invalid("Invalid native monitor baseline");
        this.scope = scope; baselineSequence = cursor = sequence; baselineTick = observedTick = sinkTick = tick;
        baselineStock = currentStock = sinkStock; recipes = Map.copyOf(nativeRecipes); change("observing","waiting_for_native_events");
    }
    public boolean armed() { return scope != null; }
    public long cursor() { return cursor; }
    public String scope() { return scope; }
    public String state() { return state; }
    public boolean stopped() { return invalid || state.equals("completed") || state.equals("cancelled"); }
    public boolean caughtUp() { return caughtUp; }

    public void accept(JsonObject page) {
        if (!armed() || stopped()) return;
        try {
            if (!"maicraft.production_events.v1".equals(text(page,"schema")) || !scope.equals(text(page,"scope"))) throw WatchGoal.invalid("journal_scope_changed");
            long tick = number(page,"tick"), next = number(page,"next_sequence"), latest = number(page,"latest_sequence");
            if (tick < observedTick || next < cursor || next > latest || requiredFlag(page,"gap") || requiredFlag(page,"incomplete")) throw WatchGoal.invalid("native_history_incomplete");
            var events = page.getAsJsonArray("events");
            if (events == null || events.size() > 64) throw WatchGoal.invalid("native_history_page_invalid");
            long previous = cursor;
            for (var raw : events) {
                JsonObject event = raw.getAsJsonObject(); long sequence = number(event,"sequence");
                if (sequence <= cursor) continue;
                if (sequence <= previous || sequence > next || !scope.equals(text(event,"scope"))
                        || number(event,"tick") < baselineTick || number(event,"tick") > tick) throw WatchGoal.invalid("native_event_order_invalid");
                previous = sequence; acceptEvent(event);
            }
            cursor = next; observedTick = tick; caughtUp = !requiredFlag(page,"truncated");
            if (caughtUp && next != latest) throw WatchGoal.invalid("native_history_cursor_incomplete");
        } catch (RuntimeException malformed) { attention("native_history_unverifiable",true); }
    }

    private void acceptEvent(JsonObject event) {
        String kind = text(event,"kind");
        if (kind.equals("recipe_output") && text(event,"provenance").equals("native_recipe_output")) {
            var process = goal.processes().stream().filter(value -> ServerProductionEvents.key(value.position()).equals(text(event,"producer"))
                    && recipes.get(value.id()).equals(text(event,"recipe_id"))).findFirst().orElse(null);
            if (process == null || !flag(event,"completed")) return;
            long output = resources(event,"outputs",false), input = resources(event,"inputs",false);
            if (output == 0 && input == 0) return;
            long target = resources(event,"outputs",true);
            boolean useful = completed.get(process.id()) < process.minimumEvents() || target > 0 && produced < goal.minimumOutput()
                    || target > 0 && process.outputPosition().equals(goal.sink()) && delivered < goal.minimumOutput();
            completed.put(process.id(),Math.incrementExact(completed.get(process.id())));
            produced = Math.addExact(produced,target);
            if (process.outputPosition().equals(goal.sink())) delivered = Math.addExact(delivered,target);
            if (useful) progressed(number(event,"tick"));
        } else if (kind.equals("resource_transferred") && text(event,"provenance").equals("mekanism.transporter.native_forward_delivery")
                && ServerProductionEvents.key(goal.sink()).equals(text(event,"destination"))
                && goal.processes().stream().anyMatch(process -> !process.outputPosition().equals(goal.sink())
                    && ServerProductionEvents.key(process.outputPosition()).equals(text(event,"source"))) && matches(event)) {
            if (!event.has("extraction_sequence") || !event.has("extraction_tick") || !scope.equals(text(event,"extraction_scope"))) return;
            long extraction = number(event,"extraction_sequence"), tick = number(event,"extraction_tick");
            if (extraction <= baselineSequence || extraction >= number(event,"sequence") || tick < baselineTick || tick > number(event,"tick")) return;
            long amount = number(event,"amount"); if (amount <= 0) throw WatchGoal.invalid("invalid_native_delivery");
            boolean useful = delivered < goal.minimumOutput(); delivered = Math.addExact(delivered,amount);
            if (useful) progressed(number(event,"tick"));
        }
    }
    private long resources(JsonObject event, String field, boolean matchingOnly) {
        var values = event.getAsJsonArray(field); if (values == null || values.size() > 128) throw WatchGoal.invalid("native_resources_missing");
        long count = 0;
        for (var raw : values) {
            JsonObject row = raw.getAsJsonObject(); long amount = number(row,"amount");
            if (amount <= 0 || !ResourceIdentity.key(row.getAsJsonObject("identity")).equals(text(row,"resource_id"))) throw WatchGoal.invalid("native_resource_identity_invalid");
            if (!matchingOnly || matches(row)) count = Math.addExact(count,amount);
        }
        return count;
    }
    private boolean matches(JsonObject value) { return goal.resourceId().equals(text(value,"resource_id")) && goal.identity().equals(value.get("identity")); }
    private void progressed(long tick) { nativeProgress = true; progressTick = Math.max(progressTick,tick); revision++; }

    /** Call only after a fully loaded, identity-checked native sink read; unknown time never accrues idle evidence. */
    public void observedSink(long stock, long tick, long loadedElapsed) {
        if (!armed() || stopped()) return;
        if (stock < 0 || tick < observedTick || loadedElapsed < 0) { attention("native_snapshot_unverifiable",true); return; }
        observedTick = sinkTick = tick; currentStock = stock;
        if (!caughtUp) { change("observing","reading_native_history"); return; }
        idleTicks = nativeProgress ? 0 : Math.addExact(idleTicks,loadedElapsed); nativeProgress = false;
        if (completed.entrySet().stream().allMatch(entry -> entry.getValue() >= goal.processes().stream()
                .filter(process -> process.id().equals(entry.getKey())).findFirst().orElseThrow().minimumEvents())
                && produced >= goal.minimumOutput() && delivered >= goal.minimumOutput() && stock - baselineStock >= goal.minimumOutput())
            change("completed","native_production_and_sink_verified");
        else if (idleTicks > goal.idleTicks()) change("needs_attention","no_native_progress");
        else change("observing","waiting_for_native_events");
    }
    public void waitingLoaded() { if (!stopped()) change(armed() ? "observing" : "pending_registration","waiting_loaded"); }
    public void attention(String reason, boolean stop) { if (!state.equals("completed") && !state.equals("cancelled")) { invalid |= stop; change("needs_attention",reason); } }
    public void cancel() { if (!state.equals("cancelled") && !state.equals("completed")) change("cancelled","cancelled_by_owner"); }
    private void change(String state, String reason) {
        if (!this.state.equals(state) || !this.reason.equals(reason)) revision++;
        this.state = state; this.reason = reason;
    }
    public JsonObject report() {
        JsonObject result = new JsonObject(); result.addProperty("state",state); result.addProperty("reason",reason);
        result.addProperty("revision",revision); result.addProperty("armed",armed()); result.addProperty("machine_production_verified",state.equals("completed"));
        result.addProperty("native_output",produced); result.addProperty("native_delivery_or_direct_output",delivered);
        result.addProperty("sink_initial",baselineStock); result.addProperty("sink_current",currentStock); result.addProperty("sink_growth",currentStock-baselineStock);
        result.addProperty("sink_observed_tick",sinkTick); result.addProperty("sink_stock_observed",sinkTick >= 0);
        result.addProperty("minimum_output",goal.minimumOutput()); result.addProperty("resource_id",goal.resourceId());
        result.addProperty("idle_ticks_observed",idleTicks); result.addProperty("last_native_observation_tick",observedTick); result.addProperty("last_progress_tick",progressTick);
        result.addProperty("progress_scope","remaining_required_process_events_output_and_delivery");
        if (armed()) { result.addProperty("scope",scope); result.addProperty("baseline_sequence",baselineSequence); result.addProperty("baseline_tick",baselineTick); result.addProperty("cursor",cursor); }
        JsonObject counts = new JsonObject(); completed.forEach(counts::addProperty); result.add("completed_process_events",counts);
        result.addProperty("proof_scope","post-registration native processing, native endpoint delivery or direct bound output, and actual sink growth; no future output guarantee");
        return result;
    }
    private static String text(JsonObject value, String name) { return value != null && value.has(name) && value.get(name).isJsonPrimitive() ? value.get(name).getAsString() : ""; }
    private static long number(JsonObject value, String name) { return value.get(name).getAsBigDecimal().longValueExact(); }
    private static boolean flag(JsonObject value, String name) { return value.has(name) && value.get(name).isJsonPrimitive() && value.getAsJsonPrimitive(name).isBoolean() && value.get(name).getAsBoolean(); }
    private static boolean requiredFlag(JsonObject value, String name) {
        if (!value.has(name) || !value.get(name).isJsonPrimitive() || !value.getAsJsonPrimitive(name).isBoolean()) throw WatchGoal.invalid("Missing native flag " + name);
        return value.get(name).getAsBoolean();
    }
}
