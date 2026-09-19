// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.enchant;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;

/** 附魔提交前先持久化占用本次操作编号；已有记录只表示禁止重发，不能证明按钮已发出或附魔成功。 */
public final class EnchantmentSubmissionJournal {
    // 文件同步在单独线程完成；队列满时明确失败，绝不退回游戏线程执行fsync或无限堆积待提交操作。
    private static final Executor WRITER = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), task -> {
                Thread thread = new Thread(task, "maicraft-enchantment-journal"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    @FunctionalInterface interface MarkerWriter { void write(Path file, byte[] contents) throws IOException; }
    private final UUID operationId;
    private final Path file;
    private final byte[] contents;
    private final Executor executor;
    private final MarkerWriter writer;
    private CompletableFuture<Void> preparation;

    public EnchantmentSubmissionJournal(StateIdentity identity, UUID operationId) {
        this(identity, operationId, WRITER, EnchantmentSubmissionJournal::writeMarker);
    }

    EnchantmentSubmissionJournal(StateIdentity identity, UUID operationId, Executor executor) {
        this(identity, operationId, executor, EnchantmentSubmissionJournal::writeMarker);
    }

    EnchantmentSubmissionJournal(StateIdentity identity, UUID operationId, Executor executor, MarkerWriter writer) {
        Objects.requireNonNull(identity, "world identity"); this.operationId = Objects.requireNonNull(operationId, "operation id");
        this.executor = Objects.requireNonNull(executor, "executor"); this.writer = Objects.requireNonNull(writer, "writer");
        file = identity.directory().resolve("enchant-submissions").resolve(identity.key()).resolve(operationId + ".json");
        // 世界哈希和稳定操作编号均已限定格式；只写固定预留状态，不带物品NBT、服务器地址或任意任务文本。
        contents = ("{\"version\":1,\"world_key\":\"" + identity.key() + "\",\"operation_id\":\"" + operationId
                + "\",\"status\":\"reserved\"}\n").getBytes(StandardCharsets.UTF_8);
    }

    public synchronized boolean prepare() {
        if (preparation == null) {
            // 首次只排队一次；等待、成功和失败都沿用同一结果，重连后的新实例必须重新面对已有文件。
            try {
                preparation = CompletableFuture.runAsync(() -> {
                    try { writer.write(file, contents); }
                    catch (IOException failure) { throw new CompletionException(failure); }
                }, executor);
            } catch (RuntimeException rejected) { preparation = CompletableFuture.failedFuture(rejected); }
        }
        if (!preparation.isDone()) return false;
        try { preparation.join(); return true; }
        catch (CompletionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof FileAlreadyExistsException)
                throw new IllegalStateException("enchantment_submission_already_reserved: do not resubmit this operation; "
                        + "the existing marker does not prove that a button was sent or applied", cause);
            throw new IllegalStateException("enchantment_submission_reservation_failed: do not retry this operation automatically; "
                    + "any existing marker has been retained", cause);
        }
    }

    /** 仅表示本实例已完成文件预留；等待或失败时均不能放行附魔按钮。 */
    public synchronized boolean reserved() {
        return preparation != null && preparation.isDone() && !preparation.isCompletedExceptionally();
    }
    public UUID operationId() { return operationId; }

    private static void writeMarker(Path file, byte[] contents) throws IOException {
        Files.createDirectories(file.getParent());
        // CREATE_NEW把两个相同操作的竞争交给文件系统裁决；损坏或中断标记也不得覆盖、删除或当成可重试。
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(contents); while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }
}
