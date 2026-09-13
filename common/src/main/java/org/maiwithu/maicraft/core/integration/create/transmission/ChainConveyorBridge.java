// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Read-only Create 6 chain-conveyor API; no connection setters or packet constructors are exposed. */
public final class ChainConveyorBridge {
    public static final String BLOCK_ID = "create:chain_conveyor";
    public static final String ENTITY = "com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity";
    private static final String HANDLER = "com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorConnectionHandler";
    public record Limits(int maximumLength, int maximumConnections) {
        public Limits { if (maximumLength < 5 || maximumConnections < 1) throw new IllegalArgumentException("chain_conveyor_invalid_native_limits"); }
    }
    public record Selection(BlockPos first, ResourceKey<Level> dimension) {
        public Selection { if (first != null) first = first.immutable(); }
        public boolean matches(Level level, BlockPos position) { return first != null && first.equals(position) && dimension != null && dimension.equals(level.dimension()); }
    }
    public record Kinetic(float speed, boolean networkPresent, boolean overstressed, String networkId) {
        public boolean powered() { return Float.isFinite(speed) && speed != 0 && networkPresent && !overstressed; }
    }
    private ChainConveyorBridge() {}
    public static boolean available() {
        try { Class.forName(ENTITY, false, ChainConveyorBridge.class.getClassLoader()); return true; }
        catch (ClassNotFoundException | LinkageError unavailable) { return false; }
    }
    public static boolean isConveyor(BlockEntity entity) {
        try { return entity != null && Class.forName(ENTITY).isInstance(entity); }
        catch (ClassNotFoundException | LinkageError unavailable) { return false; }
    }
    public static boolean clientLinkAvailable() {
        if (!available()) return false;
        try {
            Class<?> handler = Class.forName(HANDLER, false, ChainConveyorBridge.class.getClassLoader());
            handler.getDeclaredField("firstPos"); handler.getDeclaredField("firstDim"); return true;
        } catch (ReflectiveOperationException | LinkageError unavailable) { return false; }
    }
    public static Kinetic kinetic(Level world, BlockPos at) {
        if (!world.isLoaded(at)) throw new IllegalArgumentException("chain_conveyor_unloaded");
        Object entity = world.getBlockEntity(at);
        if (!(entity instanceof BlockEntity blockEntity) || !isConveyor(blockEntity)) throw new IllegalArgumentException("chain_conveyor_endpoint_changed");
        try {
            float speed = ((Number) entity.getClass().getMethod("getSpeed").invoke(entity)).floatValue();
            boolean network = (Boolean) entity.getClass().getMethod("hasNetwork").invoke(entity);
            boolean stressed = (Boolean) entity.getClass().getMethod("isOverStressed").invoke(entity);
            Object id = entity.getClass().getField("network").get(entity);
            return new Kinetic(speed, network, stressed, id == null ? "" : id.toString());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError missing) { throw unavailable(missing); }
    }
    public static Limits limits(Level world) {
        if (world == null) throw new IllegalArgumentException("chain_conveyor_world_missing");
        try {
            Object server = Class.forName("com.simibubi.create.infrastructure.config.AllConfigs").getMethod("server").invoke(null);
            Object kinetics = server.getClass().getField("kinetics").get(server);
            return new Limits(config(kinetics, "maxChainConveyorLength"), config(kinetics, "maxChainConveyorConnections"));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError missing) { throw unavailable(missing); }
    }
    public static int linkCost(BlockPos delta) {
        try { return ((Number) Class.forName(ENTITY).getMethod("getChainCost", BlockPos.class).invoke(null, delta)).intValue(); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError missing) { throw unavailable(missing); }
    }
    public static Set<BlockPos> connections(Level world, BlockPos at) {
        if (!world.isLoaded(at)) throw new IllegalArgumentException("chain_conveyor_unloaded");
        return connections(world.getBlockEntity(at));
    }
    public static boolean existingLink(Set<BlockPos> first, Set<BlockPos> second, BlockPos delta) {
        boolean outward = first.contains(delta), inward = second.contains(BlockPos.ZERO.subtract(delta));
        if (outward != inward) throw new IllegalArgumentException("chain_conveyor_one_sided_link_requires_inspection");
        return outward;
    }
    /** Native connection coordinates are relative to this conveyor; snapshots never grant mutation permission. */
    public static Set<BlockPos> connections(BlockEntity entity) {
        if (!isConveyor(entity)) throw new IllegalArgumentException("chain_conveyor_endpoint_changed");
        try {
            var result = new LinkedHashSet<BlockPos>();
            for (Object value : (Set<?>) entity.getClass().getField("connections").get(entity)) result.add(((BlockPos) value).immutable());
            return Set.copyOf(result);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError missing) { throw unavailable(missing); }
    }
    @SuppressWarnings("unchecked")
    public static Selection selection() {
        try {
            Class<?> type = Class.forName(HANDLER);
            var first = type.getDeclaredField("firstPos"); first.setAccessible(true);
            var dimension = type.getDeclaredField("firstDim"); dimension.setAccessible(true);
            return new Selection((BlockPos) first.get(null), (ResourceKey<Level>) dimension.get(null));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError missing) { throw unavailable(missing); }
    }
    private static int config(Object kinetics, String name) throws ReflectiveOperationException {
        Object value = kinetics.getClass().getField(name).get(kinetics);
        return ((Number) value.getClass().getMethod("get").invoke(value)).intValue();
    }
    private static IllegalArgumentException unavailable(Throwable cause) { return new IllegalArgumentException("chain_conveyor_native_api_unavailable", cause); }
}
