// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.integration.machine.MachineSnapshots;
import org.maiwithu.maicraft.core.integration.machine.discovery.LoadedMachineDiscovery;
import org.maiwithu.maicraft.core.integration.machine.discovery.MachineDiscoveryCandidate;
import org.maiwithu.maicraft.core.integration.machine.discovery.MachineDiscoveryScanner;
import org.maiwithu.maicraft.core.integration.machine.runtime.ProductionRunPlan;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;
import static org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalogModels.*;

/** Client lifecycle and presentation bridge; the catalog and discovery never acquire the player's body. */
public final class ClientMachineCatalog {
    private static final LoadedMachineDiscovery discovery = new LoadedMachineDiscovery();
    private static MachineCatalog catalog;
    private static Object level;
    private static UUID playerId;
    private static String issue = "";
    private static final MachineDiscoveryScanner.Sink sink = new MachineDiscoveryScanner.Sink() {
        public void observed(MachineDiscoveryCandidate value) {
            if (catalog == null || !catalog.ready()) return;
            try {
                catalog.observe(new DeviceObservation(value.dimension(), position(value.position()), value.blockId(), value.blockEntityType(), null,
                        value.possibleRoles().stream().map(role -> new RoleEvidence(role, EvidenceStatus.INFERRED, value.roleBasis())).toList(),
                        EvidenceStatus.NATIVE_OBSERVED, value.source(), value.observedTick(), System.currentTimeMillis()));
            } catch (RuntimeException unavailable) { reportIssue(unavailable); }
        }
        public void removed(String dimension, BlockPos position, String block, long tick) {
            if (catalog != null && catalog.ready()) catalog.markAbsent(dimension, position(position), tick, System.currentTimeMillis());
        }
    };
    private ClientMachineCatalog() {}

    public static void tick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            if (level != null) { discovery.clear(sink); if (catalog != null) catalog.unbind(); level = null; playerId = null; }
            return;
        }
        if (level != minecraft.level || !minecraft.player.getUUID().equals(playerId)) {
            var identity = StateIdentity.resolve(minecraft);
            if (identity.isEmpty()) return;
            if (catalog == null) catalog = new MachineCatalog(identity.get().directory().resolveSibling("machines"));
            level = minecraft.level; playerId = minecraft.player.getUUID(); issue = "";
            catalog.bind(new Identity(identity.get().key(), playerId.toString()), UUID.randomUUID().toString());
            discovery.clear(sink);
        }
        catalog.tick(System.currentTimeMillis());
        if (catalog.ready()) discovery.tick(minecraft.player, sink);
    }

    public static boolean ready(LocalPlayer player) {
        return player != null && player.level() == level && player.getUUID().equals(playerId) && catalog != null && catalog.ready();
    }
    public static void inspected(LocalPlayer player, String label, BlockPos center, int radius) {
        if (!ready(player)) return;
        try {
            var state = player.level().getBlockState(center); var entity = player.level().getBlockEntity(center);
            if (!state.isAir()) catalog.observe(new DeviceObservation(player.level().dimension().location().toString(), position(center),
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                    entity == null ? null : net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString(),
                    label, List.of(), EvidenceStatus.NATIVE_OBSERVED, "explicit_machine_inspection", player.level().getGameTime(), System.currentTimeMillis()));
            if (discovery.status().region() == null || !discovery.status().region().status().equals("scanning")) discovery.requestRegion(center, radius);
        } catch (RuntimeException unavailable) { reportIssue(unavailable); }
    }
    public static String registerPlan(LocalPlayer player, String label, ProductionRunPlan plan) {
        if (!ready(player)) return null;
        String actual = label == null || label.isBlank() ? catalog.lines().stream().filter(line -> line.dimension().equals(plan.dimension())
                && line.anchor().equals(position(plan.anchor()))).map(Line::label).findFirst().orElse("生产线") : label;
        try { return catalog.registerLine(actual, plan.dimension(), position(plan.anchor()), plan.authoredJson(), System.currentTimeMillis()); }
        catch (RuntimeException unavailable) { reportIssue(unavailable); return null; }
    }
    public static void commissioned(LocalPlayer player, String label, ProductionRunPlan plan, java.util.Map<String, Object> observed) {
        String id = registerPlan(player, label, plan); if (id == null) return;
        try {
            var production = (java.util.Map<?, ?>) observed.get("production"); var run = (java.util.Map<?, ?>) production.get("verified_run");
            var flow = (java.util.Map<?, ?>) observed.get("flow");
            if (!Boolean.TRUE.equals(production.get("machine_production_verified")) || !Boolean.TRUE.equals(flow.get("delivery_verified"))) return;
            catalog.recordCommission(id, new CommissionEvidence("native_recipe_output_and_endpoint_delivery", production.get("resource_key").toString(),
                    number(run.get("from_tick")), number(run.get("through_tick")), number(run.get("events")), number(run.get("output")),
                    number(flow.get("produced_and_delivered")), System.currentTimeMillis()));
        } catch (RuntimeException unavailable) { reportIssue(unavailable); }
    }
    public static JsonObject view(LocalPlayer player, String focus) {
        JsonObject result = MachineSnapshots.summaries(player); JsonArray devices = new JsonArray(), lines = new JsonArray();
        result.add("remembered_devices", devices); result.add("production_lines", lines);
        result.addProperty("catalog_status", catalog == null ? "unbound" : catalog.status().state().name().toLowerCase(java.util.Locale.ROOT));
        result.addProperty("automatic_discovery", "loaded_nearby_chunks_only"); result.addProperty("automatic_factory_inference", false);
        if (!issue.isEmpty()) result.addProperty("catalog_issue", issue);
        if (!ready(player)) return result;
        long now = player.level().getGameTime();
        if (focus == null || focus.isBlank()) {
            catalog.nearby(player.level().dimension().location().toString(), position(player.blockPosition()), 128, 32, now).forEach(device -> devices.add(device.json(false)));
            catalog.lines().stream().limit(16).forEach(line -> {
                var summary = catalog.lineView(line.id(),false);
                summary.addProperty("node_count",summary.getAsJsonArray("nodes").size()); summary.remove("nodes");
                summary.addProperty("link_count",summary.getAsJsonArray("links").size()); summary.remove("links"); lines.add(summary);
            });
        } else {
            catalog.device(focus, now).ifPresent(device -> devices.add(device.json(false)));
            catalog.findLabel(focus, now).stream().limit(32).forEach(device -> devices.add(device.json(false)));
            catalog.lines().stream().filter(line -> line.id().equals(focus) || line.label().equalsIgnoreCase(focus)).limit(16)
                    .forEach(line -> lines.add(catalog.lineView(line.id(), false)));
            JsonArray nodes = new JsonArray();
            for (var line : catalog.lines()) for (String prefix : List.of(line.id()+"/",line.label()+"/"))
                if (focus.startsWith(prefix)) catalog.node(line.id(),focus.substring(prefix.length()),now).ifPresent(node -> nodes.add(node.json(false)));
            result.add("matching_nodes",nodes);
        }
        result.addProperty("guidance", "Remembered descriptions are not current permission. Inspect a label before changing it; use watch_production for passive future-output monitoring.");
        return result;
    }
    public static Goal.WorldPosition resolveLabel(LocalPlayer player, String label) {
        if (!ready(player) || label == null) return null;
        var identifiedLine = catalog.line(label);
        if (identifiedLine.isPresent()) return world(identifiedLine.get().dimension(),identifiedLine.get().anchor());
        var identifiedDevice = catalog.device(label,player.level().getGameTime());
        if (identifiedDevice.isPresent() && identifiedDevice.get().currentState() != CurrentState.CURRENT_ABSENT)
            return world(identifiedDevice.get().device().dimension(),identifiedDevice.get().device().position());
        var named = catalog.linesByLabel(label);
        if (named.size() == 1) return world(named.getFirst().dimension(), named.getFirst().anchor());
        var device = catalog.findLabel(label, player.level().getGameTime());
        if (device.size() == 1 && device.getFirst().currentState() != CurrentState.CURRENT_ABSENT)
            return world(device.getFirst().device().dimension(), device.getFirst().device().position());
        var matches = new java.util.LinkedHashSet<Goal.WorldPosition>();
        for (var line : catalog.lines()) for (String prefix : List.of(line.id()+"/",line.label()+"/"))
            if (label.startsWith(prefix)) catalog.node(line.id(),label.substring(prefix.length()),player.level().getGameTime())
                    .ifPresent(node -> matches.add(world(line.dimension(),node.position())));
        return matches.size() == 1 ? matches.iterator().next() : null;
    }
    public static void shutdown() {
        if (catalog != null && catalog.ready()) {
            try { catalog.saveAsync().get(2, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (Exception unavailable) { reportIssue(unavailable); }
        }
    }
    private static Position position(BlockPos p) { return new Position(p.getX(), p.getY(), p.getZ()); }
    private static Goal.WorldPosition world(String dimension, Position p) { return new Goal.WorldPosition(p.x(), p.y(), p.z(), dimension); }
    private static long number(Object value) { return ((Number) value).longValue(); }
    private static void reportIssue(Exception failure) {
        String next = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (next.equals(issue)) return; issue = next;
        JsonObject details = new JsonObject(); details.addProperty("reason", next);
        IntentRuntime.get().gameEvent("machine_catalog_attention", "机器档案暂时无法更新，现有记录会保留。", details);
    }
}
