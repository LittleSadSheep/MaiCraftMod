// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionEvidence;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.Link;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.Port;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.Resource;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** Walks an entire declared path through bounded native queries, keeping one immutable request in flight. */
public final class ProductionConnectionSurvey {
    private final ProductionRunPlan plan;
    private final ProductionWork work;
    private final Function<Resource, ProductionEvidence.Binding> bindings;
    private final Supplier<String> dimension;
    private final LongSupplier clock;
    private final Function<BlockPos, String> nativeSystem;
    private final Supplier<Vec3> bodyPosition;
    private final java.util.function.ToDoubleFunction<BlockPos> observationRadius;
    private Link active;
    private List<BlockPos> path = List.of();
    private List<ProductionConnectionPath.Segment> segments = List.of();
    private ProductionConnectionResponses responses;
    private ProductionConnectionBinding binding;
    private String system, failure;
    private JsonObject pending;
    private int segmentIndex;
    private boolean finished, navigating;

    public ProductionConnectionSurvey(LocalPlayer player, ProductionRunPlan plan, ProductionWork work,
                                      Function<Resource, ProductionEvidence.Binding> bindings) {
        this(plan, work, bindings, () -> player.level().dimension().location().toString(), () -> player.level().getGameTime(),
                position -> nativeSystemAt(player, position), player::position,
                position -> ProductionObservationRange.radius(player.level(), position));
    }

    /** Deterministic environment seam for request/range regressions without fabricating a running game. */
    ProductionConnectionSurvey(ProductionRunPlan plan, ProductionWork work, Function<Resource, ProductionEvidence.Binding> bindings,
                               Supplier<String> dimension, LongSupplier clock, Function<BlockPos, String> nativeSystem) {
        this(plan, work, bindings, dimension, clock, nativeSystem, () -> null);
    }

    ProductionConnectionSurvey(ProductionRunPlan plan, ProductionWork work, Function<Resource, ProductionEvidence.Binding> bindings,
                               Supplier<String> dimension, LongSupplier clock, Function<BlockPos, String> nativeSystem, Supplier<Vec3> bodyPosition) {
        this(plan, work, bindings, dimension, clock, nativeSystem, bodyPosition, null);
    }

    ProductionConnectionSurvey(ProductionRunPlan plan, ProductionWork work, Function<Resource, ProductionEvidence.Binding> bindings,
                               Supplier<String> dimension, LongSupplier clock, Function<BlockPos, String> nativeSystem,
                               Supplier<Vec3> bodyPosition, java.util.function.ToDoubleFunction<BlockPos> observationRadius) {
        this.plan = Objects.requireNonNull(plan); this.work = Objects.requireNonNull(work); this.bindings = Objects.requireNonNull(bindings);
        this.dimension = Objects.requireNonNull(dimension); this.clock = Objects.requireNonNull(clock); this.nativeSystem = Objects.requireNonNull(nativeSystem);
        this.bodyPosition = Objects.requireNonNull(bodyPosition);
        this.observationRadius = observationRadius;
    }

    /** Choose at a settled boundary; pending navigation or an operation keeps its selected link. */
    public Link nearestLink(List<Link> candidates) {
        if (candidates.isEmpty()) throw new IllegalArgumentException("production_connection_links_missing");
        if (active != null && !finished) {
            if (!candidates.contains(active)) throw new IllegalStateException("production_connection_link_changed_before_completion");
            return active;
        }
        Vec3 feet = bodyPosition.get();
        if (feet == null) return candidates.getFirst();
        Link nearest = candidates.getFirst(); double distance = Double.POSITIVE_INFINITY;
        for (Link candidate : candidates) {
            try {
                List<BlockPos> points = pathFor(candidate);
                var parts = split(points, candidate.resource().medium());
                double score = endpointDistance(feet, points, parts.getFirst());
                // An unobserved authored adapter requires the original first-segment probe.
                if (selectSystem(candidate, parts.getFirst(), points) != null)
                    score = Math.min(score, endpointDistance(feet, points, parts.getLast()));
                if (score < distance) { nearest = candidate; distance = score; }
            } catch (IllegalArgumentException | ArithmeticException invalid) { return candidate; }
        }
        return nearest;
    }

    /** Null means movement or the same server request is still pending. A non-null report settles this link. */
    public JsonObject tick(Link link) {
        Objects.requireNonNull(link);
        if (active == null || !active.equals(link)) {
            if (pending != null) throw new IllegalStateException("production_connection_link_changed_before_receipt_consumed");
            begin(link);
        }
        // A world change or caller refresh cannot replace the operation occupying ProductionWork's request slot.
        if (pending != null) return poll();
        if (finished) return result();
        if (!plan.dimension().equals(dimension.get())) return fail("production_connection_world_changed");
        ProductionConnectionPath.Segment segment = segments.get(segmentIndex);
        navigating = true;
        if (!work.observe(segment.points(path))) return null;
        navigating = false;
        if (system == null) {
            system = selectSystem(active, segment, path);
            if (system == null) return fail("production_connection_native_adapter_not_observed");
        }
        pending = query(segment);
        return poll();
    }

    /** Call at a settled stage boundary; the task owns cancellation of its shared request slot. */
    public void reset() {
        if (pending != null) throw new IllegalStateException("production_connection_reset_before_receipt_consumed");
        if (navigating) work.stopMovement();
        active = null; path = List.of(); segments = List.of(); responses = null; binding = null;
        system = failure = null; segmentIndex = 0; finished = navigating = false;
    }

    public JsonObject report() {
        JsonObject result = new JsonObject();
        result.addProperty("status", finished ? "settled" : active == null ? "idle" : pending != null ? "awaiting_native_reply" : "observing_path");
        if (active != null) result.addProperty("link", active.id());
        result.addProperty("segments_completed", segmentIndex); result.addProperty("segments_total", segments.size());
        result.addProperty("path_positions", path.size()); result.addProperty("request_pending", pending != null);
        result.addProperty("visit_order", !segments.isEmpty() && segments.getFirst().start() > 0 ? "reverse" : "forward");
        if (failure != null) result.addProperty("reason", failure);
        if (binding != null) result.add("resource_binding", binding.report());
        return result;
    }

    private void begin(Link link) {
        reset(); active = link;
        try {
            Port from = plan.port(link.from()), to = plan.port(link.to());
            path = pathFor(link);
            segments = split(path, link.resource().medium());
            if (!path.getFirst().equals(plan.at(from.offset())) || !path.getLast().equals(plan.at(to.offset()))
                    || !from.face().equals(ProductionConnectionPath.face(path.getFirst(), path.get(1)))
                    || !to.face().equals(ProductionConnectionPath.face(path.getLast(), path.get(path.size() - 2)))) {
                failure = "production_connection_path_does_not_match_endpoint_faces"; finished = true; return;
            }
            binding = ProductionConnectionBinding.resolve(link.resource(), bindings);
            // Adapter identity belongs to the authored origin, never to whichever end is nearer.
            system = selectSystem(link, segments.getFirst(), path);
            Vec3 feet = bodyPosition.get();
            if (system != null && feet != null && endpointDistance(feet, path, segments.getLast())
                    < endpointDistance(feet, path, segments.getFirst())) segments = List.copyOf(segments.reversed());
            responses = new ProductionConnectionResponses(path, plan.dimension(), link.resource().medium(), binding.exactId());
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            failure = invalid.getMessage() == null ? "production_connection_path_invalid" : invalid.getMessage(); finished = true;
        }
    }

    private JsonObject query(ProductionConnectionPath.Segment segment) {
        JsonObject query = new JsonObject(); JsonArray points = new JsonArray();
        segment.points(path).forEach(position -> points.add(ProductionRunPlan.position(position)));
        query.add("path", points); query.addProperty("system", system); query.addProperty("medium", active.resource().medium());
        binding.addQuery(query);
        String fromFace = segment.start() == 0 ? plan.port(active.from()).face()
                : ProductionConnectionPath.face(path.get(segment.start()), path.get(segment.start() + 1));
        String toFace = segment.end() == path.size() - 1 ? plan.port(active.to()).face()
                : ProductionConnectionPath.face(path.get(segment.end()), path.get(segment.end() - 1));
        // Native diagonal gears have no cardinal segment endpoint face; omit that optional assertion.
        if (fromFace != null) query.addProperty("from_face", fromFace);
        if (toFace != null) query.addProperty("to_face", toFace);
        return query;
    }

    private JsonObject poll() {
        JsonObject reply = work.request("machine.connections", pending.deepCopy(), false);
        if (reply == null) return null;
        pending = null;
        ProductionConnectionPath.Segment segment = segments.get(segmentIndex);
        long previousTick = responses.latestTick();
        responses.accept(segment.start(), segment.end(), system, reply);
        segmentIndex++;
        long now = clock.getAsLong(), acceptedTick = responses.latestTick();
        if (acceptedTick > previousTick && acceptedTick >= Math.max(0, now - 1_200))
            work.extendDeadlineTo(Math.max(now, acceptedTick) + 1_200);
        if (!plan.dimension().equals(dimension.get())) return fail("production_connection_world_changed");
        if (segmentIndex == segments.size()) { finished = true; return result(); }
        return null;
    }

    private JsonObject fail(String reason) { failure = reason; finished = true; return result(); }

    private JsonObject result() {
        JsonObject result = responses == null ? emptyResult()
                : responses.finish(Math.max(clock.getAsLong(), responses.latestTick()));
        if (failure != null || !plan.dimension().equals(dimension.get())) {
            result.addProperty("status", "unknown"); result.addProperty("complete", false);
            result.addProperty("verified_connection", false); result.addProperty("operational", false);
            result.addProperty("resource_compatibility", "unknown");
            result.addProperty("reason", failure == null ? "production_connection_world_changed" : failure);
        }
        result.addProperty("link_id", active.id());
        if (binding != null) result.add("resource_binding", binding.report());
        result.addProperty("flow_verified", false); result.addProperty("production_verified", false);
        return result;
    }

    private JsonObject emptyResult() {
        JsonObject result = new JsonObject(); result.addProperty("schema", "maicraft.connection_inspection.v1");
        result.addProperty("dimension", plan.dimension()); result.addProperty("medium", active.resource().medium());
        result.addProperty("tick", 0); result.addProperty("server_tick_available", false);
        result.add("edges", new JsonArray()); result.add("intermediate", new JsonArray());
        JsonArray points = new JsonArray(); path.forEach(position -> points.add(ProductionRunPlan.position(position))); result.add("path", points);
        return result;
    }

    private List<BlockPos> pathFor(Link link) {
        return link.path().isEmpty() ? List.of(plan.at(plan.port(link.from()).offset()), plan.at(plan.port(link.to()).offset()))
                : link.path().stream().map(plan::at).map(BlockPos::immutable).toList();
    }

    private List<ProductionConnectionPath.Segment> split(List<BlockPos> points, String medium) {
        return observationRadius == null ? ProductionConnectionPath.split(points, medium)
                : ProductionConnectionPath.split(points, medium, observationRadius);
    }

    private static double endpointDistance(Vec3 feet, List<BlockPos> path, ProductionConnectionPath.Segment segment) {
        return feet.distanceToSqr(path.get(segment.start()).getCenter());
    }

    private String selectSystem(Link link, ProductionConnectionPath.Segment segment, List<BlockPos> path) {
        if (link.resource().medium().equals("kinetic")) return "create";
        boolean ae = false, create = false;
        for (BlockPos position : segment.points(path)) {
            String adapter = nativeSystem.apply(position);
            if ("mekanism".equals(adapter)) return "mekanism";
            ae |= "ae2".equals(adapter); create |= "create".equals(adapter);
        }
        return ae ? "ae2" : create ? "create" : null;
    }

    private static String nativeSystemAt(LocalPlayer player, BlockPos position) {
        if (!player.level().isLoaded(position)) return null;
        Object entity = player.level().getBlockEntity(position);
        if (NativeApi.is(entity, "mekanism.common.tile.transmitter.TileEntityTransmitter")
                || NativeApi.is(entity, "mekanism.common.tile.TileEntityLogisticalSorter")) return "mekanism";
        if (NativeApi.is(entity, "appeng.api.networking.IInWorldGridNodeHost")) return "ae2";
        if (NativeApi.is(entity, "com.simibubi.create.foundation.blockEntity.SmartBlockEntity")) return "create";
        return null;
    }
}
