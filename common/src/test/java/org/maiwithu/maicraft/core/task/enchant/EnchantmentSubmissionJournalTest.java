// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.enchant;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;

/** 使用真实临时文件检查附魔提交预留；没有玩家、物品或经验操作，存在记录也不当成已附魔证据。 */
public final class EnchantmentSubmissionJournalTest {
    private static final String WORLD = "e".repeat(64);
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("enchantment-submission-journal-");
        durableAndIdempotent(directory); existingMarkers(directory); concurrentReservations(directory);
        partialWriteFailure(directory); blockedPathAndRejectedQueue(directory); separateWorlds(directory);
        System.out.println("EnchantmentSubmissionJournalTest: durable reservation, restart, races and failure boundaries passed");
    }

    private static void durableAndIdempotent(Path directory) throws Exception {
        var identity = new StateIdentity(WORLD, directory); UUID id = UUID.randomUUID(); var queue = new ManualExecutor();
        var journal = new EnchantmentSubmissionJournal(identity, id, queue); Path file = marker(identity, id);
        check(journal.operationId().equals(id) && !journal.reserved(), "新操作编号尚未持久化，不能放行");
        check(!journal.prepare() && !journal.prepare() && queue.submitted == 1 && !Files.exists(file), "首轮只排队，游戏线程不写文件");
        queue.runNext();
        check(journal.reserved() && journal.prepare(), "真实写入并同步完成后才允许上层继续");
        byte[] before = Files.readAllBytes(file); var modified = Files.getLastModifiedTime(file);
        var document = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        check(document.keySet().equals(Set.of("version", "world_key", "operation_id", "status"))
                && document.get("version").getAsInt() == 1 && document.get("world_key").getAsString().equals(WORLD)
                && document.get("operation_id").getAsString().equals(id.toString())
                && document.get("status").getAsString().equals("reserved"), "文件只保存固定预留字段");
        check(journal.prepare() && journal.prepare() && queue.submitted == 1
                && Arrays.equals(before, Files.readAllBytes(file)) && modified.equals(Files.getLastModifiedTime(file)), "同一活实例重复查询不得改写记录");
        // 模拟重连后构造新的任务对象：旧标记无论按钮是否来得及发出，都禁止再次预留。
        var restartQueue = new ManualExecutor(); var restarted = new EnchantmentSubmissionJournal(identity, id, restartQueue);
        check(!restarted.prepare(), "重启检查也在后台完成"); restartQueue.runNext();
        rejects(restarted, "enchantment_submission_already_reserved");
        check(!restarted.reserved() && Arrays.equals(before, Files.readAllBytes(file)), "重连拒绝不得覆盖原记录");
    }

    private static void existingMarkers(Path directory) throws Exception {
        var identity = new StateIdentity(WORLD, directory);
        // 空文件或截断JSON都可能来自写入途中崩溃；不能解析失败后删除并重发附魔。
        for (String contents : new String[]{"", "{\"version\":"}) {
            UUID id = UUID.randomUUID(); Path file = marker(identity, id); Files.createDirectories(file.getParent());
            Files.writeString(file, contents, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            var queue = new ManualExecutor(); var journal = new EnchantmentSubmissionJournal(identity, id, queue);
            check(!journal.prepare(), "已有标记在后台核对"); queue.runNext(); rejects(journal, "enchantment_submission_already_reserved");
            check(!journal.reserved() && Files.readString(file).equals(contents), "损坏标记必须原样保留且不放行");
        }
    }

    private static void concurrentReservations(Path directory) throws Exception {
        var identity = new StateIdentity(WORLD, directory); UUID id = UUID.randomUUID();
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1); var finished = new CountDownLatch(2);
        var workers = Executors.newFixedThreadPool(2);
        try {
            // 同时释放两个真实文件写入，让CREATE_NEW决定唯一赢家；不用睡眠猜测线程时序。
            Executor concurrent = task -> workers.execute(() -> {
                ready.countDown();
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("并发写入未释放");
                    task.run();
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { finished.countDown(); }
            });
            var first = new EnchantmentSubmissionJournal(identity, id, concurrent);
            var second = new EnchantmentSubmissionJournal(identity, id, concurrent);
            check(!first.prepare() && !second.prepare(), "两个候选必须先等待写入");
            check(ready.await(10, TimeUnit.SECONDS), "两个工作线程均已就绪"); start.countDown();
            check(finished.await(10, TimeUnit.SECONDS), "两个文件写入均已结束");
            int winners = 0;
            for (var journal : new EnchantmentSubmissionJournal[]{first, second}) {
                try { if (journal.prepare()) winners++; }
                catch (IllegalStateException rejected) { check(rejected.getMessage().startsWith("enchantment_submission_already_reserved"), "竞争失败必须因已有记录拒绝"); }
            }
            check(winners == 1 && first.reserved() != second.reserved(), "同一操作最多一个实例获准提交");
            check(JsonParser.parseString(Files.readString(marker(identity, id))).getAsJsonObject()
                    .get("operation_id").getAsString().equals(id.toString()), "竞争后保留完整的唯一记录");
        } finally { start.countDown(); workers.shutdownNow(); }
    }

    private static void partialWriteFailure(Path directory) throws Exception {
        var identity = new StateIdentity(WORLD, directory); UUID id = UUID.randomUUID(); var queue = new ManualExecutor();
        var attempts = new AtomicInteger();
        var journal = new EnchantmentSubmissionJournal(identity, id, queue, (file, contents) -> {
            attempts.incrementAndGet(); Files.createDirectories(file.getParent());
            Files.writeString(file, "{", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            throw new IOException("simulated interrupted reservation write");
        });
        check(!journal.prepare(), "失败写入也不得在排队阶段放行"); queue.runNext();
        rejects(journal, "enchantment_submission_reservation_failed"); rejects(journal, "enchantment_submission_reservation_failed");
        check(!journal.reserved() && attempts.get() == 1 && queue.submitted == 1
                && Files.readString(marker(identity, id)).equals("{"), "写入失败永久保留本实例失败与可能存在的标记");
        var restartedQueue = new ManualExecutor(); var restarted = new EnchantmentSubmissionJournal(identity, id, restartedQueue);
        check(!restarted.prepare(), "重启等待检查中断标记"); restartedQueue.runNext();
        rejects(restarted, "enchantment_submission_already_reserved");
    }

    private static void blockedPathAndRejectedQueue(Path directory) throws Exception {
        // 真实文件占据本应是目录的位置，强制文件系统在创建标记前失败；本实例仍不自动重排。
        Path blocked = directory.resolve("not-a-directory"); Files.writeString(blocked, "occupied");
        var queue = new ManualExecutor();
        var failed = new EnchantmentSubmissionJournal(new StateIdentity(WORLD, blocked), UUID.randomUUID(), queue);
        check(!failed.prepare(), "路径失败仍先排队"); queue.runNext();
        rejects(failed, "enchantment_submission_reservation_failed"); rejects(failed, "enchantment_submission_reservation_failed");
        check(!failed.reserved() && queue.submitted == 1, "文件系统失败不得放行或重排");
        var attempts = new AtomicInteger();
        var rejected = new EnchantmentSubmissionJournal(new StateIdentity(WORLD, directory), UUID.randomUUID(), task -> {
            attempts.incrementAndGet(); throw new RejectedExecutionException("bounded writer queue full");
        });
        rejects(rejected, "enchantment_submission_reservation_failed"); rejects(rejected, "enchantment_submission_reservation_failed");
        check(!rejected.reserved() && attempts.get() == 1, "队列满时停止，不退回游戏线程写盘或每刻重试");
    }

    private static void separateWorlds(Path directory) throws Exception {
        // 同一稳定编号在不同世界各自记账，不让一个存档的消费边界冒充另一个存档已经附魔。
        UUID id = UUID.randomUUID(); var queue = new ManualExecutor();
        for (String key : new String[]{WORLD, "f".repeat(64)}) {
            var identity = new StateIdentity(key, directory); var journal = new EnchantmentSubmissionJournal(identity, id, queue);
            check(!journal.prepare(), "各世界独立预留"); queue.runNext(); check(journal.prepare(), "另一世界可建立独立标记");
            check(Files.exists(marker(identity, id)), "记录必须归入当前世界哈希目录");
        }
    }

    private static Path marker(StateIdentity identity, UUID id) {
        return identity.directory().resolve("enchant-submissions").resolve(identity.key()).resolve(id + ".json");
    }
    private static void rejects(EnchantmentSubmissionJournal journal, String prefix) {
        try { journal.prepare(); }
        catch (IllegalStateException rejected) { check(rejected.getMessage().startsWith(prefix), rejected.getMessage()); return; }
        throw new AssertionError("预留失败后不能放行或继续等待: " + prefix);
    }
    private static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>(); int submitted;
        @Override public void execute(Runnable task) { submitted++; tasks.addLast(task); }
        void runNext() { tasks.removeFirst().run(); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
