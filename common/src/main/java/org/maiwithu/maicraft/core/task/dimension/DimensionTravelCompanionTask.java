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
 * 由真实的本地玩家角色穿过传送门。
 *
 * <p>只有已加载的传送门方块才作为证据。任务不会向服务器查询隐藏结构位置，也不会臆造坐标。
 * 角色即将走入传送门前，只授权一次调度器交接；只有同一玩家和连接在三十秒内抵达目标维度，才可恢复语义父任务。</p>
 */
public final class DimensionTravelCompanionTask
        extends AbstractCompanionTask<DimensionTravelTaskRecord> {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final String END = "minecraft:the_end";
    private static final int INDEX_BUILD_BUDGET = 8;
    private static final int INITIAL_PORTAL_CANDIDATES = 32;
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
    private MoveToTaskRecord moveRecord;
    private long handoffToken;
    private int portalEntryTicks;
    private boolean arrived;
    private boolean cleaned;
    private long handoffPreparedAtNanos;
    private String lastPortalFailure;
    private PortalPreparationTask preparationTask;
    private PortalPreparationTaskRecord preparationRecord;
    private boolean preparationAttempted;
    private Map<String, Object> preparationData = Map.of();
    /** 只有当前最近目标窗口全部耗尽时才扩大搜索范围。 */
    private int portalCandidateWindow = INITIAL_PORTAL_CANDIDATES;

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
        if (preparationTask != null) return tickPreparation();
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
                portalCandidateWindow,
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
            if (!observed.complete()) {
                // TargetIndex 会以有界批次推进有限的已加载区域扫描；等待批次完成时不能消耗语义任务的存活期限，尤其是在游戏刻加速的客户端上。
                // 只有扫描完整后，才能据此得出“未找到”结论。
                r.extendDeadlineTo(r.getDeadlineGameTime() + 1);
                return TaskState.RUNNING;
            }
            // TargetIndex 有意只返回最近目标窗口。若该有限窗口中的每个目标都已失败，就扩大搜索窗口，而不是误以为任意固定次数的尝试已耗尽整个已加载范围。
            if (observed.hits().size() >= portalCandidateWindow
                    && portalCandidateWindow < Integer.MAX_VALUE / 2) {
                portalCandidateWindow *= 2;
                return TaskState.RUNNING;
            }
            if (attempted.isEmpty() && r.preparation.enabled() && !preparationAttempted) {
                preparationAttempted = true;
                preparationRecord = new PortalPreparationTaskRecord(r.getToolCallId() + "-prepare",
                        r.getDeadlineGameTime(), r.destinationDimension, r.searchRadius, r.mayAlterTerrain, r.preparation);
                preparationTask = new PortalPreparationTask(player, preparationRecord);
                return TaskState.RUNNING;
            }
            failIssue(
                    attempted.isEmpty() ? "portal_not_observed" : "portal_unreachable",
                    attempted.isEmpty()
                            ? "No portal for " + r.destinationDimension
                                    + " is visible in the loaded client world within "
                                    + r.searchRadius + " blocks. Explore to load one, or explicitly "
                                    + "request preparation of an appropriate portal."
                            : "Every observed portal candidate inside the requested loaded search "
                                    + "scope was exhausted"
                                    + (lastPortalFailure == null ? "." : ": " + lastPortalFailure));
            return TaskState.FAILED;
        }
        portalCells = connectedPortalCells(portal);
        if (portalCells.isEmpty()) {
            attempted.add(portal.asLong());
            portal = null;
            return TaskState.RUNNING;
        }

        moveRecord = new MoveToTaskRecord(
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

    private TaskState tickPreparation() {
        TaskState terminal;
        if (player.level().getGameTime() >= preparationRecord.getDeadlineGameTime()) {
            preparationTask.stop(player, Task.StopReason.REPLACED); terminal = TaskState.TIMEOUT;
        } else terminal = runChild(preparationTask);
        r.extendDeadlineTo(preparationRecord.getDeadlineGameTime());
        if (terminal == null) return TaskState.RUNNING;
        TaskResult result = preparationTask.result(terminal);
        preparationData = result.data() == null ? Map.of() : Map.copyOf(result.data());
        preparationTask = null; preparationRecord = null;
        if (terminal != TaskState.SUCCESS || !result.success()) {
            failIssue(preparationData.getOrDefault("issue_code", "portal_preparation_failed").toString(), result.message());
            return TaskState.FAILED;
        }
        phase = Phase.FIND;
        return TaskState.RUNNING;
    }

    private TaskState tickMove() {
        armHandoffIfNear();
        TaskState terminal = runChild(moveChild);
        if (terminal == null) {
            r.extendDeadlineTo(moveRecord.getDeadlineGameTime());
            return TaskState.RUNNING;
        }
        TaskResult result = moveChild.result(terminal);
        moveChild = null;
        moveRecord = null;
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
        lastPortalFailure = result == null || result.message() == null
                ? "the first-person approach failed" : result.message();
        attempted.add(portal.asLong());
        attempted.addAll(portalCells);
        portal = null;
        portalCells = Set.of();
        phase = Phase.FIND;
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
        lastPortalFailure = reason;
        if (portal != null) attempted.add(portal.asLong());
        attempted.addAll(portalCells);
        portal = null;
        portalCells = Set.of();
        phase = Phase.FIND;
        portalEntryTicks = 0;
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
            if (preparationTask != null) preparationTask.stop(companion, why);
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
        if (preparationTask != null) {
            try {
                preparationTask.stop(player, Task.StopReason.REPLACED);
                preparationTask.result(TaskState.CANCELLED);
                preparationTask = null; preparationRecord = null;
            } catch (RuntimeException ignored) { complete = false; }
        }
        try {
            revokeHandoff();
        } catch (RuntimeException ignored) {
            complete = false;
        }
        if (moveChild != null) {
            boolean childComplete = true;
            try {
                moveChild.stop(player, Task.StopReason.REPLACED);
            } catch (RuntimeException ignored) {
                childComplete = false;
                complete = false;
            }
            try {
                moveChild.result(TaskState.CANCELLED);
            } catch (RuntimeException ignored) {
                childComplete = false;
                complete = false;
            }
            if (childComplete) {
                moveChild = null;
                moveRecord = null;
            }
        } else {
            moveRecord = null;
        }
        if (indexedLevel != null && !targetBlocks.isEmpty()) {
            try {
                TargetIndex.unregister(indexedLevel, targetBlocks);
                indexedLevel = null;
                targetBlocks = Set.of();
            } catch (RuntimeException ignored) {
                complete = false;
            }
        } else {
            indexedLevel = null;
            targetBlocks = Set.of();
        }
        try {
            InputDriver.halt(player);
        } catch (RuntimeException ignored) {
            complete = false;
        }
        try {
            super.cleanup();
        } catch (RuntimeException ignored) {
            complete = false;
        }
        portalCells = Set.of();
        if (complete) cleaned = true;
    }

    private Set<Long> connectedPortalCells(BlockPos origin) {
        ClientLevel level = (ClientLevel) player.level();
        Block block = level.getBlockState(origin).getBlock();
        Set<Long> cells = new HashSet<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        open.add(origin.immutable());
        while (!open.isEmpty() && cells.size() < MAX_CONNECTED_PORTAL_CELLS) {
            BlockPos cell = open.removeFirst();
            if (!level.isLoaded(cell)
                    || !level.getBlockState(cell).is(block)
                    || !cells.add(cell.asLong())) {
                continue;
            }
            open.add(cell.above());
            open.add(cell.below());
            open.add(cell.north());
            open.add(cell.south());
            open.add(cell.east());
            open.add(cell.west());
        }
        return Set.copyOf(cells);
    }

    private boolean intersectsConnectedPortal() {
        if (portalCells.isEmpty()) return false;
        ClientLevel level = (ClientLevel) player.level();
        AABB body = player.getBoundingBox().inflate(0.05D);
        BlockPos feet = player.blockPosition();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos cell = feet.offset(dx, dy, dz);
                    if (portalCells.contains(cell.asLong())
                            && level.isLoaded(cell)
                            && targetBlocks.contains(level.getBlockState(cell).getBlock())
                            && body.intersects(new AABB(cell))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.putAll(preparationData);
        data.put("destination_dimension", r.destinationDimension);
        data.put("final_dimension", dimension());
        if (portalBlockId != null) data.put("portal_block", portalBlockId);
        data.put("observed_candidates_tried", attempted.size() + (portal == null ? 0 : 1));
        data.put("verified", arrived && r.destinationDimension.equals(dimension()));
        if (issueCode != null) {
            data.put("issue_code", issueCode);
            data.put("requires_decision", true);
            data.putIfAbsent("recovery_options", recoveryOptions());
        }
        return data;
    }

    private List<Map<String, Object>> recoveryOptions() {
        List<Map<String, Object>> options = new ArrayList<>();
        options.add(Map.of(
                "choice", "explore",
                "description", "Explore physically until an appropriate portal is loaded and observed."));
        if (NETHER.equals(r.destinationDimension)) {
            options.add(Map.of(
                    "choice", "prepare_portal",
                    "description", "Acquire portal materials, construct a valid bounded frame, ignite it, then retry."));
        } else if (END.equals(r.destinationDimension)) {
            options.add(Map.of(
                    "choice", "activate_portal",
                    "description", "Discover a stronghold from physical evidence and activate its portal before retrying."));
        }
        return List.copyOf(options);
    }

    @Override
    protected String successMessage() {
        return "arrived in " + r.destinationDimension + " through an observed physical portal";
    }

    @Override
    protected String timeoutMessage() {
        return "dimension travel stopped making verifiable progress before the requested world was observed";
    }

    @Override
    protected String cancelledMessage() {
        return "dimension travel was interrupted";
    }
}
