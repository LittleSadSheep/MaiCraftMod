// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.integration.machine.MachineSnapshots;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.TaskState;

/** Reads one bounded native page per request without movement, menu opening or game-thread waits. */
final class ServerMachineObservationTask extends AbstractCompanionTask<ServerMachineObservationTaskRecord> {
    private record Target(int componentIndex, BlockPos position) {}
    private final MachineObservationPages pages = new MachineObservationPages();
    private final List<Target> targets = new ArrayList<>();
    private Level world;
    private JsonObject report;
    private ClientRequestReceipt pending;
    private int targetIndex;
    private int resourceOffset;
    private int resourcePages;
    private int continuation = -1;
    private int resourceContinuation;
    private long requestTick;
    private boolean finished;
    private final Set<Integer> observedComponents = new HashSet<>();

    ServerMachineObservationTask(LocalPlayer player, ServerMachineObservationTaskRecord record) { super(player, record); }

    @Override protected void onStart() {
        world = player.level();
        report = r.snapshot.report();
        resourceOffset = r.resourceOffset;
        if (player != Minecraft.getInstance().player || !world.dimension().location().toString().equals(r.snapshot.dimension())) {
            fail("Machine snapshot belongs to a different world.", FailureType.TARGET_LOST);
            return;
        }
        if (!ServerAssistClient.serverSupported("machine.snapshot")) { r.observed(); finished = true; return; }
        Set<Integer> declared = new HashSet<>();
        if (report.has("component_evidence")) for (var raw : report.getAsJsonArray("component_evidence")) {
            var component = raw.getAsJsonObject();
            if (component.has("block_index")) declared.add(component.get("block_index").getAsInt());
        }
        var rows = report.getAsJsonArray("relative_blocks");
        if (rows != null) for (int index = r.componentOffset; index < rows.size(); index++) {
            var row = rows.get(index).getAsJsonArray();
            BlockPos position = r.snapshot.center().offset(row.get(0).getAsInt(), row.get(1).getAsInt(), row.get(2).getAsInt());
            if (!world.isLoaded(position)) { pages.incomplete.add("component_chunk_unloaded"); continue; }
            if (world.getBlockEntity(position) == null && !declared.contains(index)) continue;
            if (player.distanceToSqr(position.getCenter()) > 16 * 16) {
                pages.incomplete.add("components_outside_observation_range");
                continue;
            }
            if (targets.size() >= 64) {
                continuation = index;
                pages.incomplete.add("component_budget_exhausted");
                break;
            }
            targets.add(new Target(index, position.immutable()));
        }
        if (targets.isEmpty() && r.componentOffset == 0 && world.isLoaded(r.snapshot.center())
                && player.distanceToSqr(r.snapshot.center().getCenter()) <= 16 * 16)
            targets.add(new Target(0, r.snapshot.center()));
        if (!report.get("structure_complete").getAsBoolean()) pages.incomplete.add("client_structure_incomplete");
        if (targets.isEmpty()) pages.incomplete.add("no_components_within_observation_range");
    }

    @Override protected TaskState onTick() {
        if (finished) return TaskState.SUCCESS;
        if (player != Minecraft.getInstance().player || player.level() != world) {
            fail("Player or world changed during native machine observation.", FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        if (pending != null) return poll();
        if (targetIndex >= targets.size()) return finish();
        if (world.getGameTime() >= r.getDeadlineGameTime()) {
            pages.incomplete.add("observation_deadline");
            return finish();
        }
        Target target = targets.get(targetIndex);
        if (!world.isLoaded(target.position()) || player.distanceToSqr(target.position().getCenter()) > 16 * 16) {
            pages.incomplete.add("component_left_observation_range");
            targetIndex++;
            return TaskState.RUNNING;
        }
        JsonObject point = new JsonObject();
        point.addProperty("x", target.position().getX());
        point.addProperty("y", target.position().getY());
        point.addProperty("z", target.position().getZ());
        JsonArray positions = new JsonArray();
        positions.add(point);
        JsonObject body = new JsonObject();
        body.add("positions", positions);
        body.addProperty("resource_offset", resourceOffset);
        body.addProperty("resource_limit", 32);
        pending = ServerAssistClient.submit("machine.snapshot", body, false);
        requestTick = world.getGameTime();
        return TaskState.RUNNING;
    }

    private TaskState poll() {
        var receipt = pending.snapshot();
        if (!receipt.settled() && world.getGameTime() - requestTick <= 120) return TaskState.RUNNING;
        if (!receipt.settled() || receipt.retired() || receipt.status() != ClientRequestReceipt.Status.SUCCEEDED
                || receipt.backend() != ClientRequestReceipt.Backend.SERVER) {
            pages.incomplete.add("server_observation_" + receipt.code());
            ServerAssistClient.cancel(pending.id());
            pending = null;
            return finish();
        }
        JsonObject page = receipt.result();
        if (!r.snapshot.dimension().equals(ServerCapabilityState.text(page, "dimension"))) {
            pages.incomplete.add("server_dimension_mismatch");
            pending = null;
            return finish();
        }
        var observations = page.getAsJsonArray("observations");
        Target target = targets.get(targetIndex);
        if (observations == null || observations.isEmpty()) pages.incomplete.add("component_not_returned");
        else for (var raw : observations) {
            JsonObject point = raw.getAsJsonObject().getAsJsonObject("position");
            if (point.get("x").getAsInt() != target.position().getX() || point.get("y").getAsInt() != target.position().getY()
                    || point.get("z").getAsInt() != target.position().getZ()) {
                pages.incomplete.add("server_position_mismatch");
                pending = null;
                return finish();
            }
        }
        if (!pages.append(page, receipt.requestId().toString())) {
            continuation = targets.get(targetIndex).componentIndex();
            resourceContinuation = resourceOffset;
            pending = null;
            return finish();
        }
        if (observations != null && !observations.isEmpty()) observedComponents.add(target.componentIndex());
        pending = null;
        resourcePages++;
        int next = page.has("next_resource_offset") ? page.get("next_resource_offset").getAsInt() : 0;
        if (page.has("truncated") && page.get("truncated").getAsBoolean() && next > resourceOffset && next <= 4096) {
            if (resourcePages < 4) resourceOffset = next;
            else {
                pages.incomplete.add("component_resource_pages_exhausted");
                continuation = target.componentIndex();
                resourceContinuation = next;
                return finish();
            }
        }
        else {
            if (page.has("truncated") && page.get("truncated").getAsBoolean()) pages.incomplete.add("component_resource_pages_exhausted");
            targetIndex++;
            resourceOffset = 0;
            resourcePages = 0;
        }
        return TaskState.RUNNING;
    }

    private TaskState finish() {
        if (targetIndex < targets.size()) {
            pages.incomplete.add("components_not_observed");
            if (continuation < 0) continuation = targets.get(targetIndex).componentIndex();
        }
        JsonObject evidence = pages.report(targets.size(), observedComponents.size(), continuation, r.snapshot.gameTime());
        evidence.addProperty("start_component_index", r.componentOffset);
        evidence.addProperty("start_resource_offset", r.resourceOffset);
        evidence.addProperty("next_resource_offset", resourceContinuation);
        try { report = MachineSnapshots.enrich(player, r.snapshot, evidence).report(); }
        catch (IllegalArgumentException expired) {
            fail(expired.getMessage(), FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        finished = true;
        r.observed();
        return TaskState.SUCCESS;
    }

    @Override protected void cleanup() {
        if (pending != null) { ServerAssistClient.cancel(pending.id()); pending = null; }
        super.cleanup();
    }
    @Override protected Map<String, Object> resultData() { return Map.of("machine", report == null ? r.snapshot.report() : report); }
    @Override protected String successMessage() { return "Machine structure and available native pages observed; missing state and production uncertainty remain explicit."; }
}
