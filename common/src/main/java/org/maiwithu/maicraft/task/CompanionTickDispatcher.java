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
import org.maiwithu.maicraft.api.Internal;

/**
 * 本地玩家任务调度的客户端线程入口，每次 END_CLIENT_TICK 至多推进一个身体使用者。
 * 玩家或世界实例替换时，先清理旧身体上的任务，再绑定新运行状态。
 */
@Internal
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

    /** 推进当前游戏刻获得身体的唯一任务。 */
    public static void tick(LocalPlayer player) {
        requireClientThread();
        if (player == null || player.isRemoved()) {
            bodyGone();
            return;
        }
        bind(player);
        // 检查提醒是否到期也放在这里。因此上层不让自动任务运行时，提醒也不会弹出。
        TimerRegistry.tick(player);
        brain.tick(player);
    }

    /**
     * 只观察玩家和世界实例是否替换，不推进计时器或任务槽。
     * 因此玩家自行操控时，仍能清理旧身体并完成已获准的传送门交接。
     */
    public static void observeBody(LocalPlayer player) {
        requireClientThread();
        if (player == null || player.isRemoved()) {
            bodyGone();
            return;
        }
        bind(player);
    }

    /** 替换当前后台任务，并在本次客户端游戏刻立即开始新任务。 */
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

    /** 查询当前后台任务；槽位空闲时返回 {@code null}。 */
    public static TaskRecord current() {
        requireClientThread();
        return brain == null ? null : brain.current();
    }

    public static String controllingTask() {
        requireClientThread();
        return brain == null ? "none" : brain.controllingTask();
    }



    /** 返回已占用的任务槽，先列同步任务，再列当前后台任务。 */
    public static List<TaskRecord> list() {
        requireClientThread();
        return brain == null ? List.of() : brain.list();
    }

    /** 按公开任务编号（例如 {@code t42}）查找仍占用槽位的记录。 */
    public static TaskRecord find(String publicId) {
        requireClientThread();
        return brain == null ? null : brain.find(publicId);
    }

    /** 按公开编号取消任务；未指定编号时取消当前后台任务。 */
    public static boolean cancel(String publicId) {
        requireClientThread();
        return boundPlayer != null && brain != null && brain.cancel(boundPlayer, publicId);
    }

    /** 取消当前本地身体所有已占用槽位中的任务。 */
    public static void cancelFor(LocalPlayer player) {
        requireClientThread();
        if (player != null && player == boundPlayer && brain != null) {
            brain.cancelAll(player);
        }
    }
    /** 为当前语义任务登记一次传送门交接，使其可以跨越玩家对象替换继续执行。 */
    public static long prepareDimensionHandoff(
            String destinationDimension, BlockPos portalPosition, Block portalBlock) {
        // 先记住“这个玩家正在为这个任务走进这扇门”，换世界后才能认出该继续哪件事。
        // 只说“我要去下界”还不够，必须看到附近确实有传送门；这次确认只在接下来三十秒内有效。
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

    /** 原生传送失败或被取消时，撤回尚未使用的传送门交接许可。 */
    public static void cancelDimensionHandoff(long token) {
        requireClientThread();
        if (expectedHandoff != null && expectedHandoff.token() == token) expectedHandoff = null;
    }

    /**
     * 只读核对玩家对象替换时能否保留自动控制请求。
     * 玩家 UUID 或连接相同还不够，必须有仍有效的传送门交接与源身体或目标身体匹配。
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


    /** 清理绑定当前玩家和世界的任务，并保留仍可完成的传送门交接。 */
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

    /** 加载器或兼容调用指定玩家离开时，只清理与当前绑定匹配的身体。 */
    public static void bodyGone(LocalPlayer player) {
        requireClientThread();
        if (player == null || player == boundPlayer) {
            bodyGone();
        }
    }

    // 兼容内部工具的查询和取消入口，仍须核对当前绑定的玩家。

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
        // 玩家重生后名字和 UUID 没变，但游戏里的玩家对象可能已换了；旧任务不能继续操作旧对象。
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
        // 真正离开世界时，再检查是不是刚才那件任务、那扇门；检查过就删掉，不能留给下一趟旅行用。
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
        // 到了新世界，确认还是同一玩家、同一服务器，而且确实到了目的维度；否则取消旧任务。
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
        if (pending == null) return;
        if (System.nanoTime() <= pending.expiresAtNanos()
                && connectionAlive(pending.connection())) {
            return;
        }
        pendingHandoff = null;
        abandon(pending.record(),
                "authorised dimension handoff ended with a disconnect or expired");
    }

    private static void abandon(TaskRecord record, String reason) {
        if (!record.getState().isTerminal()) {
            record.setState(TaskState.CANCELLED);
        }
        if (record.getResult() == null) {
            record.setResult(TaskResult.cancelled(reason));
        }
        String callId = record.getToolCallId();
        if (callId != null && !callId.isBlank()) {
            LocalToolDispatcher.deliver(callId, record.getResult().toJson());
        }
    }

    private static boolean connectionAlive(ClientPacketListener connection) {
        return connection != null
                && Minecraft.getInstance().getConnection() == connection
                && connection.getConnection() != null
                && connection.getConnection().isConnected();
    }

    private static boolean validExpectedSource(Handoff handoff, LocalPlayer player) {
        return handoff != null
                && boundPlayer == player
                && boundLevel == handoff.sourceLevel()
                && brain != null
                && brain.current() == handoff.record()
                && player.level() == handoff.sourceLevel()
                && handoff.playerId().equals(player.getUUID())
                && System.nanoTime() <= handoff.expiresAtNanos()
                && connectionAlive(handoff.connection())
                && touchesAuthorisedPortal(player, handoff);
    }

    private static boolean validDestinationBody(Handoff handoff, LocalPlayer player) {
        return handoff != null
                && player != null
                && handoff.playerId().equals(player.getUUID())
                && handoff.destinationDimension().equals(
                        player.level().dimension().location().toString())
                && System.nanoTime() <= handoff.expiresAtNanos()
                && connectionAlive(handoff.connection());
    }

    private static boolean touchesAuthorisedPortal(LocalPlayer player, Handoff handoff) {
        ClientLevel level = handoff.sourceLevel();
        AABB body = player.getBoundingBox().inflate(0.35D);
        BlockPos feet = player.blockPosition();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos position = feet.offset(dx, dy, dz);
                    if (!handoff.portalCells().contains(position.asLong())
                            || !level.isLoaded(position)
                            || !level.getBlockState(position).is(handoff.portalBlock())) {
                        continue;
                    }
                    if (body.intersects(new AABB(position))) return true;
                }
            }
        }
        return false;
    }

    private static Set<Long> connectedPortalCells(
            ClientLevel level, BlockPos origin, Block portalBlock, int limit) {
        // 从入口向上下前后左右找相连的传送门方块；只看已加载的区域，最多找到指定数量就停。
        Set<Long> cells = new HashSet<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        open.add(origin.immutable());
        while (!open.isEmpty() && cells.size() < limit) {
            BlockPos cell = open.removeFirst();
            if (!level.isLoaded(cell)
                    || !level.getBlockState(cell).is(portalBlock)
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

    private record Handoff(
            long token, TaskRecord record, UUID playerId, String destinationDimension,
            ClientPacketListener connection, long expiresAtNanos,
            ClientLevel sourceLevel, Block portalBlock, Set<Long> portalCells) {}
    private static void requireClientThread() {
        if (!Minecraft.getInstance().isSameThread()) {
            throw new IllegalStateException("task runtime is client-thread only");
        }
    }
}
