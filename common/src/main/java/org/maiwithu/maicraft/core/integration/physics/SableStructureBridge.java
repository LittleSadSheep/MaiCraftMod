// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.physics;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;

/** Optional read-only Sable boundary. Frames must be opened and consumed on the client thread. */
public final class SableStructureBridge {
    private static final String CONTAINER = "dev.ryanhcode.sable.api.sublevel.SubLevelContainer";
    private static final Map<MethodKey, Method> METHODS = new ConcurrentHashMap<>();
    public static final int MAX_METADATA_PROBES = 128, MAX_STRUCTURES = 16, MAX_LOADED_CHUNKS = 256;
    public static final double OBSERVATION_RADIUS = 128.0;

    private SableStructureBridge() {}

    /** Vanilla noCollision does not include Sable voxels; use Sable's own player-fit query. */
    public static boolean clearBody(Level level, AABB body) {
        var api = BodyApiHolder.API;
        if (!api.installed()) return true;
        if (api.method() == null) return false;
        try {
            // The native helper removes 0.1 from X/Z size; retain the requested whole body.
            return api.method().invoke(null, level, body.inflate(.05,0,.05)) == null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unknown) { return false; }
    }
    private record BodyApi(boolean installed, Method method) {
        static BodyApi load() {
            try { Class.forName(CONTAINER, false, SableStructureBridge.class.getClassLoader()); }
            catch (ClassNotFoundException absent) { return new BodyApi(false,null); }
            catch (LinkageError unavailable) { return new BodyApi(true,null); }
            try {
                Class<?> type = Class.forName("dev.ryanhcode.sable.mixinhelpers.CanFallAtleastHelper", false,
                        SableStructureBridge.class.getClassLoader());
                return new BodyApi(true,type.getMethod("canFallAtleastWithSubLevels",Level.class,AABB.class));
            } catch (ReflectiveOperationException | LinkageError unavailable) { return new BodyApi(true,null); }
        }
    }
    private static final class BodyApiHolder { static final BodyApi API = BodyApi.load(); }

    /** Direct native UUID lookup; tracking one vessel never depends on the nearby-list budget. */
    public static Structure find(ClientLevel level, UUID id) {
        try {
            Class<?> type = Class.forName(CONTAINER, false, SableStructureBridge.class.getClassLoader());
            Object container = method(type, "getContainer", Level.class).invoke(null, level);
            Object ship = method(container.getClass(), "getSubLevel", UUID.class).invoke(container, id);
            if (ship == null || required((Boolean) call(ship, "isRemoved"))) return null;
            Map<String, String> errors = new LinkedHashMap<>();
            Structure result = snapshot(ship, read("world_bounds", errors, () -> bounds(call(ship, "boundingBox"), false)), errors);
            return id.equals(result.id()) ? result : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return null; }
    }

    public record Contact(UUID trackingId, UUID collisionId, boolean below, boolean known) {
        public boolean supportedBy(UUID id) { return known && below && id.equals(trackingId) && id.equals(collisionId); }
    }

    /** Current native collision, not the historical last-tracked UUID or a nearby hull box. */
    public static Contact contact(Object entity) {
        try {
            Object tracked = call(entity, "sable$getTrackingSubLevel");
            Object collision = call(entity, "sable$getCollisionInfo");
            Object support = collision == null ? null : collision.getClass().getField("trackingSubLevel").get(collision);
            boolean below = collision != null && collision.getClass().getField("verticalCollisionBelow").getBoolean(collision);
            return new Contact(tracked == null ? null : (UUID) call(tracked, "getUniqueId"),
                    support == null ? null : (UUID) call(support, "getUniqueId"), below, true);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return new Contact(null, null, false, false);
        }
    }

    public static Frame open(ClientLevel level) {
        return open(level, null, null);
    }

    public static Frame open(ClientLevel level, Vec3 eye, BlockPos preferredStorageHit) {
        return open(level, eye, preferredStorageHit, false);
    }

    public static Frame openForPerception(ClientLevel level, Vec3 eye, BlockPos preferredStorageHit) {
        return open(level, eye, preferredStorageHit, true);
    }

    private static Frame open(ClientLevel level, Vec3 eye, BlockPos preferredStorageHit, boolean presentation) {
        if (level == null) return failed("no_client_level", "no client level is loaded");
        return openBound(() -> {
            Class<?> type = Class.forName(CONTAINER, false, SableStructureBridge.class.getClassLoader());
            return method(type, "getContainer", Level.class).invoke(null, level);
        }, eye, preferredStorageHit, presentation);
    }

    /** Injectable native source keeps offline tests outside Minecraft and Sable initialization. */
    static Frame openBound(NativeContainerSource source, Vec3 eye, BlockPos preferredStorageHit) {
        return openBound(source, eye, preferredStorageHit, false);
    }

    static Frame openBound(NativeContainerSource source, Vec3 eye, BlockPos preferredStorageHit, boolean presentation) {
        try {
            if (eye != null && (!Double.isFinite(eye.x) || !Double.isFinite(eye.y) || !Double.isFinite(eye.z))) {
                return failed("unknown", "eye position must be finite");
            }
            Object container = source.open();
            if (container == null) return failed("unknown", "Sable client container is unavailable");
            List<?> nativeStructures = (List<?>) required(call(container, "getAllSubLevels"));
            int total = nativeStructures.size(), probes = 0, removed = 0;
            Map<String, String> selectionErrors = new LinkedHashMap<>();
            Object preferred = preferredStorageHit == null ? null : read("preferred_hit", selectionErrors,
                    () -> containing(container, preferredStorageHit));
            Map<Object, Boolean> visited = new IdentityHashMap<>();
            List<Candidate> candidates = new ArrayList<>();
            for (int i = -1; i < total && i < MAX_METADATA_PROBES && probes < MAX_METADATA_PROBES; i++) {
                Object nativeStructure = i < 0 ? preferred : nativeStructures.get(i);
                if ((i < 0 && preferred == null) || visited.put(nativeStructure, true) != null) continue;
                probes++;
                Map<String, String> errors = new LinkedHashMap<>();
                if (Boolean.TRUE.equals(read("removed", errors,
                        () -> required((Boolean) call(nativeStructure, "isRemoved"))))) { removed++; continue; }
                AABB box = read("world_bounds", errors, () -> bounds(call(nativeStructure, "boundingBox"), false));
                double distance = distanceSquared(box, eye);
                boolean priority = nativeStructure == preferred;
                if (priority || box == null || distance <= OBSERVATION_RADIUS * OBSERVATION_RADIUS) {
                    candidates.add(new Candidate(nativeStructure, box, errors, priority ? -1 : distance));
                }
            }
            candidates.sort(Comparator.<Candidate>comparingInt(c -> c.distance() < 0 ? 0
                            : presentation && StructurePresentation.small(c.bounds()) ? 2 : 1)
                    .thenComparingDouble(Candidate::distance));
            List<Structure> structures = new ArrayList<>();
            Map<Object, Structure> identities = new IdentityHashMap<>();
            for (Candidate candidate : candidates.subList(0, Math.min(candidates.size(), MAX_STRUCTURES))) {
                Structure structure = snapshot(candidate.nativeStructure(), candidate.bounds(), candidate.errors());
                structures.add(structure);
                identities.put(candidate.nativeStructure(), structure);
            }
            int omitted = Math.max(0, total - removed - structures.size());
            boolean truncated = omitted > 0 || structures.stream().anyMatch(s -> s.errors().containsKey("loaded_chunks_truncated"));
            boolean partial = truncated || !selectionErrors.isEmpty() || structures.stream().anyMatch(s -> !s.errors().isEmpty());
            return new Frame(partial ? "partial" : structures.isEmpty() ? "ready_empty" : "ready",
                    partial ? "observation is incomplete; inspect budgets and structure errors " + selectionErrors : null,
                    structures, container, identities, total, omitted, probes, truncated);
        } catch (ClassNotFoundException absent) {
            return failed("not_installed", "Sable is not installed");
        } catch (NoSuchMethodException unavailable) {
            return failed("unsupported_api", describe(unavailable));
        } catch (LinkageError unavailable) {
            return failed("unsupported_api", describe(unavailable));
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return failed("unknown", describe(unavailable));
        }
    }

    /** Native handles are private and expire with this observation; never serialize the frame itself. */
    public static final class Frame {
        private final String state;
        private final String error;
        private final List<Structure> structures;
        private final Object container;
        private final Map<Object, Structure> identities;
        private final int total, omitted, metadataProbes;
        private final boolean truncated;

        private Frame(String state, String error, List<Structure> structures, Object container,
                Map<Object, Structure> identities, int total, int omitted, int metadataProbes, boolean truncated) {
            this.state = state;
            this.error = error;
            this.structures = List.copyOf(structures);
            this.container = container;
            this.identities = new IdentityHashMap<>(identities);
            this.total = total; this.omitted = omitted; this.metadataProbes = metadataProbes; this.truncated = truncated;
        }

        public String state() { return state; }
        public String error() { return error; }
        public List<Structure> structures() { return structures; }
        /** Native list size; -1 means unavailable. Omitted excludes observed removals. */
        public int total() { return total; }
        public int omitted() { return omitted; }
        public int metadataProbes() { return metadataProbes; }
        public boolean truncated() { return truncated; }

        public HitResolution resolveHit(BlockPos plotStorageHit) {
            if (container == null) return new HitResolution("unknown", null, error);
            if (plotStorageHit == null) return new HitResolution("unknown", null, "hit position is missing");
            try {
                Object nativeStructure = containing(container, plotStorageHit);
                if (nativeStructure == null) return new HitResolution("not_structure", null, null);
                if (required((Boolean) call(nativeStructure, "isRemoved"))) {
                    return new HitResolution("not_loaded", null, "structure was removed from the client container");
                }
                Structure structure = identities.get(nativeStructure);
                if (structure == null) return new HitResolution("unknown", null, "structure is outside this bounded frame");
                if (structure.id() == null) return new HitResolution("unknown", null, "structure UUID is unavailable");
                return new HitResolution("hit", structure.id(), null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                return new HitResolution("unknown", null, describe(unavailable));
            }
        }
    }

    public record HitResolution(String state, UUID structureId, String error) {}
    public record BlockRead(String state, BlockState blockState, String error) {}

    /** Nullable fields have a corresponding errors entry, except an absent optional display name. */
    public record Structure(UUID id, String name, Boolean ready, StructurePose pose,
            StructurePose lastPose, AABB worldBounds, BlockPos plotCenter, AABB storageBounds,
            List<LevelChunk> loadedChunks, Map<String, String> errors) {
        public Structure {
            loadedChunks = List.copyOf(loadedChunks);
            errors = Map.copyOf(errors);
            if (plotCenter != null) plotCenter = plotCenter.immutable();
        }

        public boolean isLoaded(BlockPos storagePosition) {
            return storagePosition != null && chunkAt(storagePosition) != null;
        }

        public String state() {
            return ready == null ? "unknown" : !ready ? "loading" : errors.isEmpty() ? "ready" : "partial";
        }

        public BlockRead readBlock(BlockPos storagePosition) {
            if (storagePosition == null) return new BlockRead("unknown", null, "storage position is missing");
            try {
                LevelChunk chunk = chunkAt(storagePosition);
                if (chunk == null) {
                    String unavailable = errors.getOrDefault("loaded_chunks", errors.get("loaded_chunks_truncated"));
                    return new BlockRead(unavailable == null ? "not_loaded" : "unknown", null, unavailable);
                }
                return new BlockRead("known", required(chunk.getBlockState(storagePosition)), null);
            } catch (RuntimeException | LinkageError unavailable) {
                return new BlockRead("unknown", null, describe(unavailable));
            }
        }

        private LevelChunk chunkAt(BlockPos position) {
            int x = position.getX() >> 4;
            int z = position.getZ() >> 4;
            for (LevelChunk chunk : loadedChunks) {
                if (chunk.getPos().x == x && chunk.getPos().z == z) return chunk;
            }
            return null;
        }
    }

    private static Structure snapshot(Object nativeStructure, AABB worldBounds, Map<String, String> errors) {
        UUID id = read("id", errors, () -> required((UUID) call(nativeStructure, "getUniqueId")));
        String name = read("name", errors, () -> (String) call(nativeStructure, "getName"));
        Boolean ready = read("ready", errors, () -> required((Boolean) call(nativeStructure, "isFinalized")));
        StructurePose pose = read("pose", errors, () -> pose(call(nativeStructure, "logicalPose")));
        StructurePose lastPose = read("last_pose", errors, () -> pose(call(nativeStructure, "lastPose")));
        Object plot = read("plot", errors, () -> required(call(nativeStructure, "getPlot")));
        BlockPos center = read("plot_center", errors, () -> required((BlockPos) call(plot, "getCenterBlock")));
        AABB storageBounds = read("storage_bounds", errors, () -> bounds(call(plot, "getBoundingBox"), true));
        List<LevelChunk> chunks = read("loaded_chunks", errors, () -> chunks(plot, errors));
        return new Structure(id, name, ready, pose, lastPose, worldBounds, center, storageBounds,
                chunks == null ? List.of() : chunks, errors);
    }

    private static List<LevelChunk> chunks(Object plot, Map<String, String> errors) throws ReflectiveOperationException {
        List<LevelChunk> chunks = new ArrayList<>();
        Iterator<?> holders = ((Iterable<?>) required(call(plot, "getLoadedChunks"))).iterator();
        for (int count = 0; count < MAX_LOADED_CHUNKS && holders.hasNext(); count++) {
            Object holder = holders.next();
            LevelChunk chunk = read("loaded_chunks", errors, () -> required((LevelChunk) call(holder, "getChunk")));
            if (chunk != null) chunks.add(chunk);
        }
        if (holders.hasNext()) errors.put("loaded_chunks_truncated", "loaded chunk observation capped at " + MAX_LOADED_CHUNKS);
        return List.copyOf(chunks);
    }

    private static Object containing(Object container, BlockPos position) throws ReflectiveOperationException {
        Object plot = method(container.getClass(), "getPlot", int.class, int.class)
                .invoke(container, position.getX() >> 4, position.getZ() >> 4);
        return plot == null ? null : required(call(plot, "getSubLevel"));
    }

    private static double distanceSquared(AABB box, Vec3 eye) {
        if (eye == null) return 0;
        if (box == null) return Double.POSITIVE_INFINITY;
        double x = Math.max(0, Math.max(box.minX - eye.x, eye.x - box.maxX));
        double y = Math.max(0, Math.max(box.minY - eye.y, eye.y - box.maxY));
        double z = Math.max(0, Math.max(box.minZ - eye.z, eye.z - box.maxZ));
        return x * x + y * y + z * z;
    }

    private static StructurePose pose(Object nativePose) throws ReflectiveOperationException {
        return StructurePose.copyOf((Vector3dc) call(nativePose, "position"),
                (Quaterniondc) call(nativePose, "orientation"),
                (Vector3dc) call(nativePose, "rotationPoint"), (Vector3dc) call(nativePose, "scale"));
    }

    private static AABB bounds(Object nativeBounds, boolean inclusive) throws ReflectiveOperationException {
        double[] values = new double[6];
        String[] names = {"minX", "minY", "minZ", "maxX", "maxY", "maxZ"};
        for (int i = 0; i < names.length; i++) {
            values[i] = ((Number) call(nativeBounds, names[i])).doubleValue();
            if (!Double.isFinite(values[i])) throw new IllegalArgumentException("non-finite bounds");
        }
        for (int i = 0; i < 3; i++) {
            if (values[i] > values[i + 3]) throw new IllegalArgumentException("empty or inverted bounds");
            if (inclusive) values[i + 3] += 1.0;
        }
        return new AABB(values[0], values[1], values[2], values[3], values[4], values[5]);
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        return method(required(target).getClass(), name).invoke(target);
    }

    private static Method method(Class<?> owner, String name, Class<?>... arguments) throws NoSuchMethodException {
        MethodKey key = new MethodKey(owner, name, List.of(arguments));
        Method found = METHODS.get(key);
        if (found != null) return found;
        Method resolved = owner.getMethod(name, arguments);
        Method previous = METHODS.putIfAbsent(key, resolved);
        return previous == null ? resolved : previous;
    }

    private static <T> T read(String field, Map<String, String> errors, Read<T> read) {
        try {
            return read.get();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            errors.put(field, describe(unavailable));
            return null;
        }
    }

    private static <T> T required(T value) {
        if (value == null) throw new IllegalStateException("native value is unavailable");
        return value;
    }

    private static String describe(Throwable failure) {
        if (failure instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            failure = invocation.getCause();
        }
        return failure.getClass().getSimpleName() + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
    }

    private static Frame failed(String state, String error) {
        return new Frame(state, error, List.of(), null, Map.of(), -1, -1, 0, false);
    }

    private record Candidate(Object nativeStructure, AABB bounds, Map<String, String> errors, double distance) {}
    private record MethodKey(Class<?> owner, String name, List<Class<?>> arguments) {}
    @FunctionalInterface interface NativeContainerSource { Object open() throws ReflectiveOperationException; }
    @FunctionalInterface private interface Read<T> { T get() throws ReflectiveOperationException; }
}
