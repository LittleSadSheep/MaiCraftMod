// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.client.server.ClientMachineWatches;
import org.maiwithu.maicraft.client.server.ServerAssistClient;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.integration.machine.catalog.ClientMachineCatalog;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionEvidence;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionNativeEvidence;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.TaskState;

/** Authorize known endpoints once, then return the body; server-native observations own future completion. */
final class MachineWatchTask extends AbstractCompanionTask<MachineWatchTaskRecord> {
    private final UUID job = UUID.randomUUID();
    private final ProductionRequestSlot requests = new ProductionRequestSlot();
    private JsonObject goal, registered;
    private List<List<BlockPos>> groups = List.of();
    private List<BlockPos> navigating = List.of();
    private int group;
    private boolean completed, submitted;
    MachineWatchTask(LocalPlayer player, MachineWatchTaskRecord record) { super(player,record); }

    @Override protected TaskState onTick() {
        try {
            if (!player.level().dimension().location().toString().equals(r.plan.dimension())) throw new IllegalStateException("machine_watch_world_changed");
            if (!ServerAssistClient.supported("machine.watch") && !ServerAssistClient.renegotiating("machine.watch"))
                throw new IllegalStateException("machine_watch_requires_server_support");
            if (goal == null) {
                var target = r.plan.manifest().target().resource();
                var producer = r.plan.manifest().links().stream().filter(link -> link.resource().equals(target))
                        .map(link -> r.plan.node(r.plan.port(link.from()).node())).filter(node -> node.kind().equals("process")).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("machine_watch_target_producer_missing"));
                if (!near(List.of(r.plan.at(producer)))) return TaskState.RUNNING;
                JsonObject query = new JsonObject(); query.add("position",ProductionRunPlan.position(r.plan.at(producer))); query.addProperty("recipe_id",producer.recipeId());
                JsonObject recipe = requests.call("machine.recipe",query,false); if (recipe == null) return TaskState.RUNNING;
                var evidence = new ProductionNativeEvidence(r.plan.manifest());
                evidence.bind(r.plan.dimension(),new ProductionManifest.Point(r.plan.anchor().getX(),r.plan.anchor().getY(),r.plan.anchor().getZ()));
                evidence.observeRecipe(producer.id(),recipe);
                var binding = evidence.resolve(target);
                if (binding.check().status() != ProductionEvidence.Status.VERIFIED) throw new IllegalArgumentException("machine_watch_exact_output_identity_unknown");
                goal = specification(binding); groups = groups(goal);
                ClientMachineCatalog.registerPlan(player,r.label,r.plan);
                return TaskState.RUNNING;
            }
            if (!near(groups.get(group))) return TaskState.RUNNING;
            JsonObject body = new JsonObject(); body.addProperty("action","register"); body.addProperty("job_id",job.toString()); body.add("goal",goal.deepCopy());
            JsonArray positions = new JsonArray(); groups.get(group).forEach(position -> positions.add(ProductionRunPlan.position(position))); body.add("authorize_positions",positions);
            submitted = true;
            JsonObject result = requests.call("machine.watch",body,false); if (result == null) return TaskState.RUNNING;
            registered = result; group++;
            if (group < groups.size()) return TaskState.RUNNING;
            if (!result.has("armed") || !result.get("armed").getAsBoolean() || !result.get("monitor_active").getAsBoolean())
                throw new IllegalStateException("machine_watch_registration_incomplete: " + result.get("reason"));
            ClientMachineWatches.track(player,job,r.label,result); completed = true;
            return TaskState.SUCCESS;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            fail(failure.getMessage(),FailureType.UNKNOWN); return TaskState.FAILED;
        }
    }
    private JsonObject specification(ProductionEvidence.Binding binding) {
        JsonObject specification = new JsonObject(); specification.addProperty("dimension",r.plan.dimension()); JsonArray processes = new JsonArray();
        for (var node : r.plan.manifest().nodes()) if (node.kind().equals("process")) {
            var outputs = r.plan.manifest().ports().stream().filter(port -> port.node().equals(node.id()) && port.direction().equals("output") && port.medium().equals("items"))
                    .map(port -> r.plan.at(port.offset())).distinct().toList();
            if (outputs.size() != 1) throw new IllegalArgumentException("machine_watch_requires_one_native_item_output_position_per_process");
            JsonObject process = new JsonObject(); process.addProperty("id",node.id()); process.add("position",ProductionRunPlan.position(r.plan.at(node)));
            process.add("output_position",ProductionRunPlan.position(outputs.getFirst())); process.addProperty("recipe_id",node.recipeId());
            process.addProperty("minimum_events",r.minimumProcessEvents); processes.add(process);
        }
        specification.add("processes",processes);
        var sink = r.plan.manifest().ports().stream().filter(port -> port.node().equals(r.plan.manifest().target().node()) && port.direction().equals("input")
                && port.medium().equals("items")).findFirst().orElseThrow(() -> new IllegalArgumentException("machine_watch_item_sink_missing"));
        JsonObject destination = new JsonObject(); destination.add("position",ProductionRunPlan.position(r.plan.at(sink.offset()))); destination.addProperty("side",sink.face());
        specification.add("sink",destination);
        JsonObject target = new JsonObject(); target.add("identity",binding.identity()); target.addProperty("resource_id",binding.resource().id());
        target.addProperty("minimum_output",r.plan.manifest().observation().minimumOutput()); specification.add("target",target);
        specification.addProperty("idle_ticks",r.idleTicks); specification.addProperty("max_duration_ticks",r.durationTicks);
        org.maiwithu.maicraft.server.machine.watch.WatchGoal.parse(specification);
        return specification;
    }
    private static List<List<BlockPos>> groups(JsonObject goal) {
        var positions = new LinkedHashSet<BlockPos>();
        for (var raw : goal.getAsJsonArray("processes")) {
            var process = raw.getAsJsonObject(); positions.add(org.maiwithu.maicraft.server.machine.ServerAccess.position(process.getAsJsonObject("position")));
            positions.add(org.maiwithu.maicraft.server.machine.ServerAccess.position(process.getAsJsonObject("output_position")));
        }
        positions.add(org.maiwithu.maicraft.server.machine.ServerAccess.position(goal.getAsJsonObject("sink").getAsJsonObject("position")));
        return ProductionOutputMonitor.group(positions);
    }
    private boolean near(List<BlockPos> positions) {
        if (requests.pending()) return true;
        if (!navigating.equals(positions)) { stopNav(); navigating = List.copyOf(positions); }
        java.util.function.BooleanSupplier ready = () -> positions.stream().allMatch(player.level()::isLoaded)
                && ProductionObservationRange.ready(player.position(),positions,p -> ProductionObservationRange.radius(player.level(),p));
        if (ready.getAsBoolean()) { stopNav(); return true; }
        if (nav == null) nav = PlayerNav.toGoal(player,() -> NavGoal.near(positions.getFirst(),ProductionObservationRange.goalRadius(positions,p -> ProductionObservationRange.radius(player.level(),p))),
                1.0,ready,PlayerNav.ContextProvider.DEFAULT);
        var state = nav.tick();
        if (state == PlayerNav.Status.FAILED || state == PlayerNav.Status.ARRIVED && !ready.getAsBoolean()) throw new IllegalStateException("machine_watch_endpoint_unreachable");
        return false;
    }
    @Override protected void cleanup() {
        requests.cancel();
        if (submitted && !completed && ServerAssistClient.supported("machine.watch")) {
            JsonObject cancel = new JsonObject(); cancel.addProperty("action","cancel"); cancel.addProperty("job_id",job.toString());
            ServerAssistClient.submit("machine.watch",cancel,false,null);
        }
        super.cleanup();
    }
    @Override public Map<String,Object> progress() { return Map.of("task",name(),"authorized_groups",group,"total_groups",groups.size(),"body_needed",!completed); }
    @Override protected Map<String,Object> resultData() {
        return Map.of("job_id",job.toString(),"monitor_registered",completed,"machine_production_verified",false,
                "uses_player_body_after_registration",false,"label",r.label,"monitor",registered == null ? new JsonObject() : registered);
    }
    @Override protected String successMessage() { return "后台观察已登记，可以去做其他事情；本任务不代表产出已完成，完成或异常将通过 Attention 报告。"; }
}
