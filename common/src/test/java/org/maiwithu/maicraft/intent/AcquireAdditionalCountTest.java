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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.agent.tool.ToolRegistry;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.tools.work.SemanticAcquireTool;
import org.maiwithu.maicraft.intent.persistence.IntentStateCodec;
import org.maiwithu.maicraft.intent.persistence.IntentStateStore;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;
import org.maiwithu.maicraft.task.TaskState;

/** 公开取物的 count 是“再拿几件”：已带物品不算新获取，起始数随暂停、重试和重启沿用，内部组合仍给最终合计数。 */
public final class AcquireAdditionalCountTest {
    private static final ResourceLocation OAK = ResourceLocation.parse("minecraft:oak_planks");
    private static final ResourceLocation BIRCH = ResourceLocation.parse("minecraft:birch_planks");

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        // 走正式取物工具与语义父任务，不直接伪造绑定后的子任务。
        var previous = ToolRegistry.remove("acquire_items"); ToolRegistry.register(new SemanticAcquireTool());
        try {
            carriedItemsAreNotNewAcquisition();
            baselineSurvivesRestart();
            retryKeepsBaselineAndWidensFamily();
            baselineFollowsItsOriginalStep();
            legacyCheckpointKeepsFinalCount();
            internalCraftKeepsFinalCount();
        } finally {
            ToolRegistry.remove("acquire_items"); if (previous != null) ToolRegistry.register(previous);
        }
        System.out.println("AcquireAdditionalCountTest: passed");
    }

    private static Goal acquire() {
        return new Goal("maicraft:acquire_items", "再拿一块橡木板", null,
                "{\"item_id\":\"minecraft:oak_planks\",\"count\":1,\"allowed_sources\":[\"inventory\"]}", "{}", List.of(), List.of());
    }

    private static void carriedItemsAreNotNewAcquisition() throws Exception {
        // 已带一块橡木板时再要一块：目标是合计两块，不能因背包已有一块就零动作完成。
        try (var f = new Fixture(acquire())) {
            f.world.inventory.setItem(0, new ItemStack(Items.OAK_PLANKS));
            var child = f.start();
            check(child.additionalCount == 1 && child.baselineCount() == 1 && child.count == 2,
                    "carried planks become the baseline, not the acquired count");
            // 背包真的多出一块后才完成；回执同时给出请求件数、起始数和净增。
            f.world.inventory.setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
            f.task.tick(f.world.player);
            var result = JsonParser.parseString(f.parent.stepResults().getLast().resultJson()).getAsJsonObject();
            var data = result.getAsJsonObject("data");
            check(result.get("success").getAsBoolean() && data.get("goal").getAsString().equals("additional_main_inventory_count")
                    && data.get("requested_additional_count").getAsInt() == 1 && data.get("baseline_count").getAsInt() == 1
                    && data.get("net_gained_count").getAsInt() == 1 && data.get("required_final_count").getAsInt() == 2,
                    "receipt reports the requested increment and the actual net gain: " + data);
        }
    }

    private static void baselineSurvivesRestart() throws Exception {
        // 首次启动记下一块；重启前木板被用掉，恢复后的新子任务仍以一块为起始数，不按恢复时的背包重算。
        try (var f = new Fixture(acquire())) {
            f.world.inventory.setItem(0, new ItemStack(Items.OAK_PLANKS));
            f.start();
            JsonObject saved = f.snapshot().getAsJsonArray("tasks").get(0).getAsJsonObject()
                    .getAsJsonArray("acquire_count_baselines").get(0).getAsJsonObject();
            check(saved.get("count_semantics").getAsString().equals("additional")
                    && saved.getAsJsonObject("carried").get(OAK.toString()).getAsInt() == 1, "checkpoint stores the frozen baseline");
            f.save(f.snapshot()); f.world.inventory.setItem(0, ItemStack.EMPTY); f.restore();
            var resumed = f.start();
            check(resumed.baselineCount() == 1 && resumed.count == 2, "restart keeps the original baseline");
        }
    }

    private static void retryKeepsBaselineAndWidensFamily() {
        // 同一步重试沿用首次数量；改成更宽的物品范围时旧物品保留原值，新物品按当时数量补记。
        var parent = new IntentTaskRecord(UUID.randomUUID(), null, acquire());
        check(parent.retainAcquireBaseline(Map.of(OAK, 1)).orElseThrow().equals(Map.of(OAK, 1)), "first start records the carried count");
        check(parent.retainAcquireBaseline(Map.of(OAK, 5)).orElseThrow().equals(Map.of(OAK, 1)), "retry does not move the baseline");
        check(parent.retainAcquireBaseline(Map.of(OAK, 5, BIRCH, 3)).orElseThrow().equals(Map.of(OAK, 1, BIRCH, 3)),
                "a widened family keeps old items and records new ones once");
    }

    private static void baselineFollowsItsOriginalStep() {
        // 插入前置只把起始数后移；明确替换本步才丢弃，新目标重新起算。
        var parent = new IntentTaskRecord(UUID.randomUUID(), null, acquire());
        parent.retainAcquireBaseline(Map.of(OAK, 2));
        var wait = new Goal("maicraft:wait_for_condition", "等待前置", null, "{\"after_s\":0}", "{}", List.of(), List.of());
        parent.insertRecovery(wait);
        parent.replaceCurrent(new Goal("maicraft:sequence", "两步前置", null, "{}", "{}", List.of(), List.of(wait, wait)));
        var root = IntentStateCodec.encode("a".repeat(64), List.of(), List.of(parent), Map.of(), List.of());
        var decoded = IntentStateCodec.decode(root).tasks().getFirst().acquireBaselines();
        check(decoded.size() == 1 && decoded.get(2).carried().equals(Map.of(OAK, 2)), "inserted steps shift the baseline through persistence");
        var replaced = new IntentTaskRecord(UUID.randomUUID(), null, acquire());
        replaced.retainAcquireBaseline(Map.of(OAK, 2)); replaced.replaceCurrent(acquire());
        check(replaced.acquireBaselines().isEmpty(), "replace_goal starts a fresh count");
    }

    private static void legacyCheckpointKeepsFinalCount() throws Exception {
        // 升级前的检查点没有起始数：当时 count 是最终合计数，已带一块就直接完成，不在恢复后再多拿一块。
        try (var f = new Fixture(acquire())) {
            JsonObject legacy = f.snapshot(); legacy.getAsJsonArray("tasks").get(0).getAsJsonObject().remove("acquire_count_baselines");
            f.save(legacy); f.restore();
            check(f.parent.acquireBaselines().get(0).legacyFinalCount(), "legacy step is marked as final-count");
            // 再次落盘仍写旧语义标记，二次恢复不能把它变成新的增量目标。
            f.save(f.snapshot()); f.restore();
            check(f.parent.acquireBaselines().get(0).legacyFinalCount(), "a second save keeps the legacy marker");
            f.world.inventory.setItem(0, new ItemStack(Items.OAK_PLANKS));
            var child = f.start();
            check(child.additionalCount == 0 && child.count == 1, "legacy step keeps the old final count");
            f.task.tick(f.world.player);
            check(f.parent.stepResults().getLast().success(), "already carried legacy target completes without more work");
        }
    }

    private static void internalCraftKeepsFinalCount() throws Exception {
        // craft 能力转成内部取物时仍给最终合计数；这次修改只改变公开 acquire_items 的语义。
        var craft = new Goal("maicraft:craft", "做一块橡木板", null, "{\"item_id\":\"minecraft:oak_planks\",\"count\":1}", "{}", List.of(), List.of());
        try (var f = new Fixture(craft)) {
            f.world.inventory.setItem(0, new ItemStack(Items.OAK_PLANKS));
            var child = f.start();
            check(child.additionalCount == 0 && child.count == 1 && f.parent.acquireBaselines().isEmpty(),
                    "internal craft acquisition stays a final-count request");
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

        @SuppressWarnings("unchecked") Fixture(Goal goal) throws Exception {
            identity = new StateIdentity("a".repeat(64), Files.createTempDirectory("acquire-count-checkpoint-"));
            newRuntime(); parent = new IntentTaskRecord(UUID.randomUUID(), null, goal, identity.key());
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
            check(state == TaskState.RUNNING, "parent creates the acquisition child: " + (state == TaskState.RUNNING ? "running" : task.result(state).toJson()));
            return (SemanticAcquireTaskRecord) field(IntentTask.class, "childRecord").get(task);
        }
        JsonObject snapshot() { return IntentStateCodec.encode(identity.key(), List.of(), List.of(parent), Map.of(), List.of()); }
        void save(JsonObject root) throws Exception { var saved = store.saveAsync(identity, root); flush(); saved.join(); }
        void flush() { while (!writes.isEmpty()) writes.removeFirst().run(); }
        void restore() throws Exception {
            UUID id = parent.externalId(); newRuntime();
            var restore = IntentRuntime.class.getDeclaredMethod("restoreBound", long.class); restore.setAccessible(true); restore.invoke(runtime, 100L);
            runtime.requireRecoveredState(); parent = runtime.task(id);
            check(parent != null && parent.restoredDetached() && parent.resume(), "disk-restored parent can resume explicitly");
            runtime.restoredTaskAttached(parent);
        }
        @Override public void close() throws Exception { world.close(); }
    }
    private static Field field(Class<?> type, String name) throws Exception { var field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
