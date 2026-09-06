// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ae2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Iterator;
import java.util.PriorityQueue;
import java.util.function.Function;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/** Observed terminal access, with bounded task-owned discovery and process-local successful access memory. */
final class Ae2TerminalAccess {
    static final List<ResourceLocation> WIRELESS_TERMINAL_IDS = List.of(
            ResourceLocation.fromNamespaceAndPath("ae2", "wireless_terminal"),
            ResourceLocation.fromNamespaceAndPath("ae2", "wireless_crafting_terminal"));

    record Wireless(int inventorySlot, ResourceLocation itemId, ItemStack snapshot) {
        Wireless {
            snapshot = snapshot.copy();
        }
    }

    record FixedTarget(String id, BlockPos position, Direction side, BlockPos approach, Vec3 hit) {
        FixedTarget {
            position = position.immutable();
            approach = approach.immutable();
        }
    }

    record Known(UUID playerId, ClientLevel level, BlockPos position, Direction side) {
        Known { position = position.immutable(); }
    }

    record ExplicitObservation(
            int radius,
            int fixedTerminalsObserved,
            int terminalFacesObserved,
            Known selected) {}

    private static Known remembered;

    private Ae2TerminalAccess() {}

    static Wireless findWireless(LocalPlayer player) {
        return findWireless(player, 0, 35);
    }

    static Wireless findWireless(LocalPlayer player, int firstSlot, int lastSlot) {
        int upper = Math.min(lastSlot, Math.min(35, player.getInventory().getContainerSize() - 1));
        for (int slot = Math.max(0, firstSlot); slot <= upper; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (WIRELESS_TERMINAL_IDS.contains(id)) return new Wireless(slot, id, stack);
        }
        return null;
    }

    /**
     * Remember a fixed terminal only from an explicit, bounded machine observation. This inspects
     * block-entity maps of already-loaded chunks intersecting the observed cube; it never loads a
     * chunk, moves the player, opens a menu, or broadens the requested observation area.
     */
    static synchronized ExplicitObservation rememberObservedWithin(
            LocalPlayer player, Ae2ReflectionBridge bridge, BlockPos center, int requestedRadius) {
        ClientLevel level = (ClientLevel) player.level();
        if (requestedRadius < 0) {
            throw new IllegalArgumentException("explicit observation radius may not be negative");
        }
        int radius = requestedRadius;
        BlockPos minimum = new BlockPos(
                center.getX() - radius,
                Math.max(level.getMinBuildHeight(), center.getY() - radius),
                center.getZ() - radius);
        BlockPos maximum = new BlockPos(
                center.getX() + radius,
                Math.min(level.getMaxBuildHeight() - 1, center.getY() + radius),
                center.getZ() + radius);
        List<Observed> observed = new ArrayList<>();
        int terminalCount = 0;
        int faceCount = 0;
        for (int chunkX = minimum.getX() >> 4; chunkX <= maximum.getX() >> 4; chunkX++) {
            for (int chunkZ = minimum.getZ() >> 4; chunkZ <= maximum.getZ() >> 4; chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) continue;
                for (var entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos position = entry.getKey();
                    if (position.getX() < minimum.getX() || position.getX() > maximum.getX()
                            || position.getY() < minimum.getY() || position.getY() > maximum.getY()
                            || position.getZ() < minimum.getZ()
                            || position.getZ() > maximum.getZ()) continue;
                    List<Direction> sides = bridge.fixedTerminalSides(entry.getValue());
                    if (sides.isEmpty()) continue;
                    terminalCount++;
                    faceCount += sides.size();
                    for (Direction side : sides) {
                        observed.add(new Observed(position.immutable(), side));
                    }
                }
            }
        }
        observed.sort(Comparator
                .comparingDouble((Observed value) -> value.position().distSqr(center))
                .thenComparingDouble(value -> value.position().distSqr(player.blockPosition())));
        Known selected = null;
        if (!observed.isEmpty()) {
            Observed nearest = observed.getFirst();
            selected = new Known(player.getUUID(), level, nearest.position(), nearest.side());
            remembered = selected;
        }
        return new ExplicitObservation(radius, terminalCount, faceCount, selected);
    }

    static List<FixedTarget> targetsFor(LocalPlayer player, BlockPos position, Direction side) {
        List<BlockPos> preferred = new ArrayList<>();
        if (side.getAxis().isHorizontal()) {
            for (int distance = 1; distance <= 2; distance++) {
                preferred.add(position.relative(side, distance).below());
                preferred.add(position.relative(side, distance));
            }
        }
        for (Direction direction : HORIZONTAL_DIRECTIONS) {
            for (int distance = 1; distance <= 2; distance++) {
                preferred.add(position.relative(direction, distance).below());
                preferred.add(position.relative(direction, distance));
            }
        }
        Vec3 hit = hit(position, side);
        double reach = player.blockInteractionRange();
        double reachSquared = reach * reach;
        Set<BlockPos> distinct = new LinkedHashSet<>(preferred);
        return distinct.stream().filter(approach -> {
                    Vec3 eye = new Vec3(
                            approach.getX() + 0.5,
                            approach.getY() + player.getEyeHeight(),
                            approach.getZ() + 0.5);
                    return eye.distanceToSqr(hit) <= reachSquared;
                })
                .sorted(Comparator.comparingDouble(value -> value.distSqr(player.blockPosition())))
                .map(approach -> new FixedTarget(
                        candidateId(position, side, approach), position, side, approach, hit))
                .toList();
    }

    static boolean stillPresent(
            LocalPlayer player, Ae2ReflectionBridge bridge, BlockPos position, Direction side) {
        ClientLevel level = (ClientLevel) player.level();
        return level.isLoaded(position)
                && bridge.hasFixedTerminal(level.getBlockEntity(position), side);
    }

    static boolean isLoaded(LocalPlayer player, BlockPos position) {
        return ((ClientLevel) player.level()).isLoaded(position);
    }

    static synchronized void remember(LocalPlayer player, FixedTarget target) {
        remembered = new Known(player.getUUID(), (ClientLevel) player.level(),
                target.position(), target.side());
    }

    static synchronized Known remembered(LocalPlayer player) {
        Known value = remembered;
        if (value == null) return null;
        if (!value.playerId().equals(player.getUUID()) || value.level() != player.level()) {
            remembered = null;
            return null;
        }
        return value;
    }

    static synchronized void discard(Known expected) {
        if (remembered == expected || remembered != null && remembered.equals(expected)) {
            remembered = null;
        }
    }

    private static Vec3 hit(BlockPos position, Direction side) {
        return Vec3.atCenterOf(position).add(
                side.getStepX() * TERMINAL_FACE_OFFSET,
                side.getStepY() * TERMINAL_FACE_OFFSET,
                side.getStepZ() * TERMINAL_FACE_OFFSET);
    }

    private static String candidateId(BlockPos position, Direction side, BlockPos approach) {
        return "ae2:" + position.getX() + ',' + position.getY() + ',' + position.getZ()
                + ':' + side.getSerializedName() + ':'
                + approach.getX() + ',' + approach.getY() + ',' + approach.getZ();
    }

    record Observed(BlockPos position, Direction side) {
        Observed { position = position.immutable(); }
    }

    /** Scan loaded block-entity maps by coordinate so block updates cannot invalidate an iterator. */
    static final class Discovery {
        static final int RADIUS = 16;
        static final int CELLS_PER_TICK = 1024;
        static final int MAX_FACES = 32;
        private final Iterator<BlockPos> cells;
        private final Function<BlockPos, List<Direction>> probe;
        private final Comparator<Observed> order;
        private final PriorityQueue<Observed> found;
        private int checkedCells, unloadedCells;

        Discovery(LocalPlayer player, Ae2ReflectionBridge bridge) {
            this(player.blockPosition(), position -> {
                ClientLevel level = (ClientLevel) player.level();
                LevelChunk chunk = level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
                return chunk == null ? null : bridge.fixedTerminalSides(chunk.getBlockEntities().get(position));
            });
        }

        Discovery(BlockPos center, Function<BlockPos, List<Direction>> probe) {
            this.probe = probe;
            cells = BlockPos.betweenClosed(center.offset(-RADIUS, -RADIUS, -RADIUS),
                    center.offset(RADIUS, RADIUS, RADIUS)).iterator();
            order = Comparator.comparingDouble((Observed value) -> value.position().distSqr(center))
                    .thenComparingLong(value -> value.position().asLong()).thenComparingInt(value -> value.side().ordinal());
            found = new PriorityQueue<>(order.reversed());
        }

        boolean tick() {
            long deadline = System.nanoTime() + 1_000_000;
            for (int checked = 0; checked < CELLS_PER_TICK && cells.hasNext() && System.nanoTime() < deadline; checked++) {
                BlockPos position = cells.next();
                checkedCells++;
                List<Direction> sides = probe.apply(position);
                if (sides == null) { unloadedCells++; continue; }
                for (Direction side : sides) {
                    Observed candidate = new Observed(position, side);
                    if (found.contains(candidate)) continue;
                    if (found.size() < MAX_FACES) found.add(candidate);
                    else if (order.compare(candidate, found.peek()) < 0) {
                        found.poll();
                        found.add(candidate);
                    }
                }
            }
            return !cells.hasNext();
        }

        List<Observed> result() { return found.stream().sorted(order).toList(); }

        String summary() {
            return "radius=" + RADIUS + ", checked_cells=" + checkedCells + ", unloaded_cells=" + unloadedCells
                    + ", retained_terminal_faces=" + found.size() + "; only the loaded portion of this local cube was observed";
        }
    }

    private static final List<Direction> HORIZONTAL_DIRECTIONS = List.of(
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST);
    private static final double TERMINAL_FACE_OFFSET = 0.45;
}
