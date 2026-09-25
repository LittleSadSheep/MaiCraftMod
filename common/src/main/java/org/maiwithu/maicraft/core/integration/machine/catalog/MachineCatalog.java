// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.catalog;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest;
import org.maiwithu.maicraft.core.integration.machine.utility.MachineUtilityInputs;
import static org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalogModels.*;

/** 由所有者线程管理目录记忆，并在后台持久化；此服务绝不读取或修改 Minecraft 世界。 */
public final class MachineCatalog {
    public static final long CURRENT_OBSERVATION_TICKS = 1200;
    public enum State { UNBOUND, LOADING, READY, FAILED }
    public record Binding(String identityKey, String sessionKey, long generation) {}
    public record Status(State state, int devices, int lines, boolean dirty, boolean savePending, String error) {}
    private final Thread owner = Thread.currentThread();
    private final MachineCatalogStore store;
    private final Map<String, Device> devices = new LinkedHashMap<>();
    private final Map<String, Line> lines = new LinkedHashMap<>();
    private final Map<String, UtilityInstallation> installations = new LinkedHashMap<>();
    private final Map<String, MachineBlueprint> blueprints = new LinkedHashMap<>();
    private final Map<String, Long> currentTicks = new LinkedHashMap<>();
    private final ConcurrentLinkedQueue<Runnable> completed = new ConcurrentLinkedQueue<>();
    private Binding binding;
    private State state = State.UNBOUND;
    private long generation, saveRevision, nextSaveMillis;
    private boolean dirty;
    private String error = "";
    private CompletableFuture<Void> lastSave = CompletableFuture.completedFuture(null);

    public MachineCatalog(Path directory) {
        this(directory, Executors.newSingleThreadExecutor(command -> {
            Thread thread = new Thread(command, "maicraft-machine-catalog-io"); thread.setDaemon(true); return thread;
        }));
    }
    /** 注入执行器便于确定性 I/O 测试；正式运行时应使用后台执行器。 */
    public MachineCatalog(Path directory, Executor executor) { store = new MachineCatalogStore(directory, executor); }

    public Binding bind(Identity identity, String sessionKey) {
        requireOwner(); poll(); sessionKey = CatalogLimits.text(sessionKey, 256, "session identity");
        String key = identity.key();
        if (binding != null && binding.identityKey().equals(key) && binding.sessionKey().equals(sessionKey)) return binding;
        boolean sameReadyIdentity = binding != null && binding.identityKey().equals(key) && state == State.READY;
        if (state == State.READY && dirty) saveAsync();
        binding = new Binding(key, sessionKey, ++generation); currentTicks.clear(); error = "";
        if (sameReadyIdentity) { observeSave(lastSave, generation, saveRevision); return binding; }
        // 同一进程重新连接时保留尚未保存的历史，但绝不保留当前观察状态。
        devices.clear(); lines.clear(); installations.clear(); blueprints.clear(); dirty = false; state = State.LOADING; lastSave = CompletableFuture.completedFuture(null);
        long requestedGeneration = generation;
        try {
            store.load(key).whenComplete((loaded, failure) -> completed.add(() -> {
                if (generation != requestedGeneration) return;
                if (failure != null) { state = State.FAILED; error = "catalog_load_failed"; return; }
                loaded.snapshot().devices().forEach(device -> devices.put(device.id(), device)); loaded.snapshot().lines().forEach(line -> lines.put(line.id(), line));
                loaded.snapshot().installations().forEach(value -> installations.put(value.id(),value));
                loaded.snapshot().blueprints().forEach(value -> blueprints.put(value.id(),value));
                dirty = loaded.needsSave(); state = State.READY;
            }));
        } catch (RuntimeException unavailable) { state = State.FAILED; error = "catalog_load_failed"; }
        return binding;
    }
    public void unbind() {
        requireOwner(); poll(); if (state == State.READY && dirty) saveAsync();
        generation++; binding = null; devices.clear(); lines.clear(); installations.clear(); blueprints.clear(); currentTicks.clear(); state = State.UNBOUND; dirty = false; error = "";
    }
    public void poll() { requireOwner(); for (int i = 0; i < 64; i++) { Runnable next = completed.poll(); if (next == null) break; next.run(); } }
    public boolean ready() { requireOwner(); poll(); return state == State.READY; }
    public Status status() { requireOwner(); poll(); return new Status(state, devices.size(), lines.size(), dirty, !lastSave.isDone(), error); }
    public Optional<Binding> binding() { requireOwner(); return Optional.ofNullable(binding); }

    public String observe(DeviceObservation observation) { return observe(binding, observation); }
    /** 异步调用方必须保留观察候选时使用的绑定关系。 */
    public String observe(Binding expected, DeviceObservation observation) {
        requireBinding(expected);
        String id = deviceId(binding.identityKey(), observation.dimension(), observation.position());
        Device previous = devices.get(id);
        if (previous == null && devices.size() >= CatalogLimits.DEVICES) throw new IllegalStateException("catalog_device_capacity");
        if (currentTicks.getOrDefault(id, -1L) > observation.gameTick()) return id;
        boolean custom = !observation.label().isEmpty() || previous != null && previous.customLabel();
        String label = !observation.label().isEmpty() ? observation.label() : previous != null && previous.customLabel() ? previous.label() : defaultLabel(observation.blockId());
        Device device = new Device(id, label, custom, observation.dimension(), observation.position(), observation.blockId(), observation.blockEntityType(),
                observation.roles(), observation.evidenceStatus(), observation.provenance(), true,
                previous == null ? observation.observedAtMillis() : previous.firstObservedAtMillis(), observation.observedAtMillis(), observation.gameTick());
        devices.put(id, device); currentTicks.put(id, observation.gameTick()); dirty = true; return id;
    }
    /** 这里只记录已加载世界中明确缺失或被替换的情况；目标未加载时必须保留为未知。 */
    public boolean markAbsent(String dimension, Position position, long gameTick, long observedAtMillis) {
        return markAbsent(binding, dimension, position, gameTick, observedAtMillis);
    }
    public boolean markAbsent(Binding expected, String dimension, Position position, long gameTick, long observedAtMillis) {
        requireBinding(expected); CatalogLimits.registry(dimension, "dimension"); CatalogLimits.nonnegative(gameTick, "game tick");
        CatalogLimits.nonnegative(observedAtMillis, "observation time");
        String id = deviceId(binding.identityKey(), dimension, position); Device previous = devices.get(id);
        if (previous == null || currentTicks.getOrDefault(id, -1L) > gameTick) return false;
        devices.put(id, new Device(id, previous.label(), previous.customLabel(), dimension, position, previous.blockId(), previous.blockEntityType(),
                previous.roles(), EvidenceStatus.NATIVE_OBSERVED, "loaded_world_absence", false, previous.firstObservedAtMillis(), observedAtMillis, gameTick));
        currentTicks.put(id, gameTick); dirty = true; return true;
    }
    public String registerLine(String label, String dimension, Position anchor, JsonObject manifest, long observedAtMillis) {
        requireReady(); label = CatalogLimits.text(label, 160, "line label"); dimension = CatalogLimits.registry(dimension, "dimension");
        String id = lineId(binding.identityKey(), dimension, anchor);
        if (!lines.containsKey(id) && lines.size() >= CatalogLimits.LINES) throw new IllegalStateException("catalog_line_capacity");
        String encoded = CatalogLimits.manifest(manifest); Line previous = lines.get(id);
        Line line = new Line(id, label, dimension, anchor, encoded, CatalogLimits.hash(encoded), observedAtMillis, previous == null ? null : previous.commission());
        lines.put(id, line); dirty = true; return id;
    }
    /** 记录调用方确认过的有限历史时段，绝不作为当前仍在生产的保证。 */
    public void recordCommission(String lineId, CommissionEvidence evidence) {
        requireReady(); Line line = lines.get(lineId); if (line == null) throw new IllegalArgumentException("catalog_line_missing");
        lines.put(lineId, new Line(line.id(), line.label(), line.dimension(), line.anchor(), line.manifestJson(), line.manifestFingerprint(),
                line.registeredAtMillis(), new Commission(evidence, line.manifestFingerprint()))); dirty = true;
    }
    public Optional<DeviceView> device(String id, long nowGameTick) { requireReady(); return Optional.ofNullable(devices.get(id)).map(value -> view(value, nowGameTick)); }
    public List<DeviceView> findLabel(String label, long nowGameTick) {
        return findLabel(label, 64, nowGameTick);
    }
    public List<DeviceView> findLabel(String label, int limit, long nowGameTick) {
        requireReady(); String key = CatalogLimits.labelKey(label);
        if (limit < 1 || limit > 128) throw new IllegalArgumentException("catalog_query_budget");
        return devices.values().stream().filter(device -> CatalogLimits.labelKey(device.label()).equals(key)).limit(limit).map(device -> view(device, nowGameTick)).toList();
    }
    public List<DeviceView> nearby(String dimension, Position center, double radius, int limit, long nowGameTick) {
        requireReady(); CatalogLimits.registry(dimension, "dimension");
        if (!Double.isFinite(radius) || radius < 0 || radius > 4096 || limit < 1 || limit > 128) throw new IllegalArgumentException("catalog_query_budget");
        return devices.values().stream().filter(device -> device.dimension().equals(dimension) && device.position().distanceSquared(center) <= radius * radius)
                .sorted(Comparator.comparingDouble((Device device) -> device.position().distanceSquared(center)).thenComparing(Device::id))
                .limit(limit).map(device -> view(device, nowGameTick)).toList();
    }
    public Optional<Line> line(String id) { requireReady(); return Optional.ofNullable(lines.get(id)); }
    public String registerInstallation(String label, String dimension, Position anchor,
            List<MachineUtilityInputs.Input> inputs, long now) {
        requireReady(); dimension = CatalogLimits.registry(dimension,"dimension"); label = CatalogLimits.text(label,160,"installation label");
        String id = UtilityInstallation.locationId(binding.identityKey(),dimension,anchor);
        if (!installations.containsKey(id) && installations.size() >= CatalogLimits.LINES) throw new IllegalStateException("catalog_installation_capacity");
        String encoded = UtilityInstallation.encode(inputs);
        installations.put(id,new UtilityInstallation(id,label,dimension,anchor,encoded,CatalogLimits.hash(encoded),now,0)); dirty = true; return id;
    }
    public Optional<UtilityInstallation> installation(String dimension, Position anchor) {
        requireReady(); dimension = CatalogLimits.registry(dimension,"dimension");
        return Optional.ofNullable(installations.get(UtilityInstallation.locationId(binding.identityKey(),dimension,anchor)));
    }
    public List<UtilityInstallation> installations() { requireReady(); return List.copyOf(installations.values()); }
    public void recordInstallationBuilt(String dimension, Position anchor,
            List<MachineUtilityInputs.Input> inputs, long now) {
        requireReady(); dimension = CatalogLimits.registry(dimension,"dimension");
        var value = installation(dimension,anchor).orElseThrow(() -> new IllegalArgumentException("catalog_installation_missing"));
        if (!value.inputsJson().equals(UtilityInstallation.encode(inputs))) throw new IllegalArgumentException("catalog_installation_changed");
        installations.put(value.id(),new UtilityInstallation(value.id(),value.label(),dimension,anchor,value.inputsJson(),value.inputsFingerprint(),value.registeredAtMillis(),now)); dirty = true;
    }
    public List<Line> lines() { requireReady(); return List.copyOf(lines.values()); }
    // 所有机器都保存原蓝图，普通刷石机也不需要声明外部接口或生产清单才能留档。
    public String registerBlueprint(String label, String dimension, Position anchor, JsonObject blueprint, long now) {
        requireReady(); dimension = CatalogLimits.registry(dimension, "machine dimension");
        String id = MachineBlueprint.locationId(binding.identityKey(), dimension, anchor);
        if (!blueprints.containsKey(id) && blueprints.size() >= CatalogLimits.LINES) throw new IllegalStateException("catalog_blueprint_capacity");
        String encoded = blueprint.toString(), fingerprint = CatalogLimits.hash(encoded);
        var previous = blueprints.get(id); boolean same = previous != null && previous.fingerprint().equals(fingerprint);
        blueprints.put(id, new MachineBlueprint(id,label,dimension,anchor,encoded,fingerprint,
                same ? previous.lastBuildState() : "planned", same ? previous.registeredAtMillis() : now, same ? previous.builtAtMillis() : 0));
        dirty = true; return id;
    }
    public Optional<MachineBlueprint> blueprint(String id) { requireReady(); return Optional.ofNullable(blueprints.get(id)); }
    public Optional<MachineBlueprint> blueprintAt(String dimension, Position anchor) {
        requireReady(); return blueprint(MachineBlueprint.locationId(binding.identityKey(),dimension,anchor));
    }
    public List<MachineBlueprint> blueprints() { requireReady(); return List.copyOf(blueprints.values()); }
    // 被替换的旧施工任务结束时不能覆盖同一地点的新蓝图记录；仅更新它实际使用过的版本。
    public void recordBlueprintState(String id, String fingerprint, String state, long now) {
        requireReady(); var value = blueprints.get(id);
        if (value == null || !value.fingerprint().equals(fingerprint)) return;
        blueprints.put(id, new MachineBlueprint(id,value.label(),value.dimension(),value.anchor(),value.blueprintJson(),fingerprint,
                state,value.registeredAtMillis(),state.equals("success") ? now : value.builtAtMillis())); dirty = true;
    }
    public List<Line> linesByLabel(String label) {
        requireReady(); String key = CatalogLimits.labelKey(label); return lines.values().stream().filter(line -> CatalogLimits.labelKey(line.label()).equals(key)).toList();
    }
    public JsonObject lineView(String id, boolean includeLocation) { return CatalogViews.line(line(id).orElseThrow(() -> new IllegalArgumentException("catalog_line_missing")), includeLocation); }
    public Optional<NodeView> node(String lineId, String nodeId, long nowGameTick) {
        requireReady(); Line line = lines.get(lineId); if (line == null) return Optional.empty();
        ProductionManifest manifest = ProductionManifest.parse(line.manifest());
        return manifest.nodes().stream().filter(node -> node.id().equals(nodeId)).findFirst().map(node -> {
            Position position = line.anchor().plus(node.offset().x(), node.offset().y(), node.offset().z());
            List<PortView> ports = manifest.ports().stream().filter(port -> port.node().equals(nodeId)).map(port -> new PortView(port.id(), port.medium(),
                    port.direction(), port.face(), line.anchor().plus(port.offset().x(), port.offset().y(), port.offset().z()))).toList();
            DeviceView device = device(deviceId(binding.identityKey(), line.dimension(), position), nowGameTick).orElse(null);
            return new NodeView(line.id(), node.id(), node.kind(), node.recipeId(), position, ports, device);
        });
    }
    public void tick(long nowMillis) {
        requireOwner(); poll();
        if (state == State.READY && dirty && nowMillis >= nextSaveMillis) { nextSaveMillis = nowMillis + 5000; saveAsync(); }
    }
    public CompletableFuture<Void> saveAsync() {
        requireReady(); long revision = ++saveRevision, expectedGeneration = generation;
        lastSave = store.save(new CatalogCodec.Snapshot(binding.identityKey(), List.copyOf(devices.values()), List.copyOf(lines.values()),List.copyOf(installations.values()),List.copyOf(blueprints.values())));
        dirty = false; CompletableFuture<Void> requested = lastSave;
        observeSave(requested, expectedGeneration, revision);
        return requested;
    }
    private void observeSave(CompletableFuture<Void> requested, long expectedGeneration, long revision) {
        requested.whenComplete((unused, failure) -> completed.add(() -> {
            if (generation != expectedGeneration || saveRevision != revision) return;
            if (failure == null) error = ""; else { dirty = true; error = "catalog_save_failed"; }
        }));
    }
    private static String defaultLabel(String blockId) { return blockId.length() <= 160 ? blockId : blockId.substring(0, 157) + "..."; }
    private DeviceView view(Device device, long now) {
        Long seen = currentTicks.get(device.id());
        CurrentState current = seen == null ? CurrentState.HISTORICAL : now < seen || now - seen > CURRENT_OBSERVATION_TICKS
                ? CurrentState.STALE : device.lastObservedPresent() ? CurrentState.CURRENT_PRESENT : CurrentState.CURRENT_ABSENT;
        return new DeviceView(device, current);
    }
    private void requireBinding(Binding expected) { requireReady(); if (expected == null || !expected.equals(binding)) throw new IllegalStateException("catalog_binding_changed"); }
    private void requireReady() { requireOwner(); poll(); if (state != State.READY || binding == null) throw new IllegalStateException("catalog_not_ready: " + state); }
    private void requireOwner() { if (Thread.currentThread() != owner) throw new IllegalStateException("catalog_owner_thread_required"); }
}
