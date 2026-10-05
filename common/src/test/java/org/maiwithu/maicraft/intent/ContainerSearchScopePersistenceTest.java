// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.agent.tool.ToolRegistry;
import org.maiwithu.maicraft.core.tools.work.SemanticAcquireTool;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.container.ContainerSearchScope;
import org.maiwithu.maicraft.intent.persistence.IntentStateCodec;
import org.maiwithu.maicraft.intent.persistence.IntentStateStore;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** 用真实 SQLite 检查点和语义父子任务重放暂停、移动、重启；只读验证范围，不实际开箱。 */
public final class ContainerSearchScopePersistenceTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        // 独立回归也登记正式取物工具，经过真实父任务适配与接单，不直接伪造绑定后的子任务。
        var previous = ToolRegistry.remove("acquire_items"); ToolRegistry.register(new SemanticAcquireTool());
        try {
            firstScopeSurvivesPauseMovementAndRestart();
            legacyScopeRemainsUnknown(false);
            legacyScopeRemainsUnknown(true);
            failedCheckpointCannotStartInvestigation();
            scopeFollowsItsOriginalStep();
        } finally {
            ToolRegistry.remove("acquire_items"); if (previous != null) ToolRegistry.register(previous);
        }
        System.out.println("ContainerSearchScopePersistenceTest: passed");
    }

    private static Goal goal() {
        return new Goal("maicraft:acquire_items", "从原范围取一块木板", null,
                "{\"item_id\":\"minecraft:oak_planks\",\"count\":1,\"allowed_sources\":[\"storage\"],\"radius\":8}",
                "{}", List.of(), List.of());
    }

    private static void firstScopeSurvivesPauseMovementAndRestart() throws Exception {
        try (var f = new Fixture()) {
            var original = f.start(); var scope = original.storageScope;
            check(scope.origin().equals(f.world.player.blockPosition()) && scope.radius() == 8, "first call captures the declared range");
            f.parent.pause(2, "scope fixture pause"); f.task.stop(f.world.player, Task.StopReason.PREEMPTED);
            f.world.position(new Vec3(6.5, 1, 3.5)); check(f.parent.resume(), "ordinary pause resumes");
            f.task.tick(f.world.player);
            check(f.child() == original && f.child().storageScope.equals(scope), "ordinary resume retains the same child and origin");
            check(f.writes.size() == 1 && !original.prepareStorageScope(), "investigation waits for the actual checkpoint write");
            check(f.world.blockUses() == 0 && f.world.itemUses() == 0, "pending persistence cannot open a box");
            f.flush(); check(original.prepareStorageScope(), "finished SQLite checkpoint permits bounded investigation");

            // 新运行时从磁盘恢复父任务，再从移动后的身体创建全新取物子任务；旧范围必须覆盖临时新原点。
            field(Level.class, "dimension").set(f.world.level, Level.NETHER);
            f.restore(); var resumed = f.start();
            check(resumed != original && resumed.storageScope.equals(scope), "restart retains original dimension, origin and radius");
            check(!resumed.storageScope.contains(f.world.player), "a different restored dimension cannot replace the original one");
            check(resumed.storageScope.contains(new BlockPos(7, 1, 3)) && !resumed.storageScope.contains(new BlockPos(13, 1, 3)),
                    "the moved player cannot extend the original eight-block sphere");
            check(f.parent.containerSearchScopes().get(0).orElseThrow().equals(scope), "the restored parent owns the same bound");

            // 检查点里的半径是原许可事实，损坏或非法的值必须拒绝，不能通过钳制替换原范围。
            JsonObject invalid = f.snapshot(); invalid.getAsJsonArray("tasks").get(0).getAsJsonObject()
                    .getAsJsonArray("container_search_scopes").get(0).getAsJsonObject().addProperty("radius", 99);
            try { IntentStateCodec.decode(invalid); throw new AssertionError("invalid persisted radius was accepted"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    private static void legacyScopeRemainsUnknown(boolean alreadyCarried) throws Exception {
        try (var f = new Fixture()) {
            JsonObject legacy = f.snapshot(); legacy.getAsJsonArray("tasks").get(0).getAsJsonObject().remove("container_search_scopes");
            f.save(legacy); f.world.position(new Vec3(6.5, 1, 3.5)); f.restore();
            check(f.parent.containerSearchScopes().get(0).isEmpty(), "legacy checkpoint reports unknown scope");
            // 未知状态再保存、再次恢复仍为未知；不能因为文件升级过一遍就重新授予当前位置附近的访问范围。
            f.save(f.snapshot()); f.restore();
            if (alreadyCarried) f.world.inventory.setItem(0, new ItemStack(Items.OAK_PLANKS));
            var child = f.start();
            check(child.storageScopeUnknown && child.storageScope == null, "restoration never adopts the temporary new origin");
            f.task.tick(f.world.player);
            // 子任务回执由语义父任务收取，核对父任务保存并交付的实际结果，而非内部任务单的空缓存。
            String json = alreadyCarried ? f.parent.stepResults().getLast().resultJson() : f.parent.attempts().getLast().resultJson();
            var result = JsonParser.parseString(json).getAsJsonObject(); var data = result.getAsJsonObject("data");
            check(data.getAsJsonObject("container_search_scope").get("status").getAsString().equals("unknown"),
                    "the default result states unknown scope without fabricated coordinates");
            check(!data.getAsJsonObject("container_search_scope").has("origin"), "unknown has no invented origin");
            if (alreadyCarried) {
                check(result.get("success").getAsBoolean() && data.get("observed_final_count").getAsInt() == 1
                        && data.getAsJsonArray("attempts").isEmpty() && !data.get("effects_observed").getAsBoolean(),
                        "satisfied inventory completes without another storage request or claimed transfer");
            } else {
                check(!result.get("success").getAsBoolean() && data.get("failure_code").getAsString().equals("container_search_scope_unknown"),
                        "missing stock cannot trigger a search with an unknown original bound");
            }
            check(f.writes.isEmpty() && f.world.blockUses() == 0 && f.world.itemUses() == 0,
                    "legacy completion or refusal performs no new native storage action");
        }
    }

    private static void scopeFollowsItsOriginalStep() {
        var parent = new IntentTaskRecord(UUID.randomUUID(), null, goal());
        var scope = new ContainerSearchScope("minecraft:overworld", new BlockPos(500, 78, 106), 32);
        parent.retainContainerSearchScope(scope);
        var wait = new Goal("maicraft:wait_for_condition", "等待前置", null, "{\"after_s\":0}", "{}", List.of(), List.of());
        parent.insertRecovery(wait);
        check(!parent.containerSearchScopes().containsKey(0) && parent.containerSearchScopes().get(1).orElseThrow().equals(scope),
                "inserting recovery does not transfer the old bound to the new step");
        parent.replaceCurrent(new Goal("maicraft:sequence", "两步前置", null, "{}", "{}", List.of(), List.of(wait, wait)));
        var root = IntentStateCodec.encode("a".repeat(64), List.of(), List.of(parent), Map.of(), List.of());
        var decoded = IntentStateCodec.decode(root).tasks().getFirst();
        check(decoded.containerSearchScopes().size() == 1 && decoded.containerSearchScopes().get(2).orElseThrow().equals(scope),
                "replacement shifts the original step's bound through persistence");
    }

    private static void failedCheckpointCannotStartInvestigation() throws Exception {
        // 用临时文件占住检查点目录，使真实 SQLite 写入失败；不能在保存失败后仍前往第一只箱子。
        try (var f = new Fixture(true)) {
            f.start(); f.task.tick(f.world.player); f.flush(); f.task.tick(f.world.player);
            var result = JsonParser.parseString(f.parent.attempts().getLast().resultJson()).getAsJsonObject();
            check(result.getAsJsonObject("data").get("failure_code").getAsString().equals("container_search_scope_checkpoint_failed")
                    && f.world.blockUses() == 0 && f.world.itemUses() == 0, "failed scope persistence stops before native storage access");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final InteractionWorldTestHarness world = new InteractionWorldTestHarness();
        final ArrayDeque<Runnable> writes = new ArrayDeque<>();
        final StateIdentity identity;
        IntentRuntime runtime;
        IntentStateStore store;
        IntentTaskRecord parent;
        IntentTask task;

        Fixture() throws Exception { this(false); }
        @SuppressWarnings("unchecked") Fixture(boolean blocked) throws Exception {
            identity = new StateIdentity("a".repeat(64), blocked ? Files.createTempFile("container-scope-blocked-", ".tmp")
                    : Files.createTempDirectory("container-scope-checkpoint-"));
            newRuntime(); parent = new IntentTaskRecord(UUID.randomUUID(), null, goal(), identity.key());
            ((Map<UUID, IntentTaskRecord>) field(IntentRuntime.class, "tasks").get(runtime)).put(parent.externalId(), parent);
        }
        private void newRuntime() throws Exception {
            var constructor = IntentRuntime.class.getDeclaredConstructor(); constructor.setAccessible(true); runtime = constructor.newInstance();
            var storage = IntentStateStore.class.getDeclaredConstructor(Executor.class); storage.setAccessible(true);
            store = storage.newInstance((Executor) writes::addLast);
            field(IntentRuntime.class, "stateStore").set(runtime, store);
            field(IntentRuntime.class, "stateIdentity").set(runtime, identity);
            field(IntentRuntime.class, "bodyAttached").set(runtime, true);
        }
        SemanticAcquireTaskRecord start() throws Exception {
            task = new IntentTask(world.player, parent, runtime);
            TaskState state = task.tick(world.player);
            check(state == TaskState.RUNNING, "parent creates the acquisition child before native work: " + (state == TaskState.RUNNING ? "running" : task.result(state).toJson()));
            return child();
        }
        SemanticAcquireTaskRecord child() throws Exception { return (SemanticAcquireTaskRecord) field(IntentTask.class, "childRecord").get(task); }
        JsonObject snapshot() { return IntentStateCodec.encode(identity.key(), List.of(), List.of(parent), Map.of(), List.of()); }
        void save(JsonObject root) throws Exception { var saved = store.saveAsync(identity, root); flush(); saved.join(); }
        void flush() { while (!writes.isEmpty()) writes.removeFirst().run(); }
        void restore() throws Exception {
            UUID id = parent.externalId(); newRuntime();
            var restore = IntentRuntime.class.getDeclaredMethod("restoreBound", long.class, String.class); restore.setAccessible(true); restore.invoke(runtime, 100L, "session_start");
            runtime.requireRecoveredState(); parent = runtime.task(id);
            check(parent != null && parent.restoredDetached() && parent.resume(), "disk-restored parent can resume explicitly");
            runtime.restoredTaskAttached(parent);
        }
        @Override public void close() throws Exception { world.close(); }
    }
    private static Field field(Class<?> type, String name) throws Exception { var field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
