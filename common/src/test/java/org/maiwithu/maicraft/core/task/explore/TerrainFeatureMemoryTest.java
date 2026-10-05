package org.maiwithu.maicraft.core.task.explore;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.maiwithu.maicraft.intent.persistence.ExplorationMemoryStore;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;

/** 远望地形要素与群系共用一条记账管道：同格合并、跨格区分、落盘往返后证据完整、重复保存幂等。 */
public final class TerrainFeatureMemoryTest {
    public static void main(String[] args) throws Exception {
        var first = feature(64, 70, 0, 100);
        var again = feature(65, 72, 30, 200);
        check(first.id().equals(again.id()), "same 64x64 cell merges repeated sightings into one finding");
        check(!first.id().equals(feature(128, 70, 0, 100).id()), "a neighbouring cell is a separate finding");
        check(!first.visited(), "a distant sighting never claims a visit");

        var saved = new ArrayList<ExplorationFinding>();
        var journal = new ExplorationJournal(saved::addAll, Runnable::run, ignored -> {});
        journal.observe(first);
        journal.observe(again);
        check(journal.pendingCount() == 1, "journal keeps one pending record per merged cell");
        check(journal.pendingFindings().get(0).lastSeen() == 100,
                "a repeated distant sighting is dropped instead of rewritten");

        var root = Files.createTempDirectory("maicraft-terrain-feature-");
        var identity = new StateIdentity("c".repeat(64), root);
        var store = new ExplorationMemoryStore(identity);
        store.save("trip-lava", List.of(first));
        store.save("trip-lava", List.of(again));
        var restored = new ExplorationMemoryStore(identity);
        var found = restored.find(first.id());
        check(found != null && "terrain_feature".equals(found.kind()) && "lava_pool".equals(found.targetId())
                        && found.x() == again.x() && found.y() == again.y() && found.z() == again.z(),
                "round-trip keeps kind, targetId and the latest sighting coordinates");
        check(found.firstSeen() == 100 && found.lastSeen() == 200,
                "store upsert preserves the full observation interval");
        check(found.evidenceJson().equals(again.evidenceJson()), "evidence survives storage verbatim");
        check(restored.query("lava_pool", 0, 5).total() == 1, "targetId query finds the lava pool");
        check(restored.query("surface lava pool", 0, 5).total() == 1, "name query finds the lava pool");
        store.save("trip-lava", List.of(feature(64, 70, 0, 300)));
        check(restored.query("lava_pool", 0, 5).total() == 1, "re-saving the same cell stays idempotent");
        System.out.println("TerrainFeatureMemoryTest: passed");
    }
    private static ExplorationFinding feature(int x, int y, int z, long time) {
        return ExplorationFinding.observed("terrain_feature", "lava_pool", "surface lava pool",
                "minecraft:overworld", x, y, z, false, time,
                "{\"surface_y\":70,\"depth\":3,\"authority\":\"visible_loaded_surface_sample\"}");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
