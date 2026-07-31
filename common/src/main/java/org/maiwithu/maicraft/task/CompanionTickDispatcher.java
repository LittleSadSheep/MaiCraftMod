package org.maiwithu.maicraft.task;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.agent.tool.LocalToolDispatcher;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Client-thread facade for the single local-player task scheduler.
 *
 * <p>The loader calls {@link #tick(LocalPlayer)} once from END_CLIENT_TICK.
 * A player instance or world replacement cancels and cleans the previous body
 * before a new runtime is bound.</p>
 */
@org.maiwithu.maicraft.api.Internal
public final class CompanionTickDispatcher {

    private static final long HANDOFF_LIFETIME_NANOS = 30_000_000_000L;
    private static final int MAX_CONNECTED_PORTAL_CELLS = 256;

    private static CompanionBrain brain;
    private static LocalPlayer boundPlayer;
    private static ClientLevel boundLevel;
    private static long handoffTokenSource;
    private static Handoff expectedHandoff;
    private static Handoff pendingHandoff;

    private CompanionTickDispatcher() {}

    /** Advance the single winner for this END_CLIENT_TICK. */
    public static void tick(LocalPlayer player) {
        requireClientThread();
        if (player == null || player.isRemoved()) {
            bodyGone();
            return;
        }
        bind(player);
        TimerRegistry.tick(player);
        brain.tick(player);
    }

    /**
     * Observe a body/world replacement without advancing timers or either task slot. Control-gated
     * client ticks use this so lifecycle cleanup and authorised portal handoff still happen while
     * a human owns the inputs.
     */
    public static void observeBody(LocalPlayer player) {
        requireClientThread();
        if (player == null || player.isRemoved()) {
            bodyGone();
            return;
        }
        bind(player);
    }

    /** Replace the current task, starting it immediately on this client tick. */
    public static void submitCurrent(LocalPlayer player, TaskRecord record) {
        requireClientThread();
        bind(player);
        brain.submitCurrent(player, record);
    }

    static void submitSync(LocalPlayer player, TaskRecord record) {
        requireClientThread();
        bind(player);
        brain.submitSync(player, record);
    }

    /** Current background task, or {@code null} when the current slot is idle. */
    public static TaskRecord current() {
        requireClientThread();
        return brain == null ? null : brain.current();
    }

    /** Both occupied slots, sync first and current second. */
    public static List<TaskRecord> list() {
        requireClientThread();
        return brain == null ? List.of() : brain.list();
    }

    /** Find an occupied record by its public id (for example {@code t42}). */
    public static TaskRecord find(String publicId) {
        requireClientThread();
        return brain == null ? null : brain.find(publicId);
    }

    /** Cancel a task by public id; blank means the current background task. */
    public static boolean cancel(String publicId) {
        requireClientThread();
        return boundPlayer != null && brain != null && brain.cancel(boundPlayer, publicId);
    }

    /** Cancel every occupied slot for the active local body. */
    public static void cancelFor(LocalPlayer player) {
        requireClientThread();
        if (player != null && player == boundPlayer && brain != null) {
            brain.cancelAll(player);
        }
    }
    /** Authorise one semantic task to survive the LocalPlayer replacement caused by a portal. */
    public static long prepareDimensionHandoff(
            String destinationDimension, BlockPos portalPosition, Block portalBlock) {
        requireClientThread();
        if (destinationDimension == null || destinationDimension.isBlank()) throw new IllegalArgumentException("destination dimension is required");
        TaskRecord active = brain == null ? null : brain.current();
        if (active == null || boundPlayer == null || boundLevel == null) throw new IllegalStateException("no active semantic task can cross dimensions");
        if (portalPosition == null || portalBlock == null
                || !boundLevel.isLoaded(portalPosition)
                || !boundLevel.getBlockState(portalPosition).is(portalBlock)) {
            throw new IllegalStateException("dimension handoff needs live loaded portal evidence");
        }
        Set<Long> portalCells = connectedPortalCells(
                boundLevel, portalPosition, portalBlock, MAX_CONNECTED_PORTAL_CELLS);
        if (portalCells.isEmpty()) {
            throw new IllegalStateException("dimension handoff needs a connected live portal");
        }
        if (boundPlayer.distanceToSqr(
                portalPosition.getX() + 0.5D,
                portalPosition.getY() + 0.5D,
                portalPosition.getZ() + 0.5D) > 10.0D * 10.0D) {
            throw new IllegalStateException("dimension handoff cannot be armed away from the portal");
        }
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (!connectionAlive(connection)) {
            throw new IllegalStateException("dimension handoff needs a live client connection");
        }
        if (expectedHandoff != null || pendingHandoff != null) {
            throw new IllegalStateException("another dimension handoff is already active");
        }
        long token = ++handoffTokenSource;
        if (token <= 0) throw new IllegalStateException("dimension handoff token exhausted");
        expectedHandoff = new Handoff(token, active, boundPlayer.getUUID(),
                destinationDimension, connection, System.nanoTime() + HANDOFF_LIFETIME_NANOS,
                boundLevel, portalBlock, portalCells);
        return token;
    }

    /** Revoke an unused portal handoff when the native portal action fails or is cancelled. */
    public static void cancelDimensionHandoff(long token) {
        requireClientThread();
        if (expectedHandoff != null && expectedHandoff.token() == token) expectedHandoff = null;
    }

    /**
     * Read-only proof for carrying an automation request across LocalPlayer replacement. UUID or
     * connection equality alone is intentionally insufficient: an armed, live portal handoff must
     * match the old source body and/or the pending destination body.
     */
    public static boolean preservesAutomationControl(
            LocalPlayer previousPlayer, LocalPlayer replacementPlayer) {
        requireClientThread();
        if (previousPlayer == null && replacementPlayer == null) return false;

        Handoff expected = expectedHandoff;
        if (previousPlayer != null && validExpectedSource(expected, previousPlayer)) {
            return replacementPlayer == null
                    || validDestinationBody(expected, replacementPlayer);
        }

        Handoff pending = pendingHandoff;
        if (replacementPlayer != null && validDestinationBody(pending, replacementPlayer)) {
            return previousPlayer == null
                    || pending.sourceLevel() == previousPlayer.level()
                    && pending.playerId().equals(previousPlayer.getUUID());
        }
        return false;
    }


    /** Cancel and clean all work anchored to the active player/world. */
    public static void bodyGone() {
        requireClientThread();
        if (boundPlayer != null && brain != null) {
            if (!detachExpectedHandoff()) {
                brain.bodyGone(boundPlayer);
            }
        }
        TimerRegistry.clear();
        brain = null;
        boundPlayer = null;
        boundLevel = null;
        abandonPendingIfDisconnected();
    }

    /** Body-specific lifecycle overload for loader and compatibility callers. */
    public static void bodyGone(LocalPlayer player) {
        requireClientThread();
        if (player == null || player == boundPlayer) {
            bodyGone();
        }
    }

    // ---- compatibility surface used by existing internal tools ----

    public static TaskRecord currentTaskFor(UUID playerUuid) {
        requireClientThread();
        return boundPlayer != null && boundPlayer.getUUID().equals(playerUuid)
                ? (brain == null ? null : brain.current())
                : null;
    }

    public static boolean currentFreshlyAccepted(LocalPlayer player) {
        requireClientThread();
        return player == boundPlayer && brain != null && brain.current.freshlyAccepted(player);
    }

    public static TaskRecord stopActive(LocalPlayer player, String reason) {
        requireClientThread();
        TaskRecord active = player == boundPlayer && brain != null ? brain.current() : null;
        if (active != null) {
            brain.cancel(player, active.publicId());
        }
        return active;
    }

    public static void clearActiveTask(LocalPlayer player) {
        bodyGone(player);
    }

    public static void onCompanionRemoved(LocalPlayer player) {
        bodyGone(player);
    }

    private static void bind(LocalPlayer player) {
        if (player == null) {
            throw new IllegalArgumentException("local player is required");
        }
        ClientLevel level = (ClientLevel) player.level();
        if (boundPlayer != null && (boundPlayer != player || boundLevel != level)) {
            if (!detachExpectedHandoff()) {
                brain.bodyGone(boundPlayer);
            }
            TimerRegistry.clear();
            brain = null;
        }
        boundPlayer = player;
        boundLevel = level;
        if (brain == null) {
            brain = new CompanionBrain();
        }
        resumePendingHandoff(player);
    }

    private static boolean detachExpectedHandoff() {
        Handoff expected = expectedHandoff;
        if (expected == null) return false;
        expectedHandoff = null;
        if (brain == null || boundPlayer == null || brain.current() != expected.record()
                || !boundPlayer.getUUID().equals(expected.playerId())
                || boundLevel != expected.sourceLevel()
                || System.nanoTime() > expected.expiresAtNanos()
                || !connectionAlive(expected.connection())
                || !touchesAuthorisedPortal(boundPlayer, expected)) {
            return false;
        }
        TaskRecord detached = brain.detachCurrentForHandoff(boundPlayer);
        if (detached == null || detached != expected.record()) {
            return false;
        }
        pendingHandoff = new Handoff(expected.token(), detached, expected.playerId(),
                expected.destinationDimension(), expected.connection(), expected.expiresAtNanos(),
                expected.sourceLevel(), expected.portalBlock(), expected.portalCells());
        return true;
    }

    private static void resumePendingHandoff(LocalPlayer player) {
        Handoff pending = pendingHandoff;
        if (pending == null) return;
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (pending.connection() != connection
                || !connectionAlive(connection)
                || !pending.playerId().equals(player.getUUID())
                || System.nanoTime() > pending.expiresAtNanos()) {
            pendingHandoff = null;
            abandon(pending.record(), "authorised dimension handoff did not reach its target");
            return;
        }
        if (!pending.destinationDimension().equals(
                player.level().dimension().location().toString())) {
            pendingHandoff = null;
            abandon(pending.record(),
                    "authorised dimension handoff produced a body in the wrong dimension");
            return;
        }
        pendingHandoff = null;
        brain.submitCurrent(player, pending.record());
    }

    private static void abandonPendingIfDisconnected() {
        Handoff pending = pendingHandoff;
