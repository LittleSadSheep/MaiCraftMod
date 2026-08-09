// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.scan.TargetIndex;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.move.MoveToCompanionTask;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * Cross a portal with the real local-player body.
 *
 * <p>Only already loaded portal blocks are evidence. The task never asks a server for hidden
 * structure locations and never invents coordinates. Immediately before walking into a portal it
 * authorises one scheduler handoff; only the same player and connection reaching the requested
 * dimension within thirty seconds may resume the semantic parent.</p>
 */
public final class DimensionTravelCompanionTask
        extends AbstractCompanionTask<DimensionTravelTaskRecord> {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final String END = "minecraft:the_end";
    private static final int INDEX_BUILD_BUDGET = 8;
    private static final int MAX_PORTAL_CANDIDATES = 32;
    private static final int MAX_FAILED_CANDIDATES = 8;
    private static final int MAX_CONNECTED_PORTAL_CELLS = 256;
    private static final int MAX_PORTAL_ENTRY_TICKS = 20 * 20;
    private static final long HANDOFF_REFRESH_NANOS = 25_000_000_000L;

    private enum Phase { FIND, MOVE, ENTER, WAIT_FOR_REPLACEMENT }

    private final Set<Long> attempted = new HashSet<>();
    private ClientLevel indexedLevel;
    private Set<Block> targetBlocks = Set.of();
    private Set<Long> portalCells = Set.of();
    private BlockPos portal;
    private String portalBlockId;
    private String startDimension;
    private String issueCode;
    private Phase phase = Phase.FIND;
    private MoveToCompanionTask moveChild;
    private long handoffToken;
    private int portalEntryTicks;
    private boolean arrived;
    private boolean cleaned;
    private long handoffPreparedAtNanos;

    public DimensionTravelCompanionTask(
            LocalPlayer player, DimensionTravelTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        startDimension = dimension();
        if (r.destinationDimension.equals(startDimension)) {
            arrived = true;
            succeed();
            return;
        }
        Block portalBlock = portalBlock(startDimension, r.destinationDimension);
        if (portalBlock == null) {
            failIssue(
                    "unsupported_dimension_route",
                    "No generic vanilla portal connects " + startDimension + " directly to "
                            + r.destinationDimension + ". Choose an intermediate semantic dimension.");
            return;
        }
        indexedLevel = (ClientLevel) player.level();
        targetBlocks = Set.of(portalBlock);
        portalBlockId = BuiltInRegistries.BLOCK.getKey(portalBlock).toString();
        TargetIndex.register(indexedLevel, targetBlocks);
    }

    @Override
    protected TaskState onTick() {
        if (r.destinationDimension.equals(dimension())) {
            arrived = true;
            return TaskState.SUCCESS;
        }
        if (moveChild != null) return tickMove();
        return switch (phase) {
            case ENTER -> tickPortalEntry();
            case WAIT_FOR_REPLACEMENT -> tickPortalWait();
            default -> findPortal();
        };
    }

    private TaskState findPortal() {
        int chunkRadius = Math.max(1, (r.searchRadius + 15) / 16);
        TargetIndex.Result observed = TargetIndex.query(
                (ClientLevel) player.level(),
                player.blockPosition(),
                targetBlocks,
                MAX_PORTAL_CANDIDATES,
                chunkRadius,
                INDEX_BUILD_BUDGET);
        portal = observed.hits().stream()
                .filter(this::livePortal)
                .filter(position -> !attempted.contains(position.asLong()))
                .filter(position -> position.distSqr(player.blockPosition())
                        <= (double) r.searchRadius * r.searchRadius)
                .min(Comparator.comparingDouble(
                        (BlockPos position) -> position.distSqr(player.blockPosition())))
                .orElse(null);
        if (portal == null) {
            if (!observed.complete()) return TaskState.RUNNING;
            failIssue(
                    "portal_not_observed",
                    "No portal for " + r.destinationDimension + " is visible in the loaded client "
                            + "world within " + r.searchRadius + " blocks. Explore to load one, or "
                            + "explicitly request preparation of an appropriate portal.");
            return TaskState.FAILED;
        }
        portalCells = connectedPortalCells(portal);
        if (portalCells.isEmpty()) {
            attempted.add(portal.asLong());
            portal = null;
            return TaskState.RUNNING;
        }

        MoveToTaskRecord moveRecord = new MoveToTaskRecord(
                "dimension-portal-" + r.getId() + "-" + attempted.size(),
                r.getDeadlineGameTime(),
                (double) portal.getX(),
                (double) portal.getY(),
                (double) portal.getZ(),
                null,
                r.mayAlterTerrain);
        moveChild = new MoveToCompanionTask(player, moveRecord);
        phase = Phase.MOVE;
        armHandoffIfNear();
        return TaskState.RUNNING;
    }

    private TaskState tickMove() {
        armHandoffIfNear();
        TaskState terminal = runChild(moveChild);
        if (terminal == null) return TaskState.RUNNING;
        TaskResult result = moveChild.result(terminal);
        moveChild = null;
        if (r.destinationDimension.equals(dimension())) {
            arrived = true;
            return TaskState.SUCCESS;
        }
        if (terminal == TaskState.SUCCESS && result.success()) {
            phase = Phase.ENTER;
            portalEntryTicks = 0;
            return TaskState.RUNNING;
        }
        revokeHandoff();
        attempted.add(portal.asLong());
        portal = null;
        portalCells = Set.of();
        phase = Phase.FIND;
        if (attempted.size() >= MAX_FAILED_CANDIDATES) {
            failIssue(
                    "portal_unreachable",
                    "Observed portal candidates could not be reached safely with the allowed terrain policy.");
            return TaskState.FAILED;
        }
        return TaskState.RUNNING;
    }

    private TaskState tickPortalEntry() {
        armHandoffIfNear();
        if (intersectsConnectedPortal()) {
            InputDriver.halt(player);
            phase = Phase.WAIT_FOR_REPLACEMENT;
            return TaskState.RUNNING;
        }
        if (portal == null || !livePortal(portal)) {
            return rejectCurrentPortal("the observed portal changed before entry");
        }
        if (++portalEntryTicks > MAX_PORTAL_ENTRY_TICKS) {
            return rejectCurrentPortal("the observed portal could not be entered with first-person movement");
        }
        InputDriver.lookAt(player, Vec3.atCenterOf(portal));
        InputDriver.applyMovement(player, 1.0F, 0.0F, false, false, false);
        return TaskState.RUNNING;
    }

    private TaskState tickPortalWait() {
        armHandoffIfNear();
        if (intersectsConnectedPortal()) {
            InputDriver.halt(player);
            return TaskState.RUNNING;
        }
        phase = Phase.ENTER;
        return tickPortalEntry();
    }

    private TaskState rejectCurrentPortal(String reason) {
        InputDriver.halt(player);
        revokeHandoff();
        if (portal != null) attempted.add(portal.asLong());
        portal = null;
        portalCells = Set.of();
        phase = Phase.FIND;
        portalEntryTicks = 0;
        if (attempted.size() >= MAX_FAILED_CANDIDATES) {
            failIssue("portal_unreachable", reason);
            return TaskState.FAILED;
        }
        return TaskState.RUNNING;
    }

    private boolean livePortal(BlockPos position) {
        ClientLevel level = (ClientLevel) player.level();
        return level.isLoaded(position)
                && targetBlocks.contains(level.getBlockState(position).getBlock());
    }

    private static Block portalBlock(String current, String destination) {
        if ((OVERWORLD.equals(current) && NETHER.equals(destination))
                || (NETHER.equals(current) && OVERWORLD.equals(destination))) {
            return Blocks.NETHER_PORTAL;
        }
        if ((OVERWORLD.equals(current) && END.equals(destination))
                || (END.equals(current) && OVERWORLD.equals(destination))) {
            return Blocks.END_PORTAL;
        }
        return null;
    }

    private String dimension() {
        return player.level().dimension().location().toString();
    }

    private void failIssue(String code, String message) {
        issueCode = code;
        fail(message, FailureType.TARGET_LOST);
    }

    private void revokeHandoff() {
        if (handoffToken == 0L) return;
        CompanionTickDispatcher.cancelDimensionHandoff(handoffToken);
        handoffToken = 0L;
        handoffPreparedAtNanos = 0L;
    }

    private void armHandoffIfNear() {
        long now = System.nanoTime();
        if (handoffToken != 0L && now - handoffPreparedAtNanos >= HANDOFF_REFRESH_NANOS) {
            revokeHandoff();
        }
        if (handoffToken != 0L || portal == null || !livePortal(portal)
                || player.distanceToSqr(
                        portal.getX() + 0.5D,
                        portal.getY() + 0.5D,
                        portal.getZ() + 0.5D) > 8.0D * 8.0D) {
            return;
        }
        Block liveBlock = player.level().getBlockState(portal).getBlock();
        handoffToken = CompanionTickDispatcher.prepareDimensionHandoff(
                r.destinationDimension, portal, liveBlock);
        handoffPreparedAtNanos = now;
    }

    @Override
    public void stop(LocalPlayer companion, Task.StopReason why) {
        try {
            super.stop(companion, why);
        } finally {
            if (why == Task.StopReason.PREEMPTED) {
                revokeHandoff();
            }
            if (why == Task.StopReason.BODY_GONE) {
                cleanup();
            }
        }
    }

    @Override
    protected void cleanup() {
        if (cleaned) return;
        boolean complete = true;
        try {
            revokeHandoff();
