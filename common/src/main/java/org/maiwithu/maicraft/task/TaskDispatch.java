package org.maiwithu.maicraft.task;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;

import java.util.Objects;
import java.util.function.Consumer;

/** Direct entry points into the single local-player task slots. */
public final class TaskDispatch {

    private static Capture activeCapture;

    private TaskDispatch() {}

    public static ToolContext ctx(String toolCallId, LocalPlayer player) {
        return new ToolContext(toolCallId, player.level().getGameTime());
    }

    /**
     * 仅在 invocation 执行期间，截获内部工具提交的任务记录，交给业务父任务保存为子任务。
     * 截获的记录不会进入全局任务槽，因此不会把父任务替换掉；不允许嵌套截获或一次提交多个记录。
     * 调用结束后必须清掉 activeCapture，避免后续无关工具的任务也被误收走。
     */
    public static void captureNext(Consumer<TaskRecord> sink, Runnable invocation) {
        requireClientThread();
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(invocation, "invocation");
        if (activeCapture != null) {
            throw new IllegalStateException("nested task capture is not supported");
        }
        Capture capture = new Capture(sink);
        activeCapture = capture;
        try {
            invocation.run();
        } finally {
            activeCapture = null;
        }
    }

    /** Submit a bounded synchronous body task. Its tool call completes at terminal settlement. */
    public static void runSync(LocalPlayer player, TaskRecord record, Consumer<String> reply) {
        requireClientThread();
        if (capture(record)) {
            return;
        }
        CompanionTickDispatcher.submitSync(player, record);
    }

    /**
     * Replace the current task. Internal tool calls remain parked until this
     * record reaches a terminal state and is delivered locally exactly once.
     */
    public static void setTask(LocalPlayer player, TaskRecord record, JsonObject args,
                               Consumer<String> reply) {
        requireClientThread();
        if (capture(record)) {
            return;
        }
        record.markAsync();
        CompanionTickDispatcher.submitCurrent(player, record);
    }

    private static boolean capture(TaskRecord record) {
        Capture capture = activeCapture;
        if (capture == null) {
            return false;
        }
        capture.accept(record);
        return true;
    }

    private static void requireClientThread() {
        if (!Minecraft.getInstance().isSameThread()) {
            throw new IllegalStateException("task dispatch is client-thread only");
        }
    }

    private static final class Capture {
        private final Consumer<TaskRecord> sink;
        private boolean accepted;

        private Capture(Consumer<TaskRecord> sink) {
            this.sink = sink;
        }

        private void accept(TaskRecord record) {
            if (accepted) {
                throw new IllegalStateException("captured invocation emitted more than one task");
            }
            accepted = true;
            sink.accept(record);
        }
    }
}
