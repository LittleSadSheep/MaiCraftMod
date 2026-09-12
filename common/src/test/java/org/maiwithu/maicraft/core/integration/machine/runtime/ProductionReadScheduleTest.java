// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionDesignCompiler;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.Point;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionNativeEvidence;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionNativeEvidence.*;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** Finite scheduling and real wire-shape tests; travel numbers model read-target order, not actual game completion. */
public final class ProductionReadScheduleTest {
    private ProductionReadScheduleTest() {}
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        sharedSnapshotsAndFrozenRequests(); stageDeadlineDoesNotDependOnNavigationProgress();
        compareOrder(fixture());
        if (args.length == 1) compareOrder(JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject());
        System.out.println("ProductionReadScheduleTest: shared native rows, bounded expiry refresh, immutable requests and finite stage budget passed");
    }

    private static void sharedSnapshotsAndFrozenRequests() {
        var plan = plan(fixture()); var reads = new ProductionReadSchedule(plan); var evidence = evidence(plan);
        var read = reads.next(Vec3.ZERO); JsonObject original = read.body();
        reads.submitted(); read.body().addProperty("tampered", true);
        check(reads.next(new Vec3(100, 0, 100)) == read && read.body().equals(original), "Pending read changed target or request body");
        rejects(reads::requireSettled, "before_receipt_consumed");
        JsonObject response = snapshot(read.position(), 100); response.addProperty("tick", 999);
        reads.complete(evidence, response);
        check(evidence.freshness(100).stream().filter(fact -> fact.kind() == ObservationKind.NODE && fact.id().equals("source"))
                .allMatch(fact -> fact.tick() == 100), "Snapshot fan-out replaced the original row tick with its envelope tick");
        boolean union = false, emptyNodeFaces = false;
        ProductionReadSchedule.Read next;
        while ((next = reads.next(Vec3.ZERO)) != null) {
            if (next.ports().contains("press.in")) {
                check(next.ports().contains("press.out") && next.body().getAsJsonArray("faces").size() == 2, "Co-located ports lost their face union"); union = true;
            }
            if (next.nodes().contains("press")) emptyNodeFaces = next.body().getAsJsonArray("faces").isEmpty();
            reads.submitted(); reads.complete(evidence, new JsonObject());
        }
        check(union && emptyNodeFaces, "Shared snapshots changed node-default empty-face semantics");
        var stale = evidence.freshness(1301).stream().filter(fact -> fact.kind() == ObservationKind.NODE && fact.id().equals("source")).findFirst().orElseThrow();
        check(stale.status() == FreshnessStatus.EXPIRED && reads.refresh(stale), "Expired accepted fact did not receive its bounded refresh");
        var refreshed = reads.next(Vec3.ZERO); reads.submitted(); reads.complete(evidence, snapshot(refreshed.position(), 1301));
        var staleAgain = new ObservationFreshness(stale.kind(), stale.id(), FreshnessStatus.EXPIRED, 1301L, 1201);
        check(!reads.refresh(staleAgain), "A fresh UUID restarted the same fact's refresh allowance");
        for (FreshnessStatus state : List.of(FreshnessStatus.FRESH, FreshnessStatus.MISSING, FreshnessStatus.INVALID))
            check(!reads.refresh(new ObservationFreshness(ObservationKind.RECIPE, "press", state, null, -1)), "Unknown or invalid native data triggered repeat observation");
        check(!ProductionDesignCompiler.compile(plan.authoredJson(), evidence).canEnter("supply"), "Missing/invalid native recipe evidence became ready");
    }

    private static void compareOrder(JsonObject authored) {
        var plan = plan(authored); var old = new ArrayList<BlockPos>();
        plan.manifest().nodes().forEach(node -> old.add(plan.at(node)));
        plan.manifest().ports().forEach(port -> old.add(plan.at(port.offset())));
        plan.manifest().nodes().stream().filter(node -> node.kind().equals("process")).forEach(node -> old.add(plan.at(node)));
        plan.manifest().configurations().forEach(configuration -> old.add(plan.at(plan.node(configuration.node()))));
        var reads = new ProductionReadSchedule(plan); var evidence = evidence(plan); var ordered = new ArrayList<BlockPos>(); Vec3 feet = Vec3.ZERO;
        ProductionReadSchedule.Read read;
        while ((read = reads.next(feet)) != null) {
            check(Set.of("machine.snapshot", "machine.recipe", "machine.configuration").contains(read.operation()), "Preparation scheduled a mutation");
            ordered.add(read.position()); feet = read.position().getCenter(); reads.submitted(); reads.complete(evidence, new JsonObject());
        }
        check(ordered.size() < old.size() && distance(ordered) < distance(old), "Spatial read jobs did not reduce requests and repeated plant traversal");
        var progress = reads.progress();
        check(progress.get("nodes").equals(plan.manifest().nodes().size()) && progress.get("ports").equals(plan.manifest().ports().size()), "Shared requests omitted native subjects");
        if (plan.manifest().nodes().size() == 21 && plan.manifest().ports().size() == 21)
            check(old.size() == 62 && ordered.size() == 45, "Actual four-station read count changed unexpectedly");
        System.out.println("Read target model: old_requests=" + old.size() + " scheduled_requests=" + ordered.size()
                + " old_target_distance=" + distance(old) + " scheduled_target_distance=" + distance(ordered));
    }

    private static void stageDeadlineDoesNotDependOnNavigationProgress() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            JsonObject authored = fixture(); authored.add("configurations", new JsonArray());
            var work = new ProductionWork() {
                public boolean approach(BlockPos position) { return false; }
                public JsonObject request(String operation, JsonObject arguments, boolean mutating) { throw new AssertionError("Navigation had not settled"); }
                public TaskState advanceChild(Task task) { throw new AssertionError("Read scheduling created a mutation child"); }
                public void extendDeadlineTo(long tick) { throw new AssertionError("No valid new native fact was observed"); }
            };
            var preparation = new ProductionPreparation(world.player, plan(authored), work);
            check(!preparation.tick(), "Unfinished movement became a completed read");
            var time = world.level.getClass().getDeclaredField("time"); time.setAccessible(true); time.setLong(world.level, world.level.getGameTime() + 3601);
            rejects(preparation::tick, "production_preparation_budget_exhausted");
        }
    }

    private static JsonObject snapshot(BlockPos position, long tick) {
        JsonObject result = new JsonObject(); result.addProperty("schema", "maicraft.machine_snapshot.v1"); result.addProperty("dimension", "minecraft:overworld");
        JsonObject row = new JsonObject(); row.add("position", ProductionRunPlan.position(position)); row.addProperty("tick", tick);
        row.addProperty("provenance", "server_native"); row.addProperty("block_id", "minecraft:barrel");
        JsonArray rows = new JsonArray(); rows.add(row); result.add("observations", rows); return result;
    }
    private static double distance(List<BlockPos> positions) { double length = 0; Vec3 at = Vec3.ZERO; for (BlockPos position : positions) { length += at.distanceTo(position.getCenter()); at = position.getCenter(); } return length; }
    private static ProductionRunPlan plan(JsonObject json) { return new ProductionRunPlan(BlockPos.ZERO, "minecraft:overworld", json); }
    private static ProductionNativeEvidence evidence(ProductionRunPlan plan) { var evidence = new ProductionNativeEvidence(plan.manifest()); evidence.bind(plan.dimension(), new Point(0, 0, 0)); return evidence; }
    private static void rejects(Runnable action, String code) { try { action.run(); throw new AssertionError("Expected " + code); } catch (IllegalStateException | IllegalArgumentException failure) { check(failure.getMessage().contains(code), "Unexpected failure: " + failure); } }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static JsonObject fixture() {
        return JsonParser.parseString("""
                {"schema_version":1,"nodes":[{"id":"source","kind":"source","offset":[0,0,0],"material_policy":"inventory_only"},
                 {"id":"press","kind":"process","offset":[4,2,0],"recipe_id":"test:press","batches":3},{"id":"sink","kind":"sink","offset":[8,0,0]}],
                 "ports":[{"id":"source.out","node":"source","offset":[0,0,0],"face":"east","medium":"items","direction":"output"},
                 {"id":"press.in","node":"press","offset":[4,0,0],"face":"west","medium":"items","direction":"input"},
                 {"id":"press.out","node":"press","offset":[4,0,0],"face":"east","medium":"items","direction":"output"},
                 {"id":"sink.in","node":"sink","offset":[8,0,0],"face":"west","medium":"items","direction":"input"}],
                 "links":[{"id":"feed","from":"source.out","to":"press.in","medium":"items","resource":"minecraft:stone","amount":3,"path":[[0,0,0],[1,0,0],[2,0,0],[3,0,0],[4,0,0]]},
                 {"id":"delivery","from":"press.out","to":"sink.in","medium":"items","resource":"minecraft:stone","amount":3,"path":[[4,0,0],[5,0,0],[6,0,0],[7,0,0],[8,0,0]]}],
                 "configurations":[{"id":"filter","node":"press","operation":"machine.configure","stage":"configure","arguments":{"action":"create.filter","item_id":"minecraft:stone"}}],
                 "target":{"node":"sink","medium":"items","resource":"minecraft:stone"},"observation":{"window_ticks":20,"minimum_output":3,"minimum_events":3,"max_idle_ticks":20}}
                """).getAsJsonObject();
    }
}
