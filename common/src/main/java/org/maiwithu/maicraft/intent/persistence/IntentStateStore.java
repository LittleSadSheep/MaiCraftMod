// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent.persistence;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.maiwithu.maicraft.core.Constants;
import java.util.Objects;
import org.maiwithu.maicraft.core.build.BuildingBudgets;

/** 游戏线程先留下完整任务快照，后台提交 SQLite 事务；重连恢复与消费屏障共用真实落盘回执。 */
public final class IntentStateStore {
    public static final int VERSION = 1;
    // 保存与恢复每次读取有效启动配置，不能让较早加载的类把大建筑检查点锁死在旧四 MiB 常量。
    public static int maxBytes() { return BuildingBudgets.current().maxIntentStateBytes(); }
    private static final int MAX_RESIDENT_IDENTITIES = 8;

    // 同一个世界连续要求保存时，只保留最近那份待写文本，不把每个旧版本都排队写一遍。
    private final Map<StateIdentity, PendingSave> latest = new LinkedHashMap<>();
    private final Map<StateIdentity, String> recoveryBlocked = new LinkedHashMap<>();
    private final Executor writerExecutor;
    private boolean workerRunning;

    public IntentStateStore() {
        // 正常保存由守护线程处理；退出入口通过 awaitPendingSaves 有限等待最后写完。
        this(command -> {
            Thread worker = new Thread(command, "maicraft-state-writer");
            worker.setDaemon(true);
            worker.start();
        });
    }

    IntentStateStore(Executor writerExecutor) {
        this.writerExecutor = Objects.requireNonNull(writerExecutor);
    }

    public enum Status { ABSENT, LOADED, CORRUPT, OVER_BUDGET, UNAVAILABLE }
    public enum FlushResult { SAVED, FAILED, TIMED_OUT, INTERRUPTED }

    public record LoadResult(Status status, JsonObject root) {}

    /** 优先恢复本进程最新快照，再读数据库；只有库中没有记录时才迁移旧 JSON。 */
    public LoadResult load(StateIdentity identity) {
        int limit = maxBytes();
        String captured;
        synchronized (latest) {
            PendingSave save = latest.get(identity);
            captured = save == null ? null : save.json;
        }
        // 刚重生或重连时，磁盘可能还没写完；同一进程里仍使用已经留下的最新任务进度。
        if (captured != null) {
            if (captured.length() > limit || captured.getBytes(StandardCharsets.UTF_8).length > limit) return overBudget(identity);
            JsonObject root = JsonParser.parseString(captured).getAsJsonObject(); clearRecoveryBlock(identity);
            return new LoadResult(Status.LOADED, root);
        }
        Path file = file(identity);
        var database = new MemoryDatabase(identity.databaseFile());
        boolean stored = false;
        try {
            String json = database.read(identity.scope(), identity.key(), limit);
            stored = json != null;
            if (!stored) json = LegacyMemoryFiles.read(file, limit);
            if (json == null) { clearRecoveryBlock(identity); return new LoadResult(Status.ABSENT, new JsonObject()); }
            JsonObject root = decode(json, identity);
            if (!stored) {
                // 旧任务完整校验后一次导入；并发导入时重新读取胜出的数据库记录，绝不覆盖更新进度。
                database.write(identity.scope(), identity.key(), json, Map.of(), true);
                stored = true;
                root = decode(database.read(identity.scope(), identity.key(), limit), identity);
            }
            clearRecoveryBlock(identity);
            return new LoadResult(Status.LOADED, root);
        } catch (MemoryDatabase.OverBudget exceeded) { return overBudget(identity); }
        catch (IOException unavailable) {
            // 数据库损坏、锁定或迁移失败不能被当成新世界，否则重启可能重复消费已有任务。
            blockRecovery(identity, "Semantic state recovery is blocked by memory storage at " + identity.databaseFile()
                    + ". The checkpoint is preserved; restore database access and restart the client before starting new tasks.");
            Constants.LOG.warn("Could not restore MaiCraft memory database", unavailable);
            return new LoadResult(Status.UNAVAILABLE, new JsonObject());
        } catch (RuntimeException invalid) {
            if (stored) preserveUnrestored(identity);
            else { quarantine(file); clearRecoveryBlock(identity); }
            Constants.LOG.warn("MaiCraft semantic checkpoint could not be decoded ({})", invalid.getClass().getSimpleName());
            return new LoadResult(Status.CORRUPT, new JsonObject());
        }
    }

    private static JsonObject decode(String json, StateIdentity identity) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("version") || root.get("version").getAsInt() != VERSION
                || !root.has("identity_key") || !identity.key().equals(root.get("identity_key").getAsString()))
            throw new IllegalArgumentException("semantic state version or identity mismatch");
        return root;
    }

    /** 先复制成不会再变的文本，马上返回“稍后写完”的凭据；收到返回值不等于磁盘已经保存成功。 */
    public CompletableFuture<Void> saveAsync(StateIdentity identity, JsonObject root) throws IOException {
        synchronized (latest) { requireRecovered(identity); }
        String json = boundedJson(root);
        PendingSave save = new PendingSave(identity, json);
        synchronized (latest) {
            requireRecovered(identity);
            if (!latest.containsKey(identity) && latest.size() >= MAX_RESIDENT_IDENTITIES) {
                // 最多留八个世界的文本；优先移走已成功写盘的旧世界，不能丢掉还没保存成功的那份。
                var iterator = latest.entrySet().iterator();
                while (iterator.hasNext()) {
                    PendingSave candidate = iterator.next().getValue();
                    if (candidate.completion.isDone() && !candidate.completion.isCompletedExceptionally()) {
                        iterator.remove();
                        break;
                    }
                }
                if (latest.size() >= MAX_RESIDENT_IDENTITIES) {
                    throw new RejectedExecutionException("semantic state writer has too many pending identities");
                }
            }
            PendingSave replaced = latest.put(identity, save);
            // 新版本替代尚未完成的旧版本；真正正在写的旧文本可能仍会先写完，再写新版本。
            if (replaced != null && !replaced.completion.isDone()) replaced.completion.cancel(false);
            if (!workerRunning) {
                workerRunning = true;
                try {
                    writerExecutor.execute(this::writePending);
                } catch (RuntimeException rejected) {
                    workerRunning = false;
                    save.completion.completeExceptionally(rejected);
                }
            }
            latest.notifyAll();
        }
        return save.completion;
    }

    private LoadResult overBudget(StateIdentity identity) {
        // 调低文件预算时保留原任务，并向接单入口提供可以直接返回给玩家的恢复办法。
        blockRecovery(identity, "Semantic state recovery is blocked by maxIntentStateBytes in "
                + BuildingBudgets.CONFIG_PATH
                + ". The checkpoint is preserved; increase this limit and restart the client to restore it before starting new tasks.");
        return new LoadResult(Status.OVER_BUDGET, new JsonObject());
    }

    /** 已读到合法 JSON，但当前模型规则或预算无法恢复时锁住原检查点，不能以空任务覆盖。 */
    public void preserveUnrestored(StateIdentity identity) {
        blockRecovery(identity, "Semantic state recovery is blocked by the current configuration or installed version. "
                + "The checkpoint is preserved; review " + BuildingBudgets.CONFIG_PATH
                + " and restore compatible settings or version, then restart the client before starting new tasks.");
    }

    /** 查询恢复原因不触发保存，也不解锁旧任务；运行时据此拒绝无法留下进度的新施工。 */
    public String recoveryProblem(StateIdentity identity) {
        synchronized (latest) { return recoveryBlocked.get(identity); }
    }

    private void blockRecovery(StateIdentity identity, String problem) {
        synchronized (latest) { recoveryBlocked.put(identity, problem); }
    }
    private void clearRecoveryBlock(StateIdentity identity) {
        synchronized (latest) { recoveryBlocked.remove(identity); }
    }
    private void requireRecovered(StateIdentity identity) throws IOException {
        // 仅提高数字不能解除覆盖保护；先读取并恢复原任务，再让当前身体继续提交新的检查点。
        String problem = recoveryBlocked.get(identity);
        if (problem != null) throw new IOException(problem);
    }

    /**
     * 等待各世界已接收的最新文本写完；合并替换时跟随新版本，共用同一个等待期限。
     * 等待会释放邮箱锁，超时或中断不会取消写盘，也不关闭后续重连仍要使用的存储器。
     */
    public FlushResult awaitPendingSaves(Duration timeout) {
        if (timeout.isNegative()) throw new IllegalArgumentException("negative checkpoint wait");
        long budget = timeout.toNanos(), started = System.nanoTime();
        synchronized (latest) {
            while (true) {
                boolean pending = false, failed = false;
                for (PendingSave save : latest.values()) {
                    pending |= !save.completion.isDone();
                    failed |= save.completion.isCompletedExceptionally();
                }
                if (!pending) return failed ? FlushResult.FAILED : FlushResult.SAVED;
                long remaining = budget - (System.nanoTime() - started);
                if (remaining <= 0) return FlushResult.TIMED_OUT;
                try { TimeUnit.NANOSECONDS.timedWait(latest, remaining); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return FlushResult.INTERRUPTED;
                }
            }
        }
    }

    public boolean hasSnapshot(StateIdentity identity) {
        synchronized (latest) { return latest.containsKey(identity); }
    }

    /** 消费屏障跟随同世界被合并后的写盘回执；拿到回执仍不代表保存完成，也不触发新写入。 */
    public CompletableFuture<Void> latestSaveCompletion(StateIdentity identity) {
        synchronized (latest) {
            PendingSave save = latest.get(identity); return save == null ? null : save.completion;
        }
    }

    /** 告诉运行时最近保存有没有失败，由正常保存周期重试，避免后台不断重复写盘报错。 */
    public boolean hasFailedSave(StateIdentity identity) {
        synchronized (latest) {
            PendingSave save = latest.get(identity);
            return save != null && save.completion.isCompletedExceptionally();
        }
    }

    private void writePending() {
        // 一次只取一份待写文本；取出后在锁外写盘，让游戏线程仍能提交更新版本。
        while (true) {
            PendingSave save = null;
            synchronized (latest) {
                for (PendingSave candidate : latest.values()) {
                    if (candidate.pending) {
                        candidate.pending = false;
                        save = candidate;
                        break;
                    }
                }
                if (save == null) {
                    workerRunning = false;
                    return;
                }
            }
            try {
                new MemoryDatabase(save.identity.databaseFile()).write(save.identity.scope(), save.identity.key(),
                        save.json, Map.of(), false);
                save.completion.complete(null);
            } catch (IOException | RuntimeException failure) {
                save.completion.completeExceptionally(failure);
                Constants.LOG.warn("Could not write MaiCraft semantic checkpoint ({})",
                        failure.getClass().getSimpleName());
            } finally {
                synchronized (latest) { latest.notifyAll(); }
            }
        }
    }

    private static String boundedJson(JsonObject root) throws IOException {
        // 中文在 UTF-8 中可能占多个字节，既检查字符数，也检查实际写入字节数。
        String json = root.toString();
        int limit = maxBytes();
        if (json.length() > limit || json.getBytes(StandardCharsets.UTF_8).length > limit) {
            throw new IOException("semantic state exceeds " + limit + " bytes");
        }
        return json;
    }

    private static final class PendingSave {
        private final StateIdentity identity;
        private final String json;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private boolean pending = true;

        private PendingSave(StateIdentity identity, String json) {
            this.identity = identity;
            this.json = json;
        }
    }

    private static Path file(StateIdentity identity) {
        return identity.directory().resolve(identity.key() + ".json");
    }

    private static void quarantine(Path file) {
        // 坏文件加时间戳和 .corrupt 后缀，留下排错材料；如果改名也失败，就写日志说明。
        if (!Files.exists(file)) return;
        String stamp = Long.toString(Instant.now().toEpochMilli());
        Path corrupt = file.resolveSibling(file.getFileName() + "." + stamp + ".corrupt");
        try {
            Files.move(file, corrupt, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException quarantineFailure) {
            Constants.LOG.warn("Could not quarantine invalid MaiCraft semantic state",
                    quarantineFailure);
        }
    }
}
