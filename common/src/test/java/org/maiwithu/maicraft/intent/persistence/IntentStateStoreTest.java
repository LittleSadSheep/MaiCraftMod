package org.maiwithu.maicraft.intent.persistence;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;

/** Real file storage and the production mailbox, with worker dispatch held until assertions run. */
public final class IntentStateStoreTest {
    public static void main(String[] args) throws Exception {
        Path workspace = Path.of("").toAbsolutePath().normalize();
        Path directory = Files.createTempDirectory(workspace, "semantic-state-regression-");
        try {
            captureIsDetachedFromTasksAndJson(directory.resolve("capture"));
            repeatedSavesCoalesceAndRestoreLatest(directory.resolve("coalescing"));
            shutdownWaitsForDetachedCheckpoint(directory.resolve("shutdown"));
            shutdownCapturesAttachedCheckpoint(directory.resolve("attached-shutdown"));
            boundedWaitPreservesPendingSave(directory.resolve("wait"));
            identityMailboxIsBounded(directory.resolve("identities"));
            failedDiskWriteCanRetry(directory.resolve("failure"));
            oversizeFilesAndSnapshotsAreRejected(directory.resolve("bounds"));
            System.out.println("IntentStateStoreTest: passed");
        } finally {
            if (!directory.toRealPath().startsWith(workspace.toRealPath())) {
                throw new AssertionError("test cleanup escaped the regression workspace");
            }
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void captureIsDetachedFromTasksAndJson(Path directory) throws Exception {
        ArrayDeque<Runnable> workers = new ArrayDeque<>();
        IntentStateStore store = new IntentStateStore(workers::add);
        StateIdentity identity = identity(directory, 1);
        Goal goal = new Goal("maicraft:wait_for_condition", "retain checkpoint", null,
                "{}", "{}", List.of(), List.of());
        IntentTaskRecord task = new IntentTaskRecord(UUID.randomUUID(), null, goal);
        JsonObject root = IntentStateCodec.encode(identity.key(), List.of(), List.of(task), Map.of(), List.of());
        CompletableFuture<Void> saved = store.saveAsync(identity, root);
        task.steps().clear();
        root.remove("tasks");
        check(!Files.exists(directory), "save submission must not create a directory or write a file");
        var captured = IntentStateCodec.decode(store.load(identity).root());
        check(captured.tasks().size() == 1 && captured.tasks().getFirst().steps().size() == 1,
                "a task or caller-owned JSON mutation escaped into the captured checkpoint");
        runWorker(workers);
        saved.get(5, TimeUnit.SECONDS);
        var disk = IntentStateCodec.decode(new IntentStateStore().load(identity).root());
        check(disk.tasks().getFirst().steps().size() == 1, "disk did not receive the immutable checkpoint");
    }

    private static void repeatedSavesCoalesceAndRestoreLatest(Path directory) throws Exception {
        ArrayDeque<Runnable> workers = new ArrayDeque<>();
        IntentStateStore store = new IntentStateStore(workers::add);
        StateIdentity identity = identity(directory, 2);
        CompletableFuture<Void> first = store.saveAsync(identity, root(identity, 0));
        CompletableFuture<Void> last = first;
        for (int version = 1; version <= 1000; version++) {
            last = store.saveAsync(identity, root(identity, version));
        }
        check(workers.size() == 1 && first.isCancelled(),
                "repeated checkpoints must replace pending work rather than enqueue generations");
        check(store.load(identity).root().get("revision").getAsInt() == 1000,
                "same-process respawn/reconnect must restore the newest accepted version before disk finishes");
        runWorker(workers);
        last.get(5, TimeUnit.SECONDS);
        check(new IntentStateStore().load(identity).root().get("revision").getAsInt() == 1000,
                "an earlier pending snapshot overwrote the newest checkpoint");
    }

    private static void shutdownWaitsForDetachedCheckpoint(Path directory) throws Exception {
        ArrayDeque<Runnable> workers = new ArrayDeque<>();
        IntentStateStore store = new IntentStateStore(workers::add);
        StateIdentity identity = identity(directory, 5), previousWorld = identity(directory, 6);
        store.saveAsync(previousWorld, root(previousWorld, 7));
        store.saveAsync(identity, root(identity, 1));
        IntentRuntime runtime = runtime(store, identity, false);
        // The body is already detached, so shutdown must wait for its captured handoff, not recapture cancelled records.
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Void> shutdown = CompletableFuture.runAsync(() -> {
            entered.countDown(); runtime.shutdownPersistence();
        });
        try {
            check(entered.await(5, TimeUnit.SECONDS), "shutdown worker did not start");
            try {
                shutdown.get(50, TimeUnit.MILLISECONDS);
                throw new AssertionError("shutdown returned before the final checkpoint reached disk");
            } catch (TimeoutException expected) { }
            store.saveAsync(identity, root(identity, 2));
        } finally {
            runWorker(workers);
            shutdown.get(5, TimeUnit.SECONDS);
        }
        check(new IntentStateStore().load(identity).root().get("revision").getAsInt() == 2,
                "shutdown must wait for the replacement receipt rather than the cancelled old generation");
        check(new IntentStateStore().load(previousWorld).root().get("revision").getAsInt() == 7,
                "shutdown must also finish the previous world's queued handoff");
    }

    private static void shutdownCapturesAttachedCheckpoint(Path directory) throws Exception {
        ArrayDeque<Runnable> workers = new ArrayDeque<>();
        CountDownLatch submitted = new CountDownLatch(1);
        IntentStateStore store = new IntentStateStore(worker -> { workers.add(worker); submitted.countDown(); });
        StateIdentity identity = identity(directory, 7);
        IntentRuntime runtime = runtime(store, identity, true);
        CompletableFuture<Void> shutdown = CompletableFuture.runAsync(runtime::shutdownPersistence);
        try {
            check(submitted.await(5, TimeUnit.SECONDS), "shutdown did not capture the attached world's final state");
            check(!shutdown.isDone() && !Files.exists(directory), "queued final capture was treated as a disk receipt");
        } finally {
            runWorker(workers); shutdown.get(5, TimeUnit.SECONDS);
        }
        var loaded = new IntentStateStore().load(identity);
        check(loaded.status() == IntentStateStore.Status.LOADED
                        && IntentStateCodec.decode(loaded.root()).tasks().isEmpty(),
                "the actual runtime shutdown must write its final encoded checkpoint before returning");
    }

    private static void boundedWaitPreservesPendingSave(Path directory) throws Exception {
        ArrayDeque<Runnable> workers = new ArrayDeque<>();
        IntentStateStore store = new IntentStateStore(workers::add);
        check(store.awaitPendingSaves(Duration.ZERO) == IntentStateStore.FlushResult.SAVED, "an empty store needs no wait");
        StateIdentity identity = identity(directory, 8);
        var receipt = store.saveAsync(identity, root(identity, 1));
        var timeout = CompletableFuture.supplyAsync(() -> store.awaitPendingSaves(Duration.ofMillis(10)));
        check(timeout.get(1, TimeUnit.SECONDS) == IntentStateStore.FlushResult.TIMED_OUT,
                "an unavailable writer must not block shutdown beyond the requested wait");
        Thread.currentThread().interrupt();
        try {
            check(store.awaitPendingSaves(Duration.ofSeconds(1)) == IntentStateStore.FlushResult.INTERRUPTED
                            && Thread.currentThread().isInterrupted(), "an interrupted wait must preserve the interrupt flag");
        } finally { Thread.interrupted(); }
        check(!receipt.isDone() && store.hasSnapshot(identity), "timeout and interruption must retain pending writes");
        runWorker(workers);
        check(store.awaitPendingSaves(Duration.ZERO) == IntentStateStore.FlushResult.SAVED
                        && new IntentStateStore().load(identity).root().get("revision").getAsInt() == 1,
                "the same pending write must still complete after an abandoned wait");
        IntentStateStore rejected = new IntentStateStore(worker -> { throw new RejectedExecutionException("fixture"); });
        rejected.saveAsync(identity, root(identity, 2));
        check(rejected.awaitPendingSaves(Duration.ZERO) == IntentStateStore.FlushResult.FAILED,
                "a rejected writer cannot be reported as persisted");
    }

    private static IntentRuntime runtime(IntentStateStore store, StateIdentity identity, boolean attached) throws Exception {
        var constructor = IntentRuntime.class.getDeclaredConstructor(); constructor.setAccessible(true);
        IntentRuntime runtime = constructor.newInstance();
        for (var entry : Map.of("stateStore", (Object) store, "stateIdentity", identity, "bodyAttached", attached).entrySet()) {
            var field = IntentRuntime.class.getDeclaredField(entry.getKey()); field.setAccessible(true);
            field.set(runtime, entry.getValue());
        }
        return runtime;
    }

    private static void identityMailboxIsBounded(Path directory) throws Exception {
        ArrayDeque<Runnable> workers = new ArrayDeque<>();
        IntentStateStore store = new IntentStateStore(workers::add);
        for (int world = 1; world <= 8; world++) {
            StateIdentity identity = identity(directory, world);
            store.saveAsync(identity, root(identity, world));
        }
        StateIdentity overflow = identity(directory, 9);
        try {
            store.saveAsync(overflow, root(overflow, 9));
            throw new AssertionError("unbounded number of unpersisted identities accepted");
        } catch (RejectedExecutionException expected) { }
        StateIdentity first = identity(directory, 1);
        CompletableFuture<Void> replacement = store.saveAsync(first, root(first, 99));
        check(workers.size() == 1, "identity changes scheduled parallel writers");
        runWorker(workers);
        replacement.get(5, TimeUnit.SECONDS);
        for (int world = 1; world <= 8; world++) {
            var loaded = new IntentStateStore().load(identity(directory, world));
            check(loaded.status() == IntentStateStore.Status.LOADED, "identity handoff lost a checkpoint");
        }
        CompletableFuture<Void> admitted = store.saveAsync(overflow, root(overflow, 9));
        runWorker(workers);
        admitted.get(5, TimeUnit.SECONDS);
    }

    private static void failedDiskWriteCanRetry(Path directory) throws Exception {
        Files.createDirectories(directory.getParent());
        Files.writeString(directory, "not a directory");
        StateIdentity identity = identity(directory, 3);
        IntentStateStore store = new IntentStateStore();
        try {
            store.saveAsync(identity, root(identity, 1)).get(5, TimeUnit.SECONDS);
            throw new AssertionError("filesystem failure was reported as a persisted checkpoint");
        } catch (ExecutionException expected) { }
        check(store.hasFailedSave(identity), "runtime cannot detect and retry a failed disk write");
        check(store.awaitPendingSaves(Duration.ZERO) == IntentStateStore.FlushResult.FAILED,
                "shutdown must distinguish a failed disk write from a successful receipt");
        check(store.load(identity).root().get("revision").getAsInt() == 1,
                "disk failure must not discard the in-process respawn checkpoint");
        Files.delete(directory);
        store.saveAsync(identity, root(identity, 2)).get(5, TimeUnit.SECONDS);
        check(!store.hasFailedSave(identity)
                        && new IntentStateStore().load(identity).root().get("revision").getAsInt() == 2,
                "latest checkpoint could not be persisted after storage recovered");
    }

    private static void oversizeFilesAndSnapshotsAreRejected(Path directory) throws Exception {
        Files.createDirectories(directory);
        StateIdentity identity = identity(directory, 4);
        Path file = directory.resolve(identity.key() + ".json");
        try (FileChannel output = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            output.position(IntentStateStore.MAX_BYTES);
            output.write(ByteBuffer.wrap(new byte[] {1}));
        }
        IntentStateStore store = new IntentStateStore();
        check(store.load(identity).status() == IntentStateStore.Status.CORRUPT,
                "oversize state file passed the load boundary");
        JsonObject huge = root(identity, 1);
        huge.addProperty("huge", "x".repeat((int) IntentStateStore.MAX_BYTES));
        try {
            store.saveAsync(identity, huge);
            throw new AssertionError("oversize snapshot entered the bounded mailbox");
        } catch (IOException expected) { }
        check(!store.hasSnapshot(identity), "rejected snapshot was retained");
    }

    private static void runWorker(ArrayDeque<Runnable> workers) throws Exception {
        check(workers.size() == 1, "expected one serial writer");
        CompletableFuture.runAsync(workers.remove()).get(5, TimeUnit.SECONDS);
    }

    private static StateIdentity identity(Path directory, int key) {
        return new StateIdentity(String.format("%064x", key), directory);
    }

    private static JsonObject root(StateIdentity identity, int revision) {
        JsonObject result = new JsonObject();
        result.addProperty("version", IntentStateStore.VERSION);
        result.addProperty("identity_key", identity.key());
        result.addProperty("revision", revision);
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
