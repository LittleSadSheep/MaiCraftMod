// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

/** 每次请求读取一页有界原生观察，不移动玩家、打开菜单或阻塞游戏线程。 */
final class ServerMachineObservationTask extends AbstractCompanionTask<ServerMachineObservationTaskRecord> {
    private record Target(int componentIndex, BlockPos position) {}
    private final MachineObservationPages pages = new MachineObservationPages();
    private final List<Target> targets = new ArrayList<>();
    private Level world;
    private JsonObject report;
    private ClientRequestReceipt pending;
    private int targetIndex;
    private int resourceOffset;
    private int continuation = -1;
    private int resourceContinuation;
    private long requestTick;
    private boolean finished;
    private final Set<BlockPos> observedComponents = new HashSet<>();

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
        Map<BlockPos, Integer> candidates = new LinkedHashMap<>();
        Map<BlockPos, Integer> indices = new LinkedHashMap<>();
        if (rows != null) for (int index = 0; index < rows.size(); index++) indices.put(at(rows.get(index).getAsJsonArray()), index);
        boolean registered = report.has("native_component_offsets");
        // 已登记机器直接遍历整份目标足迹；周围房屋和结构展示截断都不应抢占机器部件的观察额度。
        if (registered) for (var raw : report.getAsJsonArray("native_component_offsets")) {
            BlockPos position = at(raw.getAsJsonArray());
            candidates.put(position, indices.getOrDefault(position, -1));
        } else indices.forEach((position, index) -> { if (index >= r.componentOffset) candidates.put(position, index); });
        for (var candidate : candidates.entrySet()) {
            BlockPos position = candidate.getKey(); int index = candidate.getValue();
            if (!world.isLoaded(position)) { pages.unavailable(offset(position), "component_chunk_unloaded"); continue; }
            if (world.getBlockEntity(position) == null && !declared.contains(index)) continue;
            if (player.distanceToSqr(position.getCenter()) > 16 * 16) {
                pages.unavailable(offset(position), "component_outside_observation_range");
                continue;
            }
            targets.add(new Target(index, position.immutable()));
        }
        if (targets.isEmpty() && r.componentOffset == 0 && world.isLoaded(r.snapshot.center())
                && player.distanceToSqr(r.snapshot.center().getCenter()) <= 16 * 16)
            // 空场地没有第零个结构方块；仍可观察标记中心，但不能借用不存在的索引给库存归属。
            targets.add(new Target(-1, r.snapshot.center()));
        if (!registered && !report.get("structure_complete").getAsBoolean()) pages.incomplete.add("client_structure_incomplete");
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
            skip("component_left_observation_range");
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
        body.addProperty("resource_limit", 128);
        body.addProperty("occupied_items_only", true);
        pending = ServerAssistClient.submit("machine.snapshot", body, false);
        requestTick = world.getGameTime();
        return TaskState.RUNNING;
    }

    private TaskState poll() {
        var receipt = pending.snapshot();
        if (!receipt.settled() && world.getGameTime() - requestTick <= 120) return TaskState.RUNNING;
        if (!receipt.settled() || receipt.retired() || receipt.status() != ClientRequestReceipt.Status.SUCCEEDED
                || receipt.backend() != ClientRequestReceipt.Backend.SERVER) {
            ServerAssistClient.cancel(pending.id());
            pending = null;
            skip("server_observation_" + receipt.code());
            return TaskState.RUNNING;
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
        // 坐标核对完成后才给原生页绑定结构索引，防止别处的库存被归到当前目标机器。
        pages.append(page, receipt.requestId().toString(), target.componentIndex(), offset(target.position()));
        if (observations != null && !observations.isEmpty()) observedComponents.add(target.position());
        pending = null;
        // 每读到新页就续期，由 Mod 连续读取剩余材料和部件；模型只接收完成后的整机事实。
        r.extendDeadlineTo(world.getGameTime() + 1200);
        int next = page.has("next_resource_offset") ? page.get("next_resource_offset").getAsInt() : 0;
        if (page.has("truncated") && page.get("truncated").getAsBoolean() && next > resourceOffset) resourceOffset = next;
        else {
            if (page.has("truncated") && page.get("truncated").getAsBoolean())
                pages.unavailable(offset(target.position()), "resource_pagination_did_not_advance");
            targetIndex++;
            resourceOffset = 0;
        }
        return TaskState.RUNNING;
    }

    private BlockPos at(JsonArray row) {
        return r.snapshot.center().offset(row.get(0).getAsInt(), row.get(1).getAsInt(), row.get(2).getAsInt());
    }

    private JsonArray offset(BlockPos position) {
        BlockPos relative = position.subtract(r.snapshot.center()); JsonArray result = new JsonArray();
        result.add(relative.getX()); result.add(relative.getY()); result.add(relative.getZ()); return result;
    }

    private void skip(String reason) {
        // 单个部件真的读不到时记录其位置并继续其余部件，不能把后面所有设备一并变成未知。
        pages.unavailable(offset(targets.get(targetIndex).position()), reason); targetIndex++; resourceOffset = 0;
    }

    private TaskState finish() {
        if (targetIndex < targets.size()) {
            pages.incomplete.add("components_not_observed");
            if (continuation < 0) continuation = targets.get(targetIndex).componentIndex();
            resourceContinuation = resourceOffset;
            for (int index = targetIndex; index < targets.size(); index++)
                pages.unavailable(offset(targets.get(index).position()), "component_not_observed");
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
