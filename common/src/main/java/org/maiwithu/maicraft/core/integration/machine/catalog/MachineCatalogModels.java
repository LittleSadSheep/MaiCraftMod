// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.catalog;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Objects;

/** Durable descriptions and historical proofs. None of these records grants a world-operation capability. */
public final class MachineCatalogModels {
    private MachineCatalogModels() {}
    public enum EvidenceStatus { NATIVE_OBSERVED, INFERRED, UNKNOWN }
    public enum CurrentState { CURRENT_PRESENT, CURRENT_ABSENT, STALE, HISTORICAL }
    public record Identity(String worldKey, String accountKey) {
        public Identity { worldKey = CatalogLimits.text(worldKey, 512, "world identity"); accountKey = CatalogLimits.text(accountKey, 256, "account identity"); }
        public String key() { return CatalogLimits.hash(worldKey.length() + ":" + worldKey + accountKey.length() + ":" + accountKey); }
    }
    public record Position(int x, int y, int z) {
        public Position {
            if (Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000 || Math.abs((long) y) > 2048)
                throw new IllegalArgumentException("Catalog position outside supported world bounds");
        }
        public Position plus(int dx, int dy, int dz) { return new Position(Math.addExact(x, dx), Math.addExact(y, dy), Math.addExact(z, dz)); }
        public double distanceSquared(Position other) { double dx = (double) x - other.x, dy = (double) y - other.y, dz = (double) z - other.z; return dx * dx + dy * dy + dz * dz; }
    }
    public record RoleEvidence(String role, EvidenceStatus status, String provenance) {
        public RoleEvidence { role = CatalogLimits.text(role, 96, "role"); Objects.requireNonNull(status); provenance = CatalogLimits.text(provenance, 256, "role provenance"); }
    }
    public record DeviceObservation(String dimension, Position position, String blockId, String blockEntityType, String label,
                                    List<RoleEvidence> roles, EvidenceStatus evidenceStatus, String provenance, long gameTick, long observedAtMillis) {
        public DeviceObservation {
            dimension = CatalogLimits.registry(dimension, "dimension"); Objects.requireNonNull(position);
            blockId = CatalogLimits.registry(blockId, "block id"); blockEntityType = CatalogLimits.optional(blockEntityType, 256, "block entity type");
            if (blockId.equals("minecraft:air")) throw new IllegalArgumentException("Use markAbsent for a loaded missing device");
            label = CatalogLimits.optional(label, 160, "label"); roles = checkedRoles(roles); Objects.requireNonNull(evidenceStatus);
            provenance = CatalogLimits.text(provenance, 256, "observation provenance");
            CatalogLimits.nonnegative(gameTick, "game tick"); CatalogLimits.nonnegative(observedAtMillis, "observation time");
        }
    }
    public record Device(String id, String label, boolean customLabel, String dimension, Position position, String blockId,
                         String blockEntityType, List<RoleEvidence> roles, EvidenceStatus evidenceStatus, String provenance,
                         boolean lastObservedPresent, long firstObservedAtMillis, long lastObservedAtMillis, long lastObservedGameTick) {
        public Device {
            id = CatalogLimits.text(id, 80, "device id"); label = CatalogLimits.text(label, 160, "label");
            dimension = CatalogLimits.registry(dimension, "dimension"); Objects.requireNonNull(position);
            blockId = CatalogLimits.registry(blockId, "block id"); blockEntityType = CatalogLimits.optional(blockEntityType, 256, "block entity type");
            roles = checkedRoles(roles); Objects.requireNonNull(evidenceStatus); provenance = CatalogLimits.text(provenance, 256, "observation provenance");
            CatalogLimits.nonnegative(firstObservedAtMillis, "first observation time"); CatalogLimits.nonnegative(lastObservedAtMillis, "last observation time");
            CatalogLimits.nonnegative(lastObservedGameTick, "last observation tick");
        }
    }
    public record CommissionEvidence(String provenance, String resourceId, long fromTick, long throughTick,
                                     long nativeEvents, long produced, long delivered, long observedAtMillis) {
        public CommissionEvidence {
            provenance = CatalogLimits.text(provenance, 256, "commission provenance"); resourceId = CatalogLimits.text(resourceId, 1024, "commission resource");
            if (fromTick < 0 || throughTick < fromTick || nativeEvents < 1 || produced < 1 || delivered < 1 || delivered > produced)
                throw new IllegalArgumentException("Catalog commission requires a finite observed production/delivery window");
            CatalogLimits.nonnegative(observedAtMillis, "commission time");
        }
    }
    public record Commission(CommissionEvidence evidence, String manifestFingerprint) {
        public Commission { Objects.requireNonNull(evidence); fingerprint(manifestFingerprint); }
    }
    public record Line(String id, String label, String dimension, Position anchor, String manifestJson, String manifestFingerprint,
                       long registeredAtMillis, Commission commission) {
        public Line {
            id = CatalogLimits.text(id, 80, "line id"); label = CatalogLimits.text(label, 160, "line label");
            dimension = CatalogLimits.registry(dimension, "dimension"); Objects.requireNonNull(anchor);
            manifestJson = CatalogLimits.manifest(CatalogLimits.parseManifest(manifestJson)); fingerprint(manifestFingerprint);
            if (!CatalogLimits.hash(manifestJson).equals(manifestFingerprint)) throw new IllegalArgumentException("Catalog manifest fingerprint mismatch");
            CatalogLimits.nonnegative(registeredAtMillis, "registration time");
            var shape = org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.parse(JsonParser.parseString(manifestJson).getAsJsonObject());
            for (var node : shape.nodes()) anchor.plus(node.offset().x(), node.offset().y(), node.offset().z());
            for (var port : shape.ports()) anchor.plus(port.offset().x(), port.offset().y(), port.offset().z());
            for (var link : shape.links()) for (var point : link.path()) anchor.plus(point.x(), point.y(), point.z());
        }
        public JsonObject manifest() { return JsonParser.parseString(manifestJson).getAsJsonObject(); }
        public boolean commissionMatchesManifest() { return commission != null && commission.manifestFingerprint().equals(manifestFingerprint); }
    }
    public record DeviceView(Device device, CurrentState currentState) {
        public DeviceView { Objects.requireNonNull(device); Objects.requireNonNull(currentState); }
        public boolean operationAuthorized() { return false; }
        public JsonObject json(boolean includeLocation) { return CatalogViews.device(this, includeLocation); }
    }
    public record PortView(String id, String medium, String direction, String face, Position position) {}
    public record NodeView(String lineId, String nodeId, String declaredKind, String recipeId, Position position,
                           List<PortView> ports, DeviceView device) {
        public NodeView { ports = List.copyOf(ports); }
        public boolean operationAuthorized() { return false; }
        public JsonObject json(boolean includeLocation) { return CatalogViews.node(this, includeLocation); }
    }
    static String deviceId(String identityKey, String dimension, Position position) { return locationId("device", identityKey, dimension, position); }
    static String lineId(String identityKey, String dimension, Position anchor) { return locationId("line", identityKey, dimension, anchor); }
    private static String locationId(String kind, String identityKey, String dimension, Position position) {
        return kind + ":" + CatalogLimits.hash(identityKey + "\n" + dimension + "\n" + position.x + "," + position.y + "," + position.z);
    }
    private static List<RoleEvidence> checkedRoles(List<RoleEvidence> roles) {
        Objects.requireNonNull(roles); if (roles.size() > CatalogLimits.ROLES) throw new IllegalArgumentException("Too many catalog roles"); return List.copyOf(roles);
    }
    private static void fingerprint(String value) { if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid catalog fingerprint"); }
}
