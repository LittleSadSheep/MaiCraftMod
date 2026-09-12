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
import static org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalogModels.*;

/** Owner-thread catalog memory with background persistence; this service never reads or changes a Minecraft world. */
public final class MachineCatalog {
    public static final long CURRENT_OBSERVATION_TICKS = 1200;
    public enum State { UNBOUND, LOADING, READY, FAILED }
    public record Binding(String identityKey, String sessionKey, long generation) {}
    public record Status(State state, int devices, int lines, boolean dirty, boolean savePending, String error) {}
    private final Thread owner = Thread.currentThread();
    private final MachineCatalogStore store;
    private final Map<String, Device> devices = new LinkedHashMap<>();
    private final Map<String, Line> lines = new LinkedHashMap<>();
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
    /** Executor injection supports deterministic I/O tests; production should use a background executor. */
    public MachineCatalog(Path directory, Executor executor) { store = new MachineCatalogStore(directory, executor); }

    public Binding bind(Identity identity, String sessionKey) {
        requireOwner(); poll(); sessionKey = CatalogLimits.text(sessionKey, 256, "session identity");
        String key = identity.key();
        if (binding != null && binding.identityKey().equals(key) && binding.sessionKey().equals(sessionKey)) return binding;
        boolean sameReadyIdentity = binding != null && binding.identityKey().equals(key) && state == State.READY;
        if (state == State.READY && dirty) saveAsync();
        binding = new Binding(key, sessionKey, ++generation); currentTicks.clear(); error = "";
        if (sameReadyIdentity) { observeSave(lastSave, generation, saveRevision); return binding; }
        // Same-process reconnect keeps unsaved history, never current observation status.
        devices.clear(); lines.clear(); dirty = false; state = State.LOADING; lastSave = CompletableFuture.completedFuture(null);
        long requestedGeneration = generation;
        try {
            store.load(key).whenComplete((loaded, failure) -> completed.add(() -> {
                if (generation != requestedGeneration) return;
                if (failure != null) { state = State.FAILED; error = "catalog_load_failed"; return; }
                loaded.snapshot().devices().forEach(device -> devices.put(device.id(), device)); loaded.snapshot().lines().forEach(line -> lines.put(line.id(), line));
                dirty = loaded.needsSave(); state = State.READY;
            }));
        } catch (RuntimeException unavailable) { state = State.FAILED; error = "catalog_load_failed"; }
        return binding;
    }
    public void unbind() {
        requireOwner(); poll(); if (state == State.READY && dirty) saveAsync();
        generation++; binding = null; devices.clear(); lines.clear(); currentTicks.clear(); state = State.UNBOUND; dirty = false; error = "";
    }
    public void poll() { requireOwner(); for (int i = 0; i < 64; i++) { Runnable next = completed.poll(); if (next == null) break; next.run(); } }
    public boolean ready() { requireOwner(); poll(); return state == State.READY; }
    public Status status() { requireOwner(); poll(); return new Status(state, devices.size(), lines.size(), dirty, !lastSave.isDone(), error); }
    public Optional<Binding> binding() { requireOwner(); return Optional.ofNullable(binding); }

    public String observe(DeviceObservation observation) { return observe(binding, observation); }
    /** Async callers must retain the binding under which they observed the candidate. */
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
    /** Only explicit loaded-world absence/replacement belongs here; an unloaded target must remain unknown. */
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
    /** Records a caller-confirmed finite historical window, never a current production guarantee. */
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
    public List<Line> lines() { requireReady(); return List.copyOf(lines.values()); }
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
        lastSave = store.save(new CatalogCodec.Snapshot(binding.identityKey(), List.copyOf(devices.values()), List.copyOf(lines.values())));
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
