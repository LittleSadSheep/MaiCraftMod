// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.explore;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.intent.ExplorationIntent;
import org.maiwithu.maicraft.intent.persistence.ExplorationMemoryStore;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;

/**
 * 结构 sighting 的无头可测部分：同簇去重的纯几何判定、兴趣白名单随证据画像动态扩展，
 * 以及 kind=structure 的线索记录与 find_structure 到达记录经确定性 id 合并。
 * 扫描器全链路（TargetIndex 与真实世界）与伴随任务的接线仍需实机验证。
 */
public final class StructureSightingMemoryTest {
    public static void main(String[] args) throws Exception {
        dedup();
        interests();
        memoryMerge();
        System.out.println("StructureSightingMemoryTest: passed");
    }

    private static void dedup() {
        var recorded = List.of(new BlockPos(0, 64, 0), new BlockPos(300, 64, 300));
        check(SemanticExploreCompanionTask.isDuplicateSighting(
                        recorded, new BlockPos(10, 64, 10), 24),
                "an anchor inside the cluster radius of a recorded anchor is the same structure");
        check(!SemanticExploreCompanionTask.isDuplicateSighting(
                        recorded, new BlockPos(40, 64, 0), 24),
                "an anchor beyond the cluster radius is a new sighting");
        check(!SemanticExploreCompanionTask.isDuplicateSighting(
                        null, new BlockPos(0, 64, 0), 24),
                "no recorded anchors never duplicates");
        check(!SemanticExploreCompanionTask.isDuplicateSighting(
                        recorded, new BlockPos(10, 64, 10), 0),
                "an unusable cluster radius never duplicates");
    }

    private static void interests() {
        check(SemanticExploreTaskRecord.isLegalInterest("lava_pool"), "lava_pool stays legal");
        check(SemanticExploreTaskRecord.isLegalInterest("minecraft:village"),
                "a canonical structure id with an evidence profile is legal");
        check(SemanticExploreTaskRecord.isLegalInterest("minecraft:village_plains"),
                "an alias of a registered profile resolves through the profile table");
        check(SemanticExploreTaskRecord.isLegalInterest("MINECRAFT:VILLAGE"),
                "interest ids are matched after lowercase normalization");
        check(!SemanticExploreTaskRecord.isLegalInterest("minecraft:not_a_structure"),
                "an id without an evidence profile is refused");
        check(!SemanticExploreTaskRecord.isLegalInterest(null), "a missing value is refused");

        try {
            record(List.of("minecraft:not_a_structure"));
            check(false, "the direct task entry must reject an unknown interest");
        } catch (IllegalArgumentException invalid) {
            check(invalid.getMessage().contains("evidence profile")
                            && invalid.getMessage().contains("focus=structures"),
                    "the rejection points to the catalog query instead of enumerating ids");
        }
        var accepted = record(List.of("minecraft:village"));
        check(accepted.declaredInterest("minecraft:village"),
                "a structure interest declared on the record matches its canonical id");

        var parameters = new JsonObject();
        var array = new com.google.gson.JsonArray();
        array.add("minecraft:village");
        parameters.add("interests", array);
        check(ExplorationIntent.parseInterests(parameters).equals(List.of("minecraft:village")),
                "plan-time parsing accepts a structure id");
        parameters.addProperty("interests", "lava_pool");
        try {
            ExplorationIntent.parseInterests(parameters);
            check(false, "a non-array interests field must be rejected");
        } catch (IllegalArgumentException invalid) {
            check(invalid.getMessage().contains("focus=structures"),
                    "the plan-time rejection carries the same hint");
        }
    }

    private static void memoryMerge() throws Exception {
        var sighted = ExplorationFinding.observed("structure", "minecraft:village", "minecraft:village",
                "minecraft:overworld", 64, 70, -128, false, 100,
                "{\"group_counts\":{},\"authority\":\"visible_signature_cluster\"}");
        var arrived = ExplorationFinding.observed("structure", "minecraft:village", "minecraft:village",
                "minecraft:overworld", 64, 70, -128, true, 300,
                "{\"authority\":\"reached_and_rechecked\"}");
        check(sighted.id().equals(arrived.id()),
                "a sighting and an arrival in the same cell share the deterministic id");
        var merged = arrived.merge(sighted);
        check(merged.visited() && merged.firstSeen() == 100 && merged.lastSeen() == 300,
                "merging keeps the visit fact and the full observation interval");
        check(!sighted.visited(), "a sighting never claims a visit on its own");

        var saved = new ArrayList<ExplorationFinding>();
        var journal = new ExplorationJournal(saved::addAll, Runnable::run, ignored -> {});
        journal.observe(sighted);
        journal.observe(arrived);
        check(journal.pendingCount() == 1, "the journal keeps one record per cell across both entry paths");
        check(journal.pendingFindings().get(0).visited(),
                "the arrival fact survives the journal upsert");

        var root = Files.createTempDirectory("maicraft-structure-sighting-");
        var identity = new StateIdentity("c".repeat(64), root);
        var store = new ExplorationMemoryStore(identity);
        store.save("trip-village", List.of(sighted));
        store.save("trip-village", List.of(arrived));
        var restored = new ExplorationMemoryStore(identity);
        var found = restored.find(sighted.id());
        check(found != null && "structure".equals(found.kind())
                        && "minecraft:village".equals(found.targetId()) && found.visited(),
                "round-trip keeps kind=structure, the canonical id and the visit fact");
        check(restored.query("minecraft:village", 0, 5).total() == 1,
                "the canonical id query finds the merged record");
    }

    private static SemanticExploreTaskRecord record(List<String> interests) {
        return new SemanticExploreTaskRecord(
                "structure-sighting-test", 1000L, "survey", 128, false,
                org.maiwithu.maicraft.core.pathing.transport.TransportMode.AUTO,
                null, null, null, interests);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
