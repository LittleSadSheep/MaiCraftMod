// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.base;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;
import org.maiwithu.maicraft.intent.persistence.MemoryDatabase;
import org.maiwithu.maicraft.intent.persistence.LegacyMemoryFiles;

/** 提交原生操作前持久化本次编号；已有记录只禁止重发，不能证明游戏或服务端已接受操作。 */
public class NativeSubmissionJournal {
    // 所有原生机制共用有界写入线程；队列满时停止，不在游戏线程同步磁盘。
    private static final Executor WRITER = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), task -> {
                Thread thread = new Thread(task, "maicraft-native-submission-journal"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    @FunctionalInterface public interface MarkerWriter { void write(Path file, byte[] contents) throws IOException; }
    private final UUID operationId;
    private final Path file;
    private final byte[] contents;
    private final String failurePrefix;
    private final Executor executor;
    private final MarkerWriter writer;
    private CompletableFuture<Void> preparation;
    // 只有预约唯一键冲突才表示已经预约；数据库目录被普通文件占用属于存储失败。
    private static final class AlreadyReserved extends IOException {
        AlreadyReserved(String operation) { super(operation); }
    }

    public NativeSubmissionJournal(StateIdentity identity, UUID operationId, String namespace) {
        this(identity, operationId, namespace, WRITER, null);
    }
    protected NativeSubmissionJournal(StateIdentity identity, UUID operationId, String namespace, Executor executor) {
        this(identity, operationId, namespace, executor, null);
    }
    protected NativeSubmissionJournal(StateIdentity identity, UUID operationId, String namespace, Executor executor, MarkerWriter writer) {
        Objects.requireNonNull(identity, "world identity"); this.operationId = Objects.requireNonNull(operationId, "operation id");
        if (namespace == null || !namespace.matches("[a-z][a-z0-9-]{0,63}")) throw new IllegalArgumentException("invalid native submission namespace");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.writer = writer == null ? (file, contents) -> writeMarker(identity, namespace, operationId, file, contents) : writer;
        // 保留旧预约路径用于迁移；附魔、聊天等仍使用原诊断前缀，不能因改存数据库获得第二次消费。
        file = identity.directory().resolve(namespace + "-submissions").resolve(identity.key()).resolve(operationId + ".json");
        failurePrefix = namespace.equals("enchant") ? "enchantment_submission"
                : namespace.equals("chat") ? "chat_submission" : "native_consumption";
        contents = ("{\"version\":1,\"world_key\":\"" + identity.key() + "\",\"operation_id\":\"" + operationId
                + "\",\"status\":\"reserved\"}\n").getBytes(StandardCharsets.UTF_8);
    }

    public synchronized boolean prepare() {
        if (preparation == null) {
            // 同一活实例只排队一次；崩溃后新实例必须核对数据库和旧预约，不能复用内存中的成功判断。
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
            if (cause instanceof AlreadyReserved)
                throw new IllegalStateException(failurePrefix + "_already_reserved: do not resubmit this operation; the marker does not prove an actual submission or success", cause);
            throw new IllegalStateException(failurePrefix + "_reservation_failed: do not retry automatically; any existing marker has been retained", cause);
        }
    }
    /** 仅说明本实例的预留已经同步，等待或失败时均不能放行原生消费。 */
    public synchronized boolean reserved() { return preparation != null && preparation.isDone() && !preparation.isCompletedExceptionally(); }
    public UUID operationId() { return operationId; }

    private static void writeMarker(StateIdentity identity, String namespace, UUID operationId, Path file, byte[] contents) throws IOException {
        var database = new MemoryDatabase(identity.databaseFile());
        String scope = identity.scope() + "/native-submissions/" + namespace, key = operationId.toString();
        if (database.containsRecord(scope, identity.key(), key)) throw new AlreadyReserved(key);
        // 旧标记即使是空文件或截断 JSON 也意味着可能提交过；原文入库后仍拒绝再次消费。
        String legacy = LegacyMemoryFiles.read(file, 1_048_576);
        if (legacy != null) {
            database.writeRecord(scope, identity.key(), key, legacy, true);
            throw new AlreadyReserved(key);
        }
        // 唯一键插入与同步提交决定唯一赢家，所有命名空间都不再创建新的 JSON 预约文件。
        if (!database.writeRecord(scope, identity.key(), key, new String(contents, StandardCharsets.UTF_8), true))
            throw new AlreadyReserved(key);
    }
}
