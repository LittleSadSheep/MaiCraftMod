package org.maiwithu.maicraft.intent.persistence;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.maiwithu.maicraft.core.task.explore.ExplorationFinding;
import org.maiwithu.maicraft.core.task.explore.ClientExplorationMemory;
import org.maiwithu.maicraft.mcp.ExplorationMemoryView;

/** 同库跑图记忆跨实例恢复，分页不漏记录，旧地标检查点和另一个世界均不受影响。 */
public final class ExplorationMemoryStoreTest {
    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("maicraft-exploration-");
        var identity = new StateIdentity("a".repeat(64), root);
        var store = new ExplorationMemoryStore(identity);
        var database = new MemoryDatabase(identity.databaseFile());
        database.write("state", identity.key(), "{\"landmarks\":[]}", Map.of(), false);
        List<ExplorationFinding> observations = new ArrayList<>();
        for (int i = 0; i < 53; i++) observations.add(at(i * 64, false, 100));
        store.save("first-trip", observations);
        var restored = new ExplorationMemoryStore(identity);
        int count = 0, offset = 0;
        while (true) {
            var page = restored.query("testmod:forest", offset, 7);
            check(page.total() == 53 && page.entries().size() <= 7, "database pages preserve total");
            count += page.entries().size(); if (page.nextOffset() == null) break; offset = page.nextOffset();
        }
        check(count == 53, "all discoveries can be retrieved");
        var trip = restored.queryRun("first-trip", 7, 7);
        check(trip.total() == 53 && trip.entries().size() == 7 && trip.nextOffset() == 14, "stable per-trip query after restart");
        restored.save(List.of(at(1, true, 200), at(2, false, 300)));
        var merged = restored.find(at(0, false, 100).id());
        check(merged.visited() && merged.x() == 1 && merged.firstSeen() == 100 && merged.lastSeen() == 300,
                "later distant sighting keeps actual visited footing and full observation interval");
        var runPage = ExplorationMemoryView.read(identity, "run:first-trip", null, 0, 5);
        check(runPage.getAsJsonObject("next_query").get("focus").getAsString().equals("run:first-trip"), "receipt pagination stays in its trip");
        check(new ExplorationMemoryStore(new StateIdentity("b".repeat(64), root)).query(null, 0, 5).total() == 0,
                "same database keeps worlds isolated");
        check(database.read("state", identity.key(), 1024).equals("{\"landmarks\":[]}"), "landmark checkpoint preserved");
        check(restored.query("' OR 1=1 --", 0, 5).total() == 0, "query remains literal data");
        var summary = ExplorationMemoryView.read(identity, "discoveries", null, 0, 5);
        check(summary.getAsJsonArray("entries").size() == 5 && !summary.toString().contains("block_counts"), "default history is a compact page");
        var detail = ExplorationMemoryView.read(identity, "exploration:" + merged.id(), null, 0, 5);
        check(detail.has("evidence") && detail.has("observed_position"), "selected discovery retains complete evidence");
        var destination = ClientExplorationMemory.resolveLabel(identity, "exploration:" + merged.id());
        check(destination.x() == 1 && destination.dimension().equals("minecraft:overworld"), "remembered label resolves visited footing");
        check(ClientExplorationMemory.resolveLabel(new StateIdentity("b".repeat(64), root), "exploration:" + merged.id()) == null,
                "a travel label from another world is not a destination");
        List<ExplorationFinding> broken = new ArrayList<>(); broken.add(at(10000, false, 400)); broken.add(null);
        try { restored.save(broken); throw new AssertionError("invalid batch accepted"); }
        catch (NullPointerException expected) { /* 同批前面的地点也必须回滚。 */ }
        check(restored.query(null, 0, 5).total() == 53, "failed batch rolls back all inserts");
        // 跑图与其他记忆可能同时落盘；写事务须先取得锁，再合并第一次和最近一次观察。
        try (var writers = Executors.newFixedThreadPool(2)) {
            var first = writers.submit(() -> { restored.save(List.of(at(0, true, 500))); return null; });
            var second = writers.submit(() -> { new ExplorationMemoryStore(identity).save(List.of(at(0, false, 600))); return null; });
            first.get(); second.get();
        }
        var concurrent = restored.find(at(0, false, 0).id());
        check(concurrent.visited() && concurrent.firstSeen() == 100 && concurrent.lastSeen() == 600,
                "concurrent sightings cannot erase the visited state or latest evidence");
        System.out.println("ExplorationMemoryStoreTest: passed");
    }
    private static ExplorationFinding at(int x, boolean visited, long time) {
        return ExplorationFinding.observed("biome", "testmod:forest", "测试森林", "minecraft:overworld",
                x, 64, 0, visited, time, "{\"tags\":[\"testmod:wooded\"]}");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
