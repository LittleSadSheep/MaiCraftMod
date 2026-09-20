// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.core.task.enchant.EnchantTaskRecord;
import org.maiwithu.maicraft.intent.persistence.IntentStateCodec;
import org.maiwithu.maicraft.intent.persistence.IntentStateStore;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;

/** 用真实检查点文件和可控后台队列复现崩溃窗口；不打开附魔台、不提交按钮，也不改玩家物品或经验。 */
public final class EnchantDurableCheckpointTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Path directory = Files.createTempDirectory("enchant-durable-checkpoint-");
        parentBeforeReservation(directory.resolve("ordered")); coalescedCheckpoint(directory.resolve("coalesced"));
        for (boolean coalesced : new boolean[]{false, true}) failedCheckpoint(directory.resolve("blocked-" + coalesced), coalesced);
        System.out.println("EnchantDurableCheckpointTest: parent identity persists before consumption; merged and failed writes stay guarded");
    }

    private static void parentBeforeReservation(Path directory) throws Exception {
        var h = new Harness(directory); var reservations = new AtomicInteger(); var markerReady = new AtomicBoolean();
        h.child.submissionBarrier(NativeSubmissionBinding.barrier(h.parent, h.runtime, "enchant", () -> {
            verifyRestored(h); reservations.incrementAndGet(); return markerReady.get();
        }));
        check(!h.child.prepareNativeConsumptionBoundary() && !h.child.prepareNativeConsumptionBoundary(), "父任务未落盘时持续等待");
        check(reservations.get() == 0 && h.queue.submitted == 1 && !Files.exists(h.file()), "不能因内存已有父任务就提前预留或发按钮");
        check(h.child.nativeConsumptionReserved(), "屏障启动后保留普通重试禁令");
        h.queue.runNext();
        check(!h.child.prepareNativeConsumptionBoundary() && reservations.get() == 1, "父检查点成功后仍须等待独立附魔预约");
        markerReady.set(true);
        check(h.child.prepareNativeConsumptionBoundary() && reservations.get() == 2 && h.queue.submitted == 1,
                "父检查点与预约均完成才放行，等待预约不能重复保存父任务");
    }

    private static void coalescedCheckpoint(Path directory) throws Exception {
        var h = new Harness(directory); var reservations = new AtomicInteger();
        h.child.submissionBarrier(NativeSubmissionBinding.barrier(h.parent, h.runtime, "enchant", () -> {
            verifyRestored(h); reservations.incrementAndGet(); return true;
        }));
        check(!h.child.prepareNativeConsumptionBoundary(), "先等待强制捕获的父任务检查点");
        var first = h.store.latestSaveCompletion(h.identity);
        h.requestKeys.put("newer-checkpoint-key", h.parent.externalId());
        check(h.captureOrdinary(), "普通检查点已接受进程内快照");
        var latest = h.store.latestSaveCompletion(h.identity);
        check(first.isCancelled() && !latest.isDone() && !Files.exists(h.file()), "合并取消旧future不代表磁盘保存成功");
        check(!h.child.prepareNativeConsumptionBoundary() && reservations.get() == 0
                && h.store.latestSaveCompletion(h.identity) == latest, "消费屏障跟随最新写入，不取消它重新写盘");
        h.queue.runNext();
        check(h.child.prepareNativeConsumptionBoundary() && reservations.get() == 1, "最新完整检查点写完才允许预约");
        check(read(h).requestKeys().get("newer-checkpoint-key").equals(h.parent.externalId()), "实际磁盘必须保留合并后的最新请求去重键");
    }

    private static void failedCheckpoint(Path blocked, boolean coalesced) throws Exception {
        // 文件占据检查点目录，实际后台写入必然失败；覆盖旧future后的失败也必须保留，不能自动再捕获重试。
        Files.writeString(blocked, "not a directory"); var h = new Harness(blocked); var reservations = new AtomicInteger();
        h.child.submissionBarrier(NativeSubmissionBinding.barrier(h.parent, h.runtime, "enchant", () -> { reservations.incrementAndGet(); return true; }));
        check(!h.child.prepareNativeConsumptionBoundary(), "失败也须由实际后台写入回执决定");
        if (coalesced) check(h.captureOrdinary(), "普通保存可以合并消费屏障的旧future");
        var latest = h.store.latestSaveCompletion(h.identity); h.queue.runNext();
        rejects(h.child); rejects(h.child);
        check(latest.isCompletedExceptionally() && h.store.latestSaveCompletion(h.identity) == latest
                && reservations.get() == 0 && h.queue.submitted == 1 && h.queue.tasks.isEmpty(), "写入失败后终止，不预约、不重排、不发按钮");
    }

    private static void verifyRestored(Harness h) {
        // 使用全新Store从磁盘恢复，避开原Store的内存快照，验证同一request_key不会在重启后得到新任务身份。
        var decoded = read(h); var snapshot = decoded.tasks().getFirst();
        check(decoded.requestKeys().get(h.requestKey).equals(h.parent.externalId())
                && snapshot.id().equals(h.parent.externalId()) && snapshot.stepIndex() == h.parent.stepIndex()
                && snapshot.steps().get(snapshot.stepIndex()).toJson().equals(h.parent.steps().get(h.parent.stepIndex()).toJson()),
                "磁盘必须同时保存父任务身份、请求键和当前语义步骤");
        var restored = IntentTaskRecord.restored(snapshot.id(), snapshot.planId(), snapshot.goal(), h.identity.key(),
                snapshot.steps(), snapshot.stepIndex(), snapshot.completed(), snapshot.internalPositions(), snapshot.internalAreaProtections(),
                snapshot.attempts(), snapshot.decision(), snapshot.pendingAnswer(), snapshot.terminal(), 100);
        check(NativeSubmissionBinding.operationId(restored, "enchant").equals(NativeSubmissionBinding.operationId(h.parent, "enchant")), "重启必须重新计算出同一附魔预约编号");
    }

    private static IntentStateCodec.Decoded read(Harness h) {
        var loaded = new IntentStateStore().load(h.identity);
        check(loaded.status() == IntentStateStore.Status.LOADED, "独立Store必须读到已完成的检查点文件");
        return IntentStateCodec.decode(loaded.root());
    }

    private static final class Harness {
        final ManualExecutor queue = new ManualExecutor();
        final StateIdentity identity;
        final IntentStateStore store;
        final IntentRuntime runtime;
        final IntentTaskRecord parent;
        final EnchantTaskRecord child;
        final String requestKey = "enchantment-request-" + UUID.randomUUID();
        final Map<String, UUID> requestKeys;

        @SuppressWarnings("unchecked") Harness(Path directory) throws Exception {
            identity = new StateIdentity("e".repeat(64), directory);
            var storeConstructor = IntentStateStore.class.getDeclaredConstructor(Executor.class); storeConstructor.setAccessible(true);
            store = storeConstructor.newInstance(queue);
            var runtimeConstructor = IntentRuntime.class.getDeclaredConstructor(); runtimeConstructor.setAccessible(true); runtime = runtimeConstructor.newInstance();
            field("stateStore").set(runtime, store); field("stateIdentity").set(runtime, identity); field("bodyAttached").set(runtime, true);
            // 即使普通五秒保存时机尚未到来，附魔自己的消费屏障仍须立即排入真实检查点写入。
            field("nextSaveNanos").setLong(runtime, Long.MAX_VALUE);
            Goal goal = Goal.fromJson(JsonParser.parseString("{\"ability\":\"maicraft:enchant\",\"outcome\":\"附魔一本书\","
                    + "\"parameters\":{\"item_id\":\"minecraft:book\",\"max_levels_spent\":1,\"max_lapis\":1}}").getAsJsonObject());
            parent = new IntentTaskRecord(UUID.randomUUID(), null, goal, identity.key());
            ((Map<UUID, IntentTaskRecord>) field("tasks").get(runtime)).put(parent.externalId(), parent);
            requestKeys = (Map<String, UUID>) field("requestKeys").get(runtime); requestKeys.put(requestKey, parent.externalId());
            child = new EnchantTaskRecord("checkpoint-only-test", 1000, ResourceLocation.parse("minecraft:book"), BlockPos.ZERO, 1, 1, 1);
        }
        boolean captureOrdinary() throws Exception {
            var method = IntentRuntime.class.getDeclaredMethod("captureCheckpoint", boolean.class); method.setAccessible(true);
            return (boolean) method.invoke(runtime, true);
        }
        Path file() { return identity.directory().resolve(identity.key() + ".json"); }
    }
    private static Field field(String name) throws Exception { Field value = IntentRuntime.class.getDeclaredField(name); value.setAccessible(true); return value; }
    private static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>(); int submitted;
        @Override public void execute(Runnable worker) { submitted++; tasks.addLast(worker); }
        void runNext() { tasks.removeFirst().run(); }
    }
    private static void rejects(EnchantTaskRecord record) {
        try { record.prepareNativeConsumptionBoundary(); }
        catch (IllegalStateException rejected) { check(rejected.getMessage().startsWith("enchantment_parent_checkpoint_failed"), rejected.getMessage()); return; }
        throw new AssertionError("检查点失败后应明确停止消费");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
