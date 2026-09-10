// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ponder;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 推进一份教程，按作者关键帧或旁白分章，保留章节结束以及结构拆除前的状态；失败时说明不完整，不把已经提取的片段冒充整篇教程。
 */
public final class PonderReplaySession {
    public interface Driver {
        void tick() throws ReflectiveOperationException;
        boolean finished() throws ReflectiveOperationException;
        int time() throws ReflectiveOperationException;
        List<Integer> keyframes() throws ReflectiveOperationException;
        PonderStructureSnapshot snapshot() throws ReflectiveOperationException;
    }
    public record Frame(int tick, String reason, String uri, int blockCount, boolean projectionComplete) {}
    public record Chapter(int index, int startTick, int endTick, List<PonderTranscript.Step> steps, List<Frame> frames) {
        public Chapter { steps = List.copyOf(steps); frames = List.copyOf(frames); }
    }
    private final PonderAccess.Entry entry;
    private final PonderTranscript transcript;
    private final Driver driver;
    private final List<Integer> boundaries;
    private final List<Chapter> chapters = new ArrayList<>();
    private final List<Frame> chapterFrames = new ArrayList<>();
    private PonderStructureSnapshot previous;
    private int previousTime, chapterStart, boundaryIndex, ticks, retained;
    private String status = "running", detail = "";

    public PonderReplaySession(PonderAccess.Entry entry, PonderTranscript transcript, Driver driver) throws ReflectiveOperationException {
        this.entry = entry; this.transcript = transcript; this.driver = driver;
        TreeSet<Integer> times = new TreeSet<>(driver.keyframes()); times.removeIf(value -> value <= 0);
        if (times.isEmpty()) transcript.steps().stream().filter(step -> step.kind().equals("旁白"))
                .map(PonderTranscript.Step::afterDelayTicks).filter(value -> value > 0).forEach(times::add);
        boundaries = List.copyOf(times); previous = driver.snapshot(); previousTime = driver.time();
    }

    public void advance(int maxTicks, long deadlineNanos) {
        for (int i = 0; i < maxTicks && status.equals("running") && System.nanoTime() < deadlineNanos; i++) {
            try {
                if (++ticks > 12000) throw new IllegalStateException("Replay exceeded 12000 ticks");
                driver.tick(); PonderStructureSnapshot current = driver.snapshot(); int time = driver.time();
                if (previous.losesStructureTo(current)) retain(previous, previousTime, "before_removal_or_replacement");
                while (boundaryIndex < boundaries.size() && time >= boundaries.get(boundaryIndex)) {
                    int boundary = boundaries.get(boundaryIndex++);
                    // The blocking delay has completed in this tick; next chapter instructions start on the next tick.
                    retain(current, time, "chapter_end"); finishChapter(boundary);
                }
                previous = current; previousTime = time;
                if (driver.finished()) {
                    retain(current, time, "scene_end"); finishChapter(Math.max(chapterStart, time) + 1); status = "complete";
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                status = "failed"; detail = reason(failure);
            }
        }
    }

    // 相邻相同结构复用资源，不因仅仅移动位置就逐刻保留；整份回放最多记录九十六次保留事件。
    private void retain(PonderStructureSnapshot snapshot, int time, String reason) {
        var blueprint = snapshot.blueprint(entry, transcript.sceneId());
        String uri = PonderBlueprintStore.put(entry.key(), blueprint);
        if (!chapterFrames.isEmpty() && chapterFrames.getLast().uri().equals(uri)) return;
        if (++retained > 96) throw new IllegalStateException("Replay exceeded 96 retained snapshots");
        chapterFrames.add(new Frame(time, reason, uri, blueprint.getAsJsonArray("blocks").size(),
                blueprint.getAsJsonObject("evidence").get("projection_complete").getAsBoolean()));
    }

    // 按演示起止时间把旁白放入对应章节，同时保存这章已保留的结构链接。
    private void finishChapter(int end) {
        List<PonderTranscript.Step> steps = transcript.steps().stream()
                .filter(step -> step.afterDelayTicks() >= chapterStart && step.afterDelayTicks() < end).toList();
        chapters.add(new Chapter(chapters.size(), chapterStart, end, steps, chapterFrames)); chapterFrames.clear(); chapterStart = end;
    }

    public String status() { return status; }
    private static String reason(Throwable failure) {
        if (failure instanceof java.lang.reflect.InvocationTargetException invocation && invocation.getCause() != null) failure = invocation.getCause();
        return failure.getClass().getSimpleName() + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
    }
    public int ticks() { return ticks; }
    public List<Chapter> chapters() { return List.copyOf(chapters); }
    public String markdown(String uri, int offset) {
        if (offset < 0 || offset > chapters.size() || offset > 0 && offset == chapters.size())
            throw new IllegalArgumentException("Ponder chapter offset out of range");
        StringBuilder text = new StringBuilder("# ").append(transcript.title()).append(" · 章节结构\n\n")
                .append("状态：`").append(status).append("`；已推进 ").append(ticks).append(" tick；已完成 ").append(chapters.size()).append(" 章。\n\n");
        if (status.equals("running")) text.append("提取在后续客户端 tick 中继续；稍后[重新读取](").append(uri).append(")。\n\n");
        if (!detail.isEmpty()) text.append("提取停止：").append(detail).append("。保留的片段不代表完整教程；自定义回调或版本可能不兼容。\n\n");
        int end = Math.min(chapters.size(), offset + 8);
        for (Chapter chapter : chapters.subList(offset, end)) {
            text.append("## 第 ").append(chapter.index() + 1).append(" 章 · ").append(chapter.startTick()).append("–").append(chapter.endTick()).append(" tick\n\n");
            for (PonderTranscript.Step step : chapter.steps()) text.append("- **").append(step.kind()).append("**：").append(step.text())
                    .append(step.focus() == null ? "" : "〔焦点 " + step.focus() + "〕").append("\n");
            for (Frame frame : chapter.frames()) text.append("- [").append(frame.reason()).append(" @ ").append(frame.tick()).append("](")
                    .append(frame.uri()).append(")：").append(frame.blockCount()).append(" 个已投影方块；投影")
                    .append(frame.projectionComplete() ? "完整" : "需处理变换/冲突，不能直接施工").append("。\n");
            text.append("\n");
        }
        if (end < chapters.size()) text.append("[后续章节](").append(uri).append("?offset=").append(end).append(")\n\n");
        return text.append("章节按作者关键帧划分，缺少关键帧时使用旁白起点；边界是演示时间标记。JSON 的 blocks 为可投影的可见布局，完整源方块、变换、NBT 与实体保存在 evidence。NBT 不作为施工配置，转速与产出不能当作实际运行证据。\n").toString();
    }
}
