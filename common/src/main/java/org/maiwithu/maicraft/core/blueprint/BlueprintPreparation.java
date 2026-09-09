package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskDispatch;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.task.InternalAreaProtectionReceipt;

/**
 * 先在后台读文件，再分多次游戏更新把蓝图变成施工目标，最后按请求生成说明或启动建筑任务。
 * 后台线程只交回读取结果；真正开始施工要等本任务的 tick 再次运行。取消后不会由后台回调另开施工。
 */
public final class BlueprintPreparation implements Task {
    public interface Report {
        /** 返回 null 表示说明还没整理完，下次游戏更新继续；非 null 是最终结果。 */
        TaskResult tick(LocalPlayer player, BlueprintStore.Loaded loaded);
    }
    // 一次只读一个文件，最多再排队四个请求；超过容量时提交会被拒绝。
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), Thread.ofPlatform().daemon(true).name("maicraft-blueprint-io").factory());

    static { TaskFactory.register(Request.class, (player, request) -> new BlueprintPreparation(request)); }

    private static final class Request extends TaskRecord
            implements InternalPositionReceipt, InternalAreaProtectionReceipt {
        final String file;
        final BlockPos anchor;
        final int rotation;
        final Report describe;
        final BiFunction<LocalPlayer, BlueprintStore.Loaded, ? extends TaskRecord> build;
        TaskRecord executedRecord;

        Request(String tool, String callId, String file, BlockPos anchor, int rotation,
                Report describe,
                BiFunction<LocalPlayer, BlueprintStore.Loaded, ? extends TaskRecord> build) {
            super(tool, callId, NO_DEADLINE);
            this.file = file;
            this.anchor = anchor == null ? BlockPos.ZERO : anchor.immutable();
            this.rotation = rotation;
            this.describe = describe;
            this.build = build;
        }

        // 只转交实际建筑子任务确认过的位置；读文件成功本身不证明角色已经到达某处。
        @Override public Position internalVerifiedPosition() {
            return executedRecord instanceof InternalPositionReceipt receipt
                    ? receipt.internalVerifiedPosition() : null;
        }

        @Override public List<Footprint> internalAreaProtections() {
            return executedRecord instanceof InternalAreaProtectionReceipt receipt
                    ? receipt.internalAreaProtections() : List.of();
        }
    }

    public static void list(LocalPlayer player, String callId, Consumer<String> reply) {
        TaskDispatch.runSync(player, new Request("blueprint", callId, null, null, 0, null, null), reply);
    }

    public static void read(LocalPlayer player, String callId, String file, BlockPos anchor, int rotation,
            Report describe, Consumer<String> reply) {
        TaskDispatch.runSync(player, new Request("blueprint_read", callId, file, anchor, rotation, describe, null), reply);
    }

    public static void build(LocalPlayer player, String callId, String file, BlockPos anchor, int rotation,
            BiFunction<LocalPlayer, BlueprintStore.Loaded, ? extends TaskRecord> build,
            JsonObject args, Consumer<String> reply) {
        TaskDispatch.setTask(player, new Request("blueprint", callId, file, anchor, rotation, null, build), args, reply);
    }

    private final Request request;
    private Future<CompoundTag> read;
    private Future<List<Map<String, Object>>> listing;
    private BlueprintStore.Loader loader;
    private BlueprintStore.Loaded prepared;
    private Task child;
    private TaskRecord childRecord;
    private TaskResult outcome;

    private BlueprintPreparation(Request request) { this.request = request; }

    @Override public void start(LocalPlayer player) {
        Path gameDirectory = ClientRuntime.requireContext(player).minecraft().gameDirectory.toPath()
                .toAbsolutePath().normalize();
        // 先移掉已取消的排队请求，再提交本次读取。
        IO.purge();
        if (request.file == null) listing = IO.submit(() -> BlueprintFiles.list(gameDirectory));
        else read = IO.submit(() -> BlueprintFiles.read(gameDirectory, request.file));
    }

    @Override public TaskState tick(LocalPlayer player) {
        if (outcome != null) return outcome.success() ? TaskState.SUCCESS : TaskState.FAILED;
        // 建筑已经启动时，按子任务自己的截止时间继续；前面的文件准备没有另设时间上限。
        if (child != null) {
            TaskState state = childRecord.getState().isTerminal() ? childRecord.getState()
                    : player.level().getGameTime() >= childRecord.getDeadlineGameTime()
                    ? TaskState.TIMEOUT : child.tick(player);
            childRecord.setState(state);
            if (state.isTerminal()) {
                finishChild(state);
            }
            return state;
        }
        try {
            if (listing != null) {
                if (!listing.isDone()) return TaskState.RUNNING;
                List<Map<String, Object>> entries = listing.get(); // isDone: never waits on IO
                outcome = TaskResult.ok(entries.isEmpty()
                        ? "no blueprints yet; drop blueprint files into the schematics folder"
                        : entries.size() + " blueprint(s) available", Map.of("blueprints", entries));
                listing = null;
                return TaskState.SUCCESS;
            }
            // 文件没读完就立即返回，保持游戏继续运行；读完后每次只展开一小批方块。
            if (prepared == null) {
                if (loader == null) {
                    if (!read.isDone()) return TaskState.RUNNING;
                    loader = new BlueprintStore.Loader(read.get(), request.anchor, request.rotation);
                    read = null;
                }
                prepared = loader.tick(ClientRuntime.requireContext(player));
                if (prepared == null) return TaskState.RUNNING;
                loader = null;
            }
            if (request.describe != null) {
                outcome = request.describe.tick(player, prepared);
                if (outcome == null) return TaskState.RUNNING;
                return outcome.success() ? TaskState.SUCCESS : TaskState.FAILED;
            }
            // 只有文件读取、展开都完成，且请求确实要建造，才创建并启动建筑子任务。
            childRecord = request.build.apply(player, prepared);
            request.executedRecord = childRecord;
            prepared = null;
            childRecord.setState(TaskState.RUNNING);
            childRecord.markStarted(player.level().getGameTime());
            child = TaskFactory.create(player, childRecord);
            child.start(player);
            if (childRecord.getState().isTerminal()) {
                TaskState terminal = childRecord.getState();
                finishChild(terminal);
                return terminal;
            }
            return TaskState.RUNNING;
        } catch (Exception failure) {
            Throwable cause = failure instanceof java.util.concurrent.ExecutionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            outcome = TaskResult.fail("blueprint preparation failed: " + cause.getMessage());
            return TaskState.FAILED;
        }
    }

    // 取出建筑任务的最终结果，并写回记录；子任务没有给结果时按失败处理。
    private void finishChild(TaskState terminal) {
        outcome = child.result(terminal);
        if (outcome == null) outcome = TaskResult.fail("blueprint build ended without a result");
        childRecord.setResult(outcome);
        child = null;
    }

    // 临时让出控制权时保留准备进度；其他停止原因会取消读取，并丢掉尚未施工的展开结果。
    @Override public void stop(LocalPlayer player, StopReason why) {
        if (child != null) child.stop(player, why);
        if (why != StopReason.PREEMPTED) cancelPreparation();
    }

    private void cancelPreparation() {
        if (read != null) read.cancel(true);
        if (listing != null) listing.cancel(true);
        loader = null;
        prepared = null;
        IO.purge();
    }

    // 最终结束时先停止准备；若建筑还在运行，让建筑子任务按同一结束原因收尾并给出结果。
    @Override public TaskResult result(TaskState terminal) {
        cancelPreparation();
        if (child != null) {
            TaskResult result = child.result(terminal);
            child = null;
            return result;
        }
        if (outcome != null) return outcome;
        return terminal == TaskState.CANCELLED ? TaskResult.cancelled("blueprint preparation cancelled")
                : TaskResult.fail("blueprint preparation ended: " + terminal.name().toLowerCase());
    }

    @Override public boolean mustSettleBeforeSatisfiedCancellation() {
        return child != null && child.mustSettleBeforeSatisfiedCancellation();
    }

    @Override public String name() { return child == null ? "prepare blueprint" : child.name(); }
}
