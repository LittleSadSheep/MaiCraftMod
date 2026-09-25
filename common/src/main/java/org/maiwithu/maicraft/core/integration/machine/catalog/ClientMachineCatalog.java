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
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.registries.BuiltInRegistries;
import org.maiwithu.maicraft.core.integration.machine.MachineConstructionPlan;
import org.maiwithu.maicraft.core.integration.machine.MachineBlueprintDocument;
import static org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalogModels.*;

/** 客户端生命周期与展示桥接；目录和发现流程绝不会接管玩家身体。 */
public final class ClientMachineCatalog {
    private static final LoadedMachineDiscovery discovery = new LoadedMachineDiscovery();
    private static MachineCatalog catalog;
    private static Object level;
    private static UUID playerId;
    private static String issue = "";
    private record PendingBuilt(Object world, UUID player, String dimension, String label, MachineConstructionPlan plan) {}
    private record CachedBlueprint(String fingerprint, MachineConstructionPlan plan) {}
    private static final Map<String, PendingBuilt> pendingBuilt = new LinkedHashMap<>();
    private static final Map<String, CachedBlueprint> compiledBlueprints = new LinkedHashMap<>();
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
            compiledBlueprints.clear();
            pendingBuilt.values().removeIf(value -> value.world() != level || !Objects.equals(value.player(),playerId));
            catalog.bind(new Identity(identity.get().key(), playerId.toString()), UUID.randomUUID().toString());
            discovery.clear(sink);
        }
        catalog.tick(System.currentTimeMillis());
        if (catalog.ready()) {
            // 目录异步加载尚未结束时，先让建造成果正常返回；加载完成后再补存同一世界的完成记录。
            for (var pending : List.copyOf(pendingBuilt.values())) {
                if (pending.world() == level && Objects.equals(pending.player(),playerId)) saveBuilt(pending);
            }
            pendingBuilt.clear(); discovery.tick(minecraft.player, sink);
        }
    }

    public static boolean ready(LocalPlayer player) {
        return player != null && player.level() == level && player.getUUID().equals(playerId) && catalog != null && catalog.ready();
    }
    public static void inspected(LocalPlayer player, String label, BlockPos center, int radius) {
        if (!ready(player)) return;
        try {
            var state = player.level().getBlockState(center); var entity = player.level().getBlockEntity(center);
            if (!state.isAir()) catalog.observe(new DeviceObservation(player.level().dimension().location().toString(), position(center),
                    BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                    entity == null ? null : BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString(),
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
    public static boolean registerInstallation(LocalPlayer player, String label,
            MachineConstructionPlan plan) {
        if (plan.utilityInputs().isEmpty()) return true;
        if (!ready(player)) {
            if (catalog != null && catalog.status().state() == MachineCatalog.State.FAILED)
                throw new IllegalArgumentException("machine_catalog_unavailable: cannot retain external input locations");
            return false;
        }
        catalog.registerInstallation(label,player.level().dimension().location().toString(),position(plan.anchor()),plan.utilityInputs(),System.currentTimeMillis());
        return true;
    }
    public static void installationBuilt(LocalPlayer player, MachineConstructionPlan plan) {
        installationBuilt(player,plan,null);
    }
    public static JsonObject installationBuilt(LocalPlayer player, MachineConstructionPlan plan, String label) {
        var value = new PendingBuilt(player.level(),player.getUUID(),player.level().dimension().location().toString(),label,plan);
        if (ready(player)) return saveBuilt(value);
        pendingBuilt.put(value.dimension()+"@"+plan.anchor(),value);
        var result = new JsonObject(); result.addProperty("archive_status","pending_catalog_load"); return result;
    }
    private static JsonObject saveBuilt(PendingBuilt value) {
        try {
            var at = position(value.plan().anchor()); long now = System.currentTimeMillis();
            String label = value.label();
            if (label == null || label.isBlank()) label = catalog.blueprintAt(value.dimension(),at).map(MachineBlueprint::label)
                    .orElse("机器@"+at.x()+","+at.y()+","+at.z());
            var positions = value.plan().positions();
            if (positions.isEmpty()) positions = List.of(value.plan().anchor());
            var xs = positions.stream().mapToInt(BlockPos::getX).summaryStatistics();
            var ys = positions.stream().mapToInt(BlockPos::getY).summaryStatistics();
            var zs = positions.stream().mapToInt(BlockPos::getZ).summaryStatistics();
            // 保存实际编译后的整机范围，包含原生带子和生成的门上半部；读取现状时无需用旧方块数据补图。
            String id = catalog.registerBlueprint(label,value.dimension(),at,value.plan().blueprint(),now,
                    new Position(xs.getMin(),ys.getMin(),zs.getMin()),new Position(xs.getMax(),ys.getMax(),zs.getMax()));
            var blueprint = catalog.blueprint(id).orElseThrow();
            catalog.recordBlueprintState(id,blueprint.fingerprint(),"success",now);
            compiledBlueprints.put(id,new CachedBlueprint(blueprint.fingerprint(),value.plan()));
            if (!value.plan().utilityInputs().isEmpty()) {
                catalog.registerInstallation(label,value.dimension(),at,value.plan().utilityInputs(),now);
                catalog.recordInstallationBuilt(value.dimension(),at,value.plan().utilityInputs(),now);
            }
            catalog.saveAsync(); var result = catalog.blueprint(id).orElseThrow().summary();
            result.addProperty("archive_status","recorded"); return result;
        } catch (RuntimeException unavailable) {
            // 留档失败只作为记录问题公开，不能把已经搭好的机器改判成施工失败。
            reportIssue(unavailable); var result = new JsonObject(); result.addProperty("archive_status","unavailable"); result.addProperty("reason",issue); return result;
        }
    }
    public static Optional<MachineBlueprint> blueprint(LocalPlayer player, String reference, BlockPos anchor) {
        if (!ready(player)) return Optional.empty();
        if (reference != null) {
            var byId = catalog.blueprint(reference); if (byId.isPresent()) return byId;
            var named = catalog.blueprints().stream().filter(value -> value.label().equalsIgnoreCase(reference)
                    && value.dimension().equals(player.level().dimension().location().toString())).toList();
            if (named.size() == 1) return Optional.of(named.getFirst());
            if (named.size() > 1) throw new IllegalArgumentException("machine_label_ambiguous: use machine_id");
        }
        return anchor == null ? Optional.empty() : catalog.blueprintAt(player.level().dimension().location().toString(),position(anchor));
    }
    public static MachineConstructionPlan blueprintPlan(MachineBlueprint blueprint) {
        var cached = compiledBlueprints.get(blueprint.id());
        if (cached != null && cached.fingerprint().equals(blueprint.fingerprint())) return cached.plan();
        // 重连后从存档蓝图恢复比较目标，不根据现在的地图反推或覆盖用户原来的设计。
        var at = blueprint.anchor(); var plan = MachineConstructionPlan.compile(new BlockPos(at.x(),at.y(),at.z()),
                MachineBlueprintDocument.compile(blueprint.blueprint(),MachineConstructionPlan.registry()),false);
        compiledBlueprints.put(blueprint.id(),new CachedBlueprint(blueprint.fingerprint(),plan)); return plan;
    }
    public static UtilityInstallation requireInstallation(LocalPlayer player, BlockPos anchor) {
        if (!ready(player)) throw new IllegalArgumentException("machine_catalog_not_ready");
        return catalog.installation(player.level().dimension().location().toString(),position(anchor))
                .orElseThrow(() -> new IllegalArgumentException("machine_external_inputs_unknown: build or register a blueprint with external_inputs first"));
    }
    public static void commissioned(LocalPlayer player, String label, ProductionRunPlan plan, Map<String, Object> observed) {
        String id = registerPlan(player, label, plan); if (id == null) return;
        try {
            var production = (Map<?, ?>) observed.get("production"); var run = (Map<?, ?>) production.get("verified_run");
            var flow = (Map<?, ?>) observed.get("flow");
            if (!Boolean.TRUE.equals(production.get("machine_production_verified")) || !Boolean.TRUE.equals(flow.get("delivery_verified"))) return;
            catalog.recordCommission(id, new CommissionEvidence("native_recipe_output_and_endpoint_delivery", production.get("resource_key").toString(),
                    number(run.get("from_tick")), number(run.get("through_tick")), number(run.get("events")), number(run.get("output")),
                    number(flow.get("produced_and_delivered")), System.currentTimeMillis()));
        } catch (RuntimeException unavailable) { reportIssue(unavailable); }
    }
    public static JsonObject view(LocalPlayer player, String focus) {
        JsonObject result = MachineSnapshots.summaries(player); JsonArray devices = new JsonArray(), lines = new JsonArray(), installations = new JsonArray();
        result.add("remembered_devices", devices); result.add("production_lines", lines);
        result.add("utility_installations",installations);
        var built = new JsonArray(); result.add("recorded_machines",built);
        result.addProperty("catalog_status", catalog == null ? "unbound" : catalog.status().state().name().toLowerCase(Locale.ROOT));
        result.addProperty("automatic_discovery", "loaded_nearby_chunks_only"); result.addProperty("automatic_factory_inference", false);
        if (!issue.isEmpty()) result.addProperty("catalog_issue", issue);
        if (!ready(player)) return result;
        catalog.blueprints().stream().filter(value -> focus == null || focus.isBlank() || value.id().equals(focus)
                || value.label().equalsIgnoreCase(focus)).limit(32).forEach(value -> built.add(value.summary()));
        result.addProperty("catalog_save_pending",catalog.status().savePending());
        if (!catalog.status().error().isEmpty()) result.addProperty("catalog_error",catalog.status().error());
        catalog.installations().stream().filter(value -> focus == null || focus.isBlank() || value.id().equals(focus)
                || value.label().equalsIgnoreCase(focus) || value.inputs().stream().anyMatch(input -> (value.label()+"/"+input.id()).equals(focus)))
                .limit(16).forEach(value -> installations.add(value.json(false)));
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
        var utilityLocations = new LinkedHashSet<Goal.WorldPosition>();
        for (var value : catalog.blueprints()) if (value.id().equals(label) || value.label().equalsIgnoreCase(label))
            utilityLocations.add(world(value.dimension(),value.anchor()));
        for (var value : catalog.installations()) {
            if (value.id().equals(label) || value.label().equalsIgnoreCase(label)) utilityLocations.add(world(value.dimension(),value.anchor()));
            for (var input : value.inputs()) if ((value.label()+"/"+input.id()).equals(label) || (value.id()+"/"+input.id()).equals(label))
                utilityLocations.add(world(value.dimension(),value.anchor().plus(input.offset().getX(),input.offset().getY(),input.offset().getZ())));
        }
        if (utilityLocations.size() == 1) return utilityLocations.iterator().next();
        if (utilityLocations.size() > 1) return null;
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
        var matches = new LinkedHashSet<Goal.WorldPosition>();
        for (var line : catalog.lines()) for (String prefix : List.of(line.id()+"/",line.label()+"/"))
            if (label.startsWith(prefix)) catalog.node(line.id(),label.substring(prefix.length()),player.level().getGameTime())
                    .ifPresent(node -> matches.add(world(line.dimension(),node.position())));
        return matches.size() == 1 ? matches.iterator().next() : null;
    }
    public static void shutdown() {
        if (catalog != null && catalog.ready()) {
            try { catalog.saveAsync().get(2, TimeUnit.SECONDS); }
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
