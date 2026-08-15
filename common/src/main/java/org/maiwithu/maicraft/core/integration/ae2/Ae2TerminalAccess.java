// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ae2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/** Loaded-client-world discovery and process-local memory for AE2 terminal access. */
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

    record Discovery(List<FixedTarget> targets, int terminalsObserved) {
        Discovery { targets = List.copyOf(targets); }
    }

    record Known(UUID playerId, ClientLevel level, BlockPos position, Direction side) {
        Known { position = position.immutable(); }
    }

    private static Known remembered;
    private static UUID observationPlayer;
    private static ClientLevel observationLevel;
    private static BlockPos observationOrigin;
    private static int observationIndex;
    private static long nextObservationTick;

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

    static Discovery discover(LocalPlayer player, Ae2ReflectionBridge bridge) {
        ClientLevel level = (ClientLevel) player.level();
        BlockPos origin = player.blockPosition();
        BlockPos minimum = new BlockPos(
                origin.getX() - FIXED_TERMINAL_RADIUS,
                Math.max(level.getMinBuildHeight(), origin.getY() - FIXED_TERMINAL_VERTICAL_RADIUS),
                origin.getZ() - FIXED_TERMINAL_RADIUS);
        BlockPos maximum = new BlockPos(
                origin.getX() + FIXED_TERMINAL_RADIUS,
                Math.min(level.getMaxBuildHeight() - 1,
                        origin.getY() + FIXED_TERMINAL_VERTICAL_RADIUS),
                origin.getZ() + FIXED_TERMINAL_RADIUS);
        List<Observed> observed = new ArrayList<>();
        int observedCount = 0;
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
                    for (Direction side : bridge.fixedTerminalSides(entry.getValue())) {
                        observedCount++;
                        if (observed.size() < MAX_FIXED_TERMINALS) {
                            observed.add(new Observed(position.immutable(), side));
                        }
                    }
                }
            }
        }
        observed.sort(Comparator.comparingDouble(value -> value.position().distSqr(origin)));
        List<FixedTarget> targets = new ArrayList<>();
        for (Observed value : observed.stream().limit(MAX_FIXED_TERMINAL_PLAN_TARGETS).toList()) {
            targets.addAll(targetsFor(player, value.position(), value.side()));
        }
        return new Discovery(targets, observedCount);
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

    /**
     * Incrementally remember a nearby fixed terminal while ordinary play is already keeping its
     * chunk loaded.  This is passive perception, not a transaction: a bounded number of already
     * loaded chunk block-entity maps is inspected per client tick and no packet, movement, menu or
     * world action is submitted. Empty terrain is never scanned block by block.
     *
     * <p>The memory lets a later semantic material prerequisite return to an already observed AE
     * access point after travelling elsewhere.  It remains only a last-known location until the
     * terminal's chunk is loaded again and {@link #stillPresent} confirms it.</p>
     */
    static synchronized void observeNearby(
            LocalPlayer player, Ae2ReflectionBridge bridge, int chunkBudget) {
        if (chunkBudget <= 0 || remembered(player) != null) return;
        ClientLevel level = (ClientLevel) player.level();
        long now = level.getGameTime();
        boolean identityChanged = observationPlayer == null
                || !observationPlayer.equals(player.getUUID())
                || observationLevel != level;
        boolean originTooFar = observationOrigin != null
                && observationOrigin.distSqr(player.blockPosition())
                > square(OBSERVATION_CHUNK_RADIUS * 16);
        boolean completed = observationIndex >= OBSERVATION_OFFSETS.size();
        if (identityChanged || originTooFar || completed && now >= nextObservationTick) {
            observationPlayer = player.getUUID();
            observationLevel = level;
            observationOrigin = player.blockPosition().immutable();
            observationIndex = 0;
        }
        if (observationOrigin == null
                || observationIndex >= OBSERVATION_OFFSETS.size()
                && now < nextObservationTick) return;

        int inspected = 0;
        int originChunkX = observationOrigin.getX() >> 4;
        int originChunkZ = observationOrigin.getZ() >> 4;
        while (inspected++ < chunkBudget && observationIndex < OBSERVATION_OFFSETS.size()) {
            BlockPos offset = OBSERVATION_OFFSETS.get(observationIndex++);
            LevelChunk chunk = level.getChunkSource().getChunkNow(
                    originChunkX + offset.getX(), originChunkZ + offset.getZ());
            if (chunk == null) continue;
            for (var entry : chunk.getBlockEntities().entrySet()) {
                List<Direction> sides = bridge.fixedTerminalSides(entry.getValue());
                if (sides.isEmpty()) continue;
                remembered = new Known(player.getUUID(), level,
                        entry.getKey(), sides.getFirst());
                observationIndex = OBSERVATION_OFFSETS.size();
                nextObservationTick = now + OBSERVATION_RESCAN_TICKS;
                return;
            }
        }
        if (observationIndex >= OBSERVATION_OFFSETS.size()) {
            nextObservationTick = now + OBSERVATION_RESCAN_TICKS;
        }
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

    private record Observed(BlockPos position, Direction side) {}

    private static List<BlockPos> makeObservationOffsets() {
        List<BlockPos> result = new ArrayList<>();
        for (int x = -OBSERVATION_CHUNK_RADIUS; x <= OBSERVATION_CHUNK_RADIUS; x++) {
            for (int z = -OBSERVATION_CHUNK_RADIUS; z <= OBSERVATION_CHUNK_RADIUS; z++) {
                result.add(new BlockPos(x, 0, z));
            }
        }
        result.sort(Comparator.comparingLong(value ->
                (long) value.getX() * value.getX()
                        + (long) value.getZ() * value.getZ()));
        return List.copyOf(result);
    }

    private static double square(int value) {
        return (double) value * value;
    }

    private static final List<Direction> HORIZONTAL_DIRECTIONS = List.of(
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST);
    private static final int FIXED_TERMINAL_RADIUS = 16;
    private static final int FIXED_TERMINAL_VERTICAL_RADIUS = 8;
    private static final int MAX_FIXED_TERMINALS = 64;
    private static final int MAX_FIXED_TERMINAL_PLAN_TARGETS = 8;
    private static final double TERMINAL_FACE_OFFSET = 0.45;
    private static final int OBSERVATION_RESCAN_TICKS = 10 * 20;
    private static final int OBSERVATION_CHUNK_RADIUS = 16;
    private static final List<BlockPos> OBSERVATION_OFFSETS = makeObservationOffsets();
}
