package org.maiwithu.maicraft.core.task.explore;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** 游戏线程只交付观察副本，后台批量落盘；写盘失败保留待写事实，不能将它们冒称已保存。 */
public final class ExplorationJournal {
    @FunctionalInterface public interface Sink { void save(List<ExplorationFinding> findings) throws IOException; }
    private final Sink sink;
    private final Executor executor;
    private final Consumer<ExplorationJournal> drained;
    private final Map<String, ExplorationFinding> pending = new LinkedHashMap<>();
    private final Map<String, Boolean> seen = new HashMap<>();
    private boolean saving, closed;
    private String failure;

    public ExplorationJournal(Sink sink, Executor executor, Consumer<ExplorationJournal> drained) {
        this.sink = sink; this.executor = executor; this.drained = drained;
    }

    /** 同次跑图的区域只记一次，真正走到此前远望的地点时再升级到访事实。 */
    public synchronized boolean needs(String id, boolean visited) {
        return !seen.containsKey(id) || visited && !seen.get(id);
    }

    public synchronized void observe(ExplorationFinding finding) {
        if (!needs(finding.id(), finding.visited())) return;
        seen.put(finding.id(), finding.visited());
        pending.merge(finding.id(), finding, ExplorationFinding::merge);
    }

    public synchronized void flush() {
        if (saving || pending.isEmpty()) return;
        List<ExplorationFinding> batch = List.copyOf(pending.values());
        saving = true;
        try {
            executor.execute(() -> {
                String error = null;
                try { sink.save(batch); }
                catch (IOException | RuntimeException failed) { error = failed.getMessage(); if (error == null) error = failed.getClass().getSimpleName(); }
                synchronized (this) {
                    saving = false; failure = error;
                    if (error == null) for (var finding : batch) pending.remove(finding.id(), finding);
                    // 关闭期间追加的最后一批也要落盘；失败则等待下次查询重试，避免后台无休止空转。
                    if (closed && error == null && !pending.isEmpty()) flush();
                    if (closed && pending.isEmpty()) drained.accept(this);
                }
            });
        } catch (RuntimeException rejected) { saving = false; failure = rejected.getMessage(); }
    }

    public synchronized void close() { closed = true; flush(); if (!saving && pending.isEmpty()) drained.accept(this); }
    public synchronized int pendingCount() { return pending.size(); }
    public synchronized String failure() { return failure; }
    public synchronized List<ExplorationFinding> pendingFindings() { return List.copyOf(pending.values()); }

    public synchronized Map<String, Object> receipt() {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("observed_places", seen.size()); receipt.put("pending_places", pending.size());
        receipt.put("save_state", failure != null ? "failed_retained_for_retry" : pending.isEmpty() ? "saved" : "pending");
        if (failure != null) receipt.put("save_error", failure);
        receipt.put("query", Map.of("view", "exploration", "focus", "discoveries"));
        return receipt;
    }
}
