// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.ToDoubleFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;
import static org.maiwithu.maicraft.core.integration.machine.runtime.ProductionConnectionFixture.*;

/** Every longer segment must remain jointly observable under all of its native per-target limits. */
public final class ProductionConnectionRangeTest {
    public static void main(String[] args) {
        var generic = ProductionConnectionPath.split(line(0, 40), "items", ignored -> 16);
        check(generic.size() == 4, "generic native read range should avoid fourteen tiny segment requests");
        check(ProductionConnectionPath.split(line(0, 40), "items", ignored -> 8)
                        .equals(ProductionConnectionPath.split(line(0, 40), "items")),
                "Create/unloaded eight-block limits retain the conservative four-block split");
        ToDoubleFunction<BlockPos> mixed = pos -> pos.getX() == 20 ? 8 : 16;
        var segments = ProductionConnectionPath.split(line(0, 20), "items", mixed);
        check(segments.size() == 3 && segments.getLast().start() == 18,
                "the distant Create endpoint must not inherit a transmitter's sixteen-block radius");
        assertRangeAndCoverage(line(0, 20), segments, mixed);
        verifiesActualRequests(line(0, 40), ignored -> 16, "mekanism", 4);
        verifiesActualRequests(line(0, 20), mixed, "mekanism", 3);
        var packed = new ArrayList<BlockPos>();
        for (int y = 0; y < 6; y++) for (int row = 0; row < 5; row++) for (int column = 0; column < 5; column++)
            packed.add(new BlockPos((y * 5 + row) % 2 == 0 ? column : 4 - column, y, y % 2 == 0 ? row : 4 - row));
        check(ProductionConnectionPath.split(packed, "items", ignored -> 16).getFirst().points(packed).size() == 128,
                "native request point cap must still split a spatially compact long route");
        verifiesActualRequests(packed, ignored -> 16, "ae2", 2);
        fourStationManifestGeometry();
        System.out.println("ProductionConnectionRangeTest: native ranges, 128-point cap and four-station reduction passed");
    }

    private static void fourStationManifestGeometry() {
        var paths = new ArrayList<List<BlockPos>>(); var create = new HashSet<BlockPos>();
        // Same authored path geometry as the four-station acceptance manifest; no path shortcuts.
        for (int z : new int[]{0, 6, 12, 18}) {
            var feed = new ArrayList<BlockPos>(); for (int x = 0; x <= 20; x++) feed.add(new BlockPos(x, 1, z)); paths.add(feed);
            var output = new ArrayList<BlockPos>(); for (int x = 20; x <= 41; x++) output.add(new BlockPos(x, 1, z));
            for (int zz = z - 1; zz >= 0; zz--) output.add(new BlockPos(41, 1, zz)); output.add(new BlockPos(42, 1, 0)); paths.add(output);
            var power = new ArrayList<BlockPos>(); for (int x = 18; x <= 20; x++) { var p = new BlockPos(x, 3, z); power.add(p); create.add(p); }
            paths.add(power); create.add(new BlockPos(20, 1, z));
        }
        int oldCount = 0, dynamicCount = 0; ToDoubleFunction<BlockPos> radius = pos -> create.contains(pos) ? 8 : 16;
        for (var path : paths) {
            String medium = path.getFirst().getY() == 3 ? "kinetic" : "items";
            oldCount += ProductionConnectionPath.split(path, medium).size();
            var parts = ProductionConnectionPath.split(path, medium, radius); dynamicCount += parts.size();
            assertRangeAndCoverage(path, parts, radius);
        }
        check(oldCount == 72 && dynamicCount == 27, "four-station paths must retain all evidence with twenty-seven safely bounded native requests");
    }

    private static void assertRangeAndCoverage(List<BlockPos> path, List<ProductionConnectionPath.Segment> parts,
                                               ToDoubleFunction<BlockPos> radius) {
        var edges = new HashSet<Integer>(); var junctions = new HashSet<Integer>();
        for (var segment : parts) {
            var points = segment.points(path);
            check(points.size() <= 128 && ProductionObservationRange.goalRadius(points, radius) >= 2,
                    "every segment needs shared native-range space for a nearby stance");
            check(ProductionObservationRange.ready(points.getFirst().getCenter().add(0, -.5, 0), points, radius),
                    "one unchanged feet position must satisfy every target's actual native radius");
            for (int i = segment.start(); i < segment.end(); i++) edges.add(i);
            for (int i = segment.start() + 1; i < segment.end(); i++) junctions.add(i);
        }
        check(edges.size() == path.size() - 1 && junctions.size() == path.size() - 2,
                "segments must retain every original edge and each internal AE2 junction");
    }

    private static void verifiesActualRequests(List<BlockPos> path, ToDoubleFunction<BlockPos> radius, String adapter, int expectedRequests) {
        var identities = new ProductionConnectionFixture(path, "items", adapter); var work = new Queries(radius);
        var survey = new ProductionConnectionSurvey(identities.plan, work, identities::binding, () -> WORLD, () -> work.tick,
                ignored -> adapter, () -> Vec3.ZERO, radius);
        JsonObject report = null;
        for (int i = 0; i < 20000 && report == null; i++) { work.tick++; report = survey.tick(identities.plan.manifest().links().getFirst()); }
        check(report != null && report.get("verified_connection").getAsBoolean() && work.sent.size() == expectedRequests,
                "the actual survey must submit the dynamically bounded groups, not merely compute a shorter plan");
        check(report.getAsJsonArray("edges").size() == path.size() - 1, "wire replies must preserve complete edge coverage");
        if (adapter.equals("ae2")) check(report.getAsJsonArray("intermediate").size() == path.size() - 2,
                "large native groups must retain boundary-node proofs");
    }

    private static final class Queries implements ProductionWork {
        final ToDoubleFunction<BlockPos> radius; final List<JsonObject> sent = new ArrayList<>();
        JsonObject pending; Vec3 feet; long tick = 100;
        Queries(ToDoubleFunction<BlockPos> radius) { this.radius = radius; }
        public boolean approach(BlockPos pos) { throw new AssertionError("connection reads cannot request a mutation stance"); }
        public boolean observe(List<BlockPos> positions) {
            check(pending == null, "pending requests must keep their group and body observation unchanged");
            feet = positions.getFirst().getCenter().add(0, -.5, 0);
            check(ProductionObservationRange.ready(feet, positions, radius), "group cannot be served from one legal observation position"); return true;
        }
        public JsonObject request(String operation, JsonObject body, boolean mutating) {
            check(operation.equals("machine.connections") && !mutating, "only native read-only connection queries are allowed");
            if (pending == null) {
                var points = new ArrayList<BlockPos>(); body.getAsJsonArray("path").forEach(value -> points.add(position(value.getAsJsonObject())));
                check(points.size() <= 128 && ProductionObservationRange.ready(feet, points, radius), "all wire positions must pass the shared range gate");
                pending = body.deepCopy(); sent.add(body.deepCopy()); return null;
            }
            check(pending.equals(body), "query body changed before its receipt"); pending = null;
            return reply(body, tick, "opaque:component-key#this-is-not-a-registry-id");
        }
        public TaskState advanceChild(Task task) { throw new AssertionError("no native mutation child"); }
        public void extendDeadlineTo(long deadline) { check(deadline >= tick, "accepted native progress cannot move the deadline backwards"); }
    }
}
