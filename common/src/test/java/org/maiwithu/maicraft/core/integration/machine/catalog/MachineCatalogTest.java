// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.catalog;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Executor;
import static org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalogModels.*;

/** Catalog memory/file contracts only: no client, world, input driver or game action is created. */
public final class MachineCatalogTest {
    private static final Identity IDENTITY = new Identity("test-world", "test-account");
    private static final Position PRESS = new Position(102, 66, 100);
    private MachineCatalogTest() {}
    public static void main(String[] args) throws Exception {
        observationAndSessionBoundaries(); layoutsAndHistoricalCommission(); persistenceAndCorruption(); budgetsAndLateLoads(); fileAndFieldBudgets(); failedSaveSurvivesRebinding();
        System.out.println("MachineCatalogTest: stable location identity, historical evidence, bounded queries and asynchronous atomic persistence passed");
    }

    private static void observationAndSessionBoundaries() throws Exception {
        var io = new ManualExecutor(); var catalog = new MachineCatalog(Files.createTempDirectory("maicraft-catalog-memory-"), io);
        var first = catalog.bind(IDENTITY, "session-1");
        check(!catalog.ready() && io.size() == 1, "Binding synchronously performed file I/O");
        rejects(() -> catalog.observe(observation(PRESS, "Press", 10)), "catalog_not_ready");
        io.runAll(); catalog.poll();
        String id = catalog.observe(first, observation(PRESS, "Press", 10));
        check(id.equals(catalog.observe(observation(PRESS, null, 11))) && catalog.status().devices() == 1, "Repeated location created duplicate devices");
        var current = catalog.device(id, 11).orElseThrow();
        check(current.currentState() == CurrentState.CURRENT_PRESENT && current.device().label().equals("Press"), "Observation lost current state or custom label");
        check(current.device().roles().getFirst().status() == EvidenceStatus.INFERRED, "Native block observation certified an inferred purpose");
        check(!current.operationAuthorized() && !current.json(false).has("position") && current.json(true).has("position"), "Public catalog view exposed coordinates or operation authority");
        check(catalog.findLabel("press", 11).size() == 1 && catalog.nearby("minecraft:the_nether", PRESS, 20, 10, 11).isEmpty(), "Label/world query boundary failed");
        check(catalog.device(id, 1212).orElseThrow().currentState() == CurrentState.STALE, "Old current-session observation stayed fresh forever");
        catalog.bind(IDENTITY, "session-2");
        check(catalog.device(id, 11).orElseThrow().currentState() == CurrentState.HISTORICAL, "Reconnect reused previous session's current evidence");
        rejects(() -> catalog.observe(first, observation(PRESS, null, 12)), "catalog_binding_changed");
        rejects(() -> catalog.markAbsent(first, "minecraft:overworld", PRESS, 12, 1000), "catalog_binding_changed");
        catalog.observe(observation(PRESS, null, 12));
        check(catalog.markAbsent("minecraft:overworld", PRESS, 13, 1013) && catalog.device(id, 13).orElseThrow().currentState() == CurrentState.CURRENT_ABSENT,
                "Explicit loaded absence was erased or treated as current presence");
        catalog.observe(observation(PRESS, null, 12));
        check(catalog.device(id, 13).orElseThrow().currentState() == CurrentState.CURRENT_ABSENT, "Older observation overwrote newer absence");
        io.runAll(); catalog.poll();
    }

    private static void layoutsAndHistoricalCommission() throws Exception {
        var io = new ManualExecutor(); var catalog = ready(Files.createTempDirectory("maicraft-catalog-line-"), io);
        catalog.observe(observation(PRESS, "Press", 10)); var manifest = manifest();
        String lineId = catalog.registerLine("Iron line", "minecraft:overworld", new Position(100, 64, 100), manifest, 1000);
        var node = catalog.node(lineId, "press", 10).orElseThrow();
        check(node.position().equals(PRESS) && node.declaredKind().equals("process") && node.ports().getFirst().position().equals(new Position(102, 64, 100)),
                "Node/port absolute locations lost their authored anchor offsets");
        check(node.device() != null && !node.json(false).has("position") && !node.operationAuthorized(), "Node lookup could not join the observed device safely");
        var proof = new CommissionEvidence("native_recipe_and_delivery_window", "items:create:iron_sheet#test", 10, 50, 4, 4, 4, 2000);
        catalog.recordCommission(lineId, proof);
        check(catalog.line(lineId).orElseThrow().commissionMatchesManifest() && !catalog.lineView(lineId, false).get("current_production_verified").getAsBoolean(),
                "Historical commissioning became a current production guarantee");
        check(!catalog.lineView(lineId, false).has("anchor") && !catalog.lineView(lineId, false).has("manifest"), "Public line view exposed absolute layout data");
        manifest.getAsJsonObject("observation").addProperty("window_ticks", 40);
        check(catalog.line(lineId).orElseThrow().manifest().getAsJsonObject("observation").get("window_ticks").getAsInt() == 20,
                "Caller mutated the registered manifest through a shared JSON reference");
        String changed = catalog.registerLine("Renamed line", "minecraft:overworld", new Position(100, 64, 100), manifest, 3000);
        check(changed.equals(lineId) && catalog.lines().size() == 1 && !catalog.line(lineId).orElseThrow().commissionMatchesManifest(),
                "Layout update duplicated its anchor or reused the old proof for new design data");
        check(catalog.lineView(lineId, false).getAsJsonObject("last_commission").get("historical_only").getAsBoolean(), "Historical commissioning was lost");
    }

    private static void persistenceAndCorruption() throws Exception {
        Path directory = Files.createTempDirectory("maicraft-catalog-file-"); var io = new ManualExecutor(); var catalog = ready(directory, io);
        String id = catalog.observe(observation(PRESS, "Saved press", 10)); var first = catalog.saveAsync();
        catalog.observe(observation(PRESS, null, 11)); var last = catalog.saveAsync();
        Path file = directory.resolve(IDENTITY.key() + ".json");
        check(!Files.exists(file) && first.isCancelled() && !last.isDone(), "Coalescing claimed an unsaved checkpoint or performed synchronous I/O");
        io.runAll(); catalog.poll(); check(last.isDone() && !last.isCompletedExceptionally(), "Atomic catalog save failed");
        byte[] original = Files.readAllBytes(file);
        check(!new String(original, java.nio.charset.StandardCharsets.UTF_8).contains("session-1"), "Session freshness token was persisted");
        var reloadIo = new ManualExecutor(); var reloaded = ready(directory, reloadIo);
        check(reloaded.device(id, 11).orElseThrow().currentState() == CurrentState.HISTORICAL
                && reloaded.device(id, 11).orElseThrow().device().lastObservedGameTick() == 11, "Restart lost history or restored current authorization");
        var broken = JsonParser.parseString(Files.readString(file)).getAsJsonObject(); broken.addProperty("identity_key", new Identity("other-world", "test-account").key());
        Files.writeString(file, broken.toString()); byte[] corrupt = Files.readAllBytes(file);
        var badIo = new ManualExecutor(); var bad = new MachineCatalog(directory, badIo); bad.bind(IDENTITY, "after-corruption"); badIo.runAll(); bad.poll();
        check(bad.status().state() == MachineCatalog.State.FAILED, "Foreign/corrupt catalog was treated as an empty database");
        rejects(bad::saveAsync, "catalog_not_ready"); check(java.util.Arrays.equals(corrupt, Files.readAllBytes(file)), "Failed load overwrote the prior file");
        try (var files = Files.list(directory)) { check(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")), "Atomic save leaked a temporary checkpoint"); }
    }

    private static void fileAndFieldBudgets() throws Exception {
        Path directory = Files.createTempDirectory("maicraft-catalog-size-"); Path file = directory.resolve(IDENTITY.key() + ".json");
        Files.write(file, new byte[CatalogLimits.FILE_BYTES + 1]);
        var io = new ManualExecutor(); var catalog = new MachineCatalog(directory, io); catalog.bind(IDENTITY, "oversized");
        check(catalog.status().state() == MachineCatalog.State.LOADING, "Oversized file was synchronously read on the owner thread");
        io.runAll(); catalog.poll();
        check(catalog.status().state() == MachineCatalog.State.FAILED && Files.size(file) == CatalogLimits.FILE_BYTES + 1L,
                "Oversized file was accepted, truncated or overwritten");
        rejects(() -> observation(PRESS, "x".repeat(161), 10), "label");
        var duplicate = new MachineCatalogModels.Device("device:test", "label", false, "minecraft:overworld", PRESS, "minecraft:stone", "",
                List.of(), EvidenceStatus.UNKNOWN, "fixture", true, 1, 1, 1);
        rejects(() -> CatalogCodec.decode(CatalogCodec.encode(new CatalogCodec.Snapshot(IDENTITY.key(), List.of(duplicate), List.of())), IDENTITY.key()), "foreign");
        var cleanIo = new ManualExecutor(); var clean = ready(Files.createTempDirectory("maicraft-catalog-anchor-"), cleanIo);
        rejects(() -> clean.registerLine("outside", "minecraft:overworld", new Position(30_000_000, 64, 100), manifest(), 1), "position");
        check(clean.lines().isEmpty(), "Invalid absolute layout partially updated the catalog");
    }

    private static void failedSaveSurvivesRebinding() throws Exception {
        Path directory = Files.createTempDirectory("maicraft-catalog-retry-"); var io = new ManualExecutor(); var catalog = ready(directory, io);
        String id = catalog.observe(observation(PRESS, "Unsaved press", 10)); io.reject = true;
        check(catalog.saveAsync().isCompletedExceptionally() && catalog.status().dirty(), "Failed save disappeared from catalog status");
        catalog.bind(new Identity("another-world", "test-account"), "other");
        io.reject = false; catalog.bind(IDENTITY, "returned"); catalog.poll();
        check(catalog.ready() && catalog.status().dirty() && catalog.device(id, 10).orElseThrow().currentState() == CurrentState.HISTORICAL,
                "Identity round trip lost the failed in-process checkpoint or restored current evidence");
        var retry = catalog.saveAsync(); io.runAll(); catalog.poll();
        check(!retry.isCompletedExceptionally() && !catalog.status().dirty() && Files.exists(directory.resolve(IDENTITY.key() + ".json")),
                "A failed checkpoint could not later be saved atomically");
    }

    private static void budgetsAndLateLoads() throws Exception {
        var io = new ManualExecutor(); var catalog = new MachineCatalog(Files.createTempDirectory("maicraft-catalog-budget-"), io);
        catalog.bind(IDENTITY, "first"); var other = new Identity("test-world", "other-account"); catalog.bind(other, "second"); io.runAll(); catalog.poll();
        check(catalog.binding().orElseThrow().identityKey().equals(other.key()) && catalog.status().devices() == 0, "Late load crossed the account/world binding");
        for (int i = 0; i < CatalogLimits.DEVICES; i++) catalog.observe(observation(new Position(i, 64, 0), null, 10));
        rejects(() -> catalog.observe(observation(new Position(CatalogLimits.DEVICES, 64, 0), null, 10)), "catalog_device_capacity");
        check(catalog.status().devices() == CatalogLimits.DEVICES && catalog.findLabel("create:mechanical_press", 10).size() == 64,
                "Capacity/query budget discarded old records or emitted an unbounded result");
        rejects(() -> catalog.nearby("minecraft:overworld", PRESS, Double.NaN, 10, 10), "catalog_query_budget");
        rejects(() -> new CommissionEvidence("native", "items:test", 0, 1, 1, 1, 2, 100), "finite observed");
        String nested = "[".repeat(33) + "0" + "]".repeat(33);
        rejects(() -> CatalogCodec.decode(nested, IDENTITY.key()), "depth");
    }

    private static MachineCatalog ready(Path directory, ManualExecutor io) { var catalog = new MachineCatalog(directory, io); catalog.bind(IDENTITY, "session-1"); io.runAll(); catalog.poll(); check(catalog.ready(), "Fixture catalog did not load"); return catalog; }
    private static DeviceObservation observation(Position position, String label, long tick) {
        return new DeviceObservation("minecraft:overworld", position, "create:mechanical_press", "create:mechanical_press", label,
                List.of(new RoleEvidence("possible_processing", EvidenceStatus.INFERRED, "registry_name_heuristic")), EvidenceStatus.NATIVE_OBSERVED, "loaded_client_world", tick, 1000 + tick);
    }
    static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        boolean reject;
        public void execute(Runnable action) { if (reject) throw new java.util.concurrent.RejectedExecutionException("fixture executor rejected"); pending.add(action); }
        int size() { return pending.size(); }
        void runAll() { int count = 0; while (!pending.isEmpty()) { if (++count > 100) throw new AssertionError("Unbounded catalog I/O scheduling"); pending.remove().run(); } }
    }
    private static void rejects(Runnable action, String text) { try { action.run(); throw new AssertionError("Expected " + text); } catch (IllegalArgumentException | IllegalStateException expected) { check(expected.getMessage().contains(text), "Unexpected failure: " + expected); } }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static com.google.gson.JsonObject manifest() {
        return JsonParser.parseString("""
                {"schema_version":1,"nodes":[{"id":"source","kind":"source","offset":[0,0,0],"material_policy":"inventory_only"},
                {"id":"press","kind":"process","offset":[2,2,0],"recipe_id":"create:pressing/iron_ingot","batches":4},{"id":"sink","kind":"sink","offset":[4,0,0]}],
                "ports":[{"id":"supply","node":"source","offset":[0,0,0],"face":"east","medium":"items","direction":"output"},
                {"id":"input","node":"press","offset":[2,0,0],"face":"west","medium":"items","direction":"input"},
                {"id":"output","node":"press","offset":[2,0,0],"face":"east","medium":"items","direction":"output"},
                {"id":"sink","node":"sink","offset":[4,0,0],"face":"west","medium":"items","direction":"input"}],
                "links":[{"id":"feed","from":"supply","to":"input","medium":"items","resource":"minecraft:iron_ingot","amount":4,"path":[[0,0,0],[1,0,0],[2,0,0]]},
                {"id":"delivery","from":"output","to":"sink","medium":"items","resource":"create:iron_sheet","amount":4,"path":[[2,0,0],[3,0,0],[4,0,0]]}],
                "configurations":[],"target":{"node":"sink","medium":"items","resource":"create:iron_sheet"},
                "observation":{"window_ticks":20,"minimum_output":4,"minimum_events":4,"max_idle_ticks":20}}
                """).getAsJsonObject();
    }
}
