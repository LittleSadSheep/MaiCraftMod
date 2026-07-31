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
     * Lexically capture the next task emitted by an internal tool.
     *
     * <p>High-level intent tasks use this to turn an existing tool into a child
     * task. Captured records never touch either global slot, so the parent remains
     * the one scheduler winner. Nested captures and multiple emissions are errors.</p>
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
