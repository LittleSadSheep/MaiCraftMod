// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.watch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;
import org.maiwithu.maicraft.server.machine.ProductionEventJournal;
import org.maiwithu.maicraft.server.machine.ServerProductionEvents;

/** Uses the real retained journal and native wire shapes; no world writes or monitor-supplied events. */
public final class MachineWatchProgressTest {
    private static final String WORLD = "minecraft:overworld", RECIPE = "create:pressing/iron_ingot";
    private static final BlockPos PRESS = new BlockPos(0,2,0), OUTPUT = BlockPos.ZERO, SINK = new BlockPos(2,0,0);
    private static final JsonObject ITEM = JsonParser.parseString("{\"kind\":\"items\",\"id\":\"create:iron_sheet\",\"components\":{}}").getAsJsonObject();
    public static void main(String[] args) {
        actualEventsAndSinkRequired(); directNativeOutput(); baselineAndUnrelatedHistory(); unloadDoesNotAdvanceEvidence();
        starvationAndResume(); completedWorkCannotHideBlockedDelivery(); invalidEvidenceCannotComplete(); finiteGoalBounds();
        System.out.println("MachineWatchProgressTest: native journal, sink, baseline, unload and finite-goal invariants passed");
    }
    private static void directNativeOutput() {
        JsonObject spec = specification(); spec.getAsJsonObject("sink").add("position",WatchNativeAccess.position(OUTPUT));
        var f = new Fixture(spec); f.arm(10,3); f.produce(11); f.produce(12); f.poll(13,5,3);
        check(f.progress.state().equals("completed"), "native output directly into its bound sink needs no invented pipe delivery");
    }
    private static void actualEventsAndSinkRequired() {
        var f = new Fixture(); f.arm(10,0); f.produce(11);
        f.poll(12,2,2);
        check(!f.progress.state().equals("completed"), "one processing operation plus external stock does not satisfy two events");
        f.produce(13); f.poll(14,2,2);
        check(!f.progress.state().equals("completed"), "production and stock growth without native sink delivery remain unproven");
        f.deliver(15,f.journal.markExtraction(15)); f.deliver(16,f.journal.markExtraction(16)); f.poll(17,1,3);
        check(!f.progress.state().equals("completed"), "current sink must contain the actual required net growth");
        f.poll(18,2,1); check(f.progress.state().equals("completed"), "two distinct native outputs and deliveries plus sink growth complete");
        check(f.progress.report().getAsJsonObject("completed_process_events").get("press").getAsLong() == 2,
                "native operations metadata never multiplies the distinct event count");
        f.progress.cancel(); check(f.progress.state().equals("completed"), "cleanup cannot erase an already verified finite completion");
    }
    private static void baselineAndUnrelatedHistory() {
        var f = new Fixture(); f.produce(1); var oldExtraction = f.journal.markExtraction(2); f.arm(10,4);
        rejects(() -> f.progress.arm(f.progress.scope(),f.progress.cursor(),10,4,Map.of("press",RECIPE)), "baseline cannot be reset");
        f.produce(11); f.produce(12); f.deliver(13,oldExtraction); f.poll(14,6,4);
        check(f.progress.report().get("native_output").getAsLong() == 2
                        && f.progress.report().get("native_delivery_or_direct_output").getAsLong() == 0,
                "pre-registration output and extraction cannot fund a new monitor");
        for (int i = 0; i < 20_000; i++) {
            JsonObject other = new JsonObject(); other.addProperty("producer","unrelated-" + i); other.addProperty("tick",15); f.journal.append(other);
        }
        f.deliver(16,f.journal.markExtraction(16)); f.deliver(17,f.journal.markExtraction(17)); f.poll(18,6,4);
        check(f.progress.state().equals("completed"), "unrelated traffic cannot erase watched endpoint history");
        check(f.progress.report().get("sink_initial").getAsLong() == 4, "pre-existing legitimate sink inventory is preserved, never fabricated away");
    }
    private static void unloadDoesNotAdvanceEvidence() {
        var f = new Fixture(); f.arm(10,0); f.poll(11,0,1); long observed = f.progress.report().get("last_native_observation_tick").getAsLong();
        f.progress.waitingLoaded();
        check(f.progress.state().equals("observing") && f.progress.report().get("reason").getAsString().equals("waiting_loaded")
                        && f.progress.report().get("last_native_observation_tick").getAsLong() == observed,
                "unloaded positions never refresh timestamps or become machine failure/completion");
        f.poll(10_000,0,0);
        check(f.progress.state().equals("observing") && f.progress.report().get("idle_ticks_observed").getAsLong() == 1,
                "unknown unloaded time cannot become evidence of no native progress");
        f.progress.attention("needs_reinspect",true); f.produce(10_001); f.produce(10_002); f.poll(10_003,2,1);
        check(!f.progress.state().equals("completed"), "changed or reloaded endpoint identity cannot reuse an old read lease");
    }
    private static void starvationAndResume() {
        var f = new Fixture(); f.arm(10,0); f.poll(131,0,121);
        check(f.progress.state().equals("needs_attention") && f.progress.report().get("reason").getAsString().equals("no_native_progress"),
                "complete loaded observations can request inspection without inventing a specific missing material");
        f.produce(132); f.deliver(133,f.journal.markExtraction(133)); f.poll(134,1,3);
        check(f.progress.state().equals("observing") && f.progress.report().get("idle_ticks_observed").getAsLong() == 0,
                "real native progress after manual replenishment resumes the same finite goal");
    }
    private static void completedWorkCannotHideBlockedDelivery() {
        var f = new Fixture(); f.arm(10,0); f.produce(11); f.produce(12); f.poll(13,0,3);
        f.produce(130); f.poll(135,0,122);
        check(f.progress.state().equals("needs_attention") && f.progress.report().get("native_output").getAsLong() == 3,
                "extra output beyond the goal cannot indefinitely conceal missing required delivery");
    }
    private static void invalidEvidenceCannotComplete() {
        var foreign = new Fixture(); foreign.arm(10,0);
        var other = new ProductionEventJournal(WORLD); foreign.progress.accept(other.page(0,Set.of("0,2,0"),11,null));
        check(foreign.progress.state().equals("needs_attention") && foreign.progress.stopped(), "a new native journal scope requires registration again");
        var gap = new Fixture(); gap.arm(10,0); JsonObject page = gap.page(11); page.addProperty("gap",true);
        gap.progress.accept(page); gap.progress.observedSink(2,12,2);
        check(!gap.progress.state().equals("completed"), "missing retained native history cannot become completion");
        var malformed = new Fixture(); malformed.arm(10,0); page = malformed.page(11); page.remove("incomplete");
        malformed.progress.accept(page); check(malformed.progress.stopped(), "missing page completeness is not implicitly complete");
        var wrong = new Fixture(); wrong.arm(10,0); JsonObject event = production(11);
        event.getAsJsonArray("outputs").get(0).getAsJsonObject().getAsJsonObject("identity").getAsJsonObject("components").addProperty("different",true);
        wrong.journal.append(event); wrong.poll(12,2,2);
        check(wrong.progress.stopped() && !wrong.progress.state().equals("completed"), "component identity corruption never enters production evidence");
    }
    private static void finiteGoalBounds() {
        JsonObject spec = specification(); var goal = WatchGoal.parse(spec); spec.getAsJsonObject("target").addProperty("minimum_output",999);
        check(goal.minimumOutput() == 2 && goal.specification().getAsJsonObject("target").get("minimum_output").getAsInt() == 2,
                "monitor approval is immutable when the caller changes its input object");
        spec = specification(); spec.addProperty("max_duration_ticks",72_001); JsonObject tooLong = spec;
        rejects(() -> WatchGoal.parse(tooLong), "monitor leases are finite");
        spec = specification(); spec.getAsJsonArray("processes").add(spec.getAsJsonArray("processes").get(0).deepCopy()); JsonObject duplicate = spec;
        rejects(() -> WatchGoal.parse(duplicate), "one native event cannot satisfy two aliased processes");
        spec = specification(); spec.getAsJsonObject("target").addProperty("resource_id","invented"); JsonObject identity = spec;
        rejects(() -> WatchGoal.parse(identity), "caller cannot invent a component key");
    }
    private static final class Fixture {
        final WatchGoal goal; final ProductionEventJournal journal = new ProductionEventJournal(WORLD);
        final WatchProgress progress; final Set<String> positions;
        Fixture() { this(specification()); }
        Fixture(JsonObject spec) {
            goal = WatchGoal.parse(spec); progress = new WatchProgress(goal);
            positions = goal.positions().stream().map(ServerProductionEvents::key).collect(java.util.stream.Collectors.toSet());
        }
        void arm(long tick, long stock) {
            check(journal.retain(this,positions), "native retention available"); long sequence = journal.latestSequence();
            progress.arm(journal.page(sequence,positions,tick,null).get("scope").getAsString(),sequence,tick,stock,Map.of("press",RECIPE));
        }
        void produce(long tick) { journal.append(production(tick)); }
        void deliver(long tick, ProductionEventJournal.OrderingMarker extraction) {
            JsonObject event = resource(); event.addProperty("kind","resource_transferred"); event.addProperty("producer",ServerProductionEvents.key(OUTPUT));
            event.addProperty("source",ServerProductionEvents.key(OUTPUT)); event.addProperty("destination",ServerProductionEvents.key(SINK));
            event.addProperty("tick",tick); event.addProperty("provenance","mekanism.transporter.native_forward_delivery");
            event.addProperty("extraction_scope",extraction.scope()); event.addProperty("extraction_sequence",extraction.sequence()); event.addProperty("extraction_tick",extraction.tick()); journal.append(event);
        }
        JsonObject page(long tick) { return journal.page(progress.cursor(),positions,tick,progress.scope()); }
        void poll(long tick, long stock, long elapsed) { progress.accept(page(tick)); progress.observedSink(stock,tick,elapsed); }
    }
    private static JsonObject specification() {
        JsonObject spec = JsonParser.parseString("""
            {"dimension":"minecraft:overworld","processes":[{"id":"press","position":{"x":0,"y":2,"z":0},
            "output_position":{"x":0,"y":0,"z":0},"recipe_id":"create:pressing/iron_ingot","minimum_events":2}],
            "sink":{"position":{"x":2,"y":0,"z":0},"side":"west"},"target":{"minimum_output":2},
            "idle_ticks":120,"max_duration_ticks":72000}
            """).getAsJsonObject();
        spec.getAsJsonObject("target").add("identity",ITEM.deepCopy()); spec.getAsJsonObject("target").addProperty("resource_id",ResourceIdentity.key(ITEM)); return spec;
    }
    private static JsonObject production(long tick) {
        JsonObject event = new JsonObject(); event.addProperty("kind","recipe_output"); event.addProperty("completed",true);
        event.addProperty("producer",ServerProductionEvents.key(PRESS)); event.addProperty("recipe_id",RECIPE); event.addProperty("provenance","native_recipe_output");
        event.addProperty("tick",tick); event.addProperty("operations",9000); JsonArray outputs = new JsonArray(); outputs.add(resource());
        event.add("outputs",outputs); event.add("inputs",new JsonArray()); return event;
    }
    private static JsonObject resource() { JsonObject value = new JsonObject(); value.add("identity",ITEM.deepCopy()); value.addProperty("resource_id",ResourceIdentity.key(ITEM)); value.addProperty("amount",1); return value; }
    private static void rejects(Runnable action, String message) {
        try { action.run(); throw new AssertionError(message); }
        catch (IllegalArgumentException | org.maiwithu.maicraft.network.ServerOperationException expected) { }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
