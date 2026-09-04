package org.maiwithu.maicraft.intent.persistence;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentTaskRecord;

/** Real file storage and the production mailbox, with worker dispatch held until assertions run. */
public final class IntentStateStoreTest {
    public static void main(String[] args) throws Exception {
        Path workspace = Path.of("").toAbsolutePath().normalize();
        Path directory = Files.createTempDirectory(workspace, "semantic-state-regression-");
        try {
            captureIsDetachedFromTasksAndJson(directory.resolve("capture"));
            repeatedSavesCoalesceAndRestoreLatest(directory.resolve("coalescing"));
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
