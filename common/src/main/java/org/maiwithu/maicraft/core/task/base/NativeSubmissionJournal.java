// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.base;

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

/** 提交原生操作前持久化本次编号；已有记录只禁止重发，不能证明游戏或服务端已接受操作。 */
public class NativeSubmissionJournal {
    // 所有原生机制共用有界写入线程；队列满时停止，不在游戏线程同步磁盘。
    private static final Executor WRITER = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), task -> {
                Thread thread = new Thread(task, "maicraft-native-consumption-journal"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    @FunctionalInterface public interface MarkerWriter { void write(Path file, byte[] contents) throws IOException; }
    private final UUID operationId;
    private final Path file;
    private final byte[] contents;
    private final String failurePrefix;
    private final Executor executor;
    private final MarkerWriter writer;
    private CompletableFuture<Void> preparation;

    public NativeSubmissionJournal(StateIdentity identity, UUID operationId, String namespace) {
        this(identity, operationId, namespace, WRITER, NativeSubmissionJournal::writeMarker);
    }
    protected NativeSubmissionJournal(StateIdentity identity, UUID operationId, String namespace, Executor executor) {
        this(identity, operationId, namespace, executor, NativeSubmissionJournal::writeMarker);
    }
    protected NativeSubmissionJournal(StateIdentity identity, UUID operationId, String namespace, Executor executor, MarkerWriter writer) {
        Objects.requireNonNull(identity, "world identity"); this.operationId = Objects.requireNonNull(operationId, "operation id");
        if (namespace == null || !namespace.matches("[a-z][a-z0-9-]{0,63}")) throw new IllegalArgumentException("invalid native consumption namespace");
        this.executor = Objects.requireNonNull(executor, "executor"); this.writer = Objects.requireNonNull(writer, "writer");
        // enchant命名空间继续使用原文件路径与诊断前缀，旧版未完成的附魔不会因入口统一而获得第二次消费。
        file = identity.directory().resolve(namespace + "-submissions").resolve(identity.key()).resolve(operationId + ".json");
        failurePrefix = namespace.equals("enchant") ? "enchantment_submission"
                : namespace.equals("chat") ? "chat_submission" : "native_consumption";
        contents = ("{\"version\":1,\"world_key\":\"" + identity.key() + "\",\"operation_id\":\"" + operationId
                + "\",\"status\":\"reserved\"}\n").getBytes(StandardCharsets.UTF_8);
    }

    public synchronized boolean prepare() {
        if (preparation == null) {
            // 同一活实例只排队一次；崩溃后新实例必须重新面对已有文件，不能复用内存中的成功判断。
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
                throw new IllegalStateException(failurePrefix + "_already_reserved: do not resubmit this operation; the marker does not prove an actual submission or success", cause);
            throw new IllegalStateException(failurePrefix + "_reservation_failed: do not retry automatically; any existing marker has been retained", cause);
        }
    }
    /** 仅说明本实例的预留已经同步，等待或失败时均不能放行原生消费。 */
    public synchronized boolean reserved() { return preparation != null && preparation.isDone() && !preparation.isCompletedExceptionally(); }
    public UUID operationId() { return operationId; }

    private static void writeMarker(Path file, byte[] contents) throws IOException {
        Files.createDirectories(file.getParent());
        // 同一编号的竞争只有一个CREATE_NEW能成功；空文件和损坏文件同样保留，不能删除后补发消费。
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(contents); while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }
}
