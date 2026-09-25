// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.ae2.Ae2ResourceSupply;
import org.maiwithu.maicraft.core.integration.ae2.Ae2SupplyTaskRecord;
import org.maiwithu.maicraft.core.inventory.StockEvidence;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 用供料父任务驱动查询与取现货：默认补料也纳入无线网络，部分库存和查询未知不能冒充已拿够。 */
public final class AcquisitionWirelessInventoryTest {
    private static final ResourceLocation ITEM = ResourceLocation.parse("minecraft:stone_bricks");
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        scenario(2, false);
        scenario(3, false);
        scenario(2, true);
        explicitMiningCannotOpenWireless();
        System.out.println("AcquisitionWirelessInventoryTest: passed");
    }

    @SuppressWarnings("unchecked")
    private static void scenario(int wanted, boolean queryFails) throws Exception {
        // 先加载任务单的默认注册，再暂时替换网络执行器，避免首次类初始化盖掉测试回执。
        Class.forName(Ae2SupplyTaskRecord.class.getName());
        var registryField = TaskFactory.class.getDeclaredField("RUNNERS"); registryField.setAccessible(true);
        var runners = (Map<Class<? extends TaskRecord>, TaskFactory.Runner<? extends TaskRecord>>) registryField.get(null);
        var previous = runners.get(Ae2SupplyTaskRecord.class);
        try (var world = new InteractionWorldTestHarness()) {
            List<Ae2ResourceSupply.Request> requests = new ArrayList<>();
            TaskFactory.register(Ae2SupplyTaskRecord.class, (player, record) -> new Task() {
                @Override public String name() { return "wireless inventory test"; }
                @Override public void start(LocalPlayer ignored) { requests.add(record.request); }
                @Override public TaskState tick(LocalPlayer ignored) {
                    if (record.request.operation() == Ae2ResourceSupply.Operation.OBSERVE) {
                        if (queryFails) return TaskState.FAILED;
                        try { remember(world); } catch (Exception failure) { throw new AssertionError(failure); }
                    } else {
                        check(record.request.wirelessOnly() && !record.request.allowCrafting(), "automatic inventory access cannot switch networks or start crafting");
                        world.inventory.setItem(0, new ItemStack(Items.STONE_BRICKS, (int) record.request.totalCount()));
                    }
                    return TaskState.SUCCESS;
                }
                @Override public void stop(LocalPlayer ignored, StopReason reason) { }
                @Override public TaskResult result(TaskState state) {
                    return state == TaskState.SUCCESS ? TaskResult.ok("settled stock operation", Map.of("outcome_uncertain", false))
                            : TaskResult.fail("wireless connection unavailable", Map.of("outcome_uncertain", false));
                }
            });
            var record = new SemanticAcquireTaskRecord("wireless-inventory", 1000, List.of(ITEM), wanted,
                    List.of(SemanticAcquireTaskRecord.Source.INVENTORY, SemanticAcquireTaskRecord.Source.WIRELESS), false,
                    SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 8);
            var task = new SemanticAcquireCompanionTask(world.player, record, ignored -> true);
            task.onStart();
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 16 && state == TaskState.RUNNING; tick++) {
                state = task.onTick();
                if (wanted == 3 && world.inventory.countItem(Items.STONE_BRICKS) == 2) break;
            }
            check(requests.getFirst().operation() == Ae2ResourceSupply.Operation.OBSERVE, "the first network action only reads inventory");
            if (queryFails) {
                check(state == TaskState.FAILED && requests.size() == 1 && world.inventory.isEmpty(), "unknown stock stops before new collection or crafting");
            } else {
                check(requests.size() == 2 && requests.get(1).totalCount() == 2, "withdraw only the two actually observed items");
                check((wanted == 2) == (state == TaskState.SUCCESS), "two network items cannot complete a three-item goal");
                check(StockEvidence.latestNetwork(world.player).orElseThrow().storedCount(ITEM) == 0, "withdrawn items are not counted again in the network");
            }
            task.stop(world.player, Task.StopReason.REPLACED);
        } finally {
            if (previous == null) runners.remove(Ae2SupplyTaskRecord.class); else runners.put(Ae2SupplyTaskRecord.class, previous);
        }
    }

    private static void explicitMiningCannotOpenWireless() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            // 复现“只许mine却从无线库存领货”：终端确实可用，第一刻仍只能盘点主背包，不能派发AE子任务。
            var record = new SemanticAcquireTaskRecord("mine-only", 1000, List.of(ITEM), 1,
                    List.of(SemanticAcquireTaskRecord.Source.MINE), false,
                    SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 8);
            var task = new SemanticAcquireCompanionTask(world.player, record, ignored -> true); task.onStart();
            check(task.onTick() == TaskState.RUNNING, "mine-only begins with ordinary inventory observation");
            var active = task.getClass().getDeclaredField("activeRecord"); active.setAccessible(true);
            check(active.get(task) == null && world.blockUses() == 0 && world.itemUses() == 0,
                    "carrying a wireless terminal cannot start a read or withdrawal for unlisted sources");
            var rootField = task.getClass().getDeclaredField("rootNeed"); rootField.setAccessible(true);
            var root = (AcquisitionNeed) rootField.get(task);
            root.wirelessInventory = true;
            check(!root.canTry(SemanticAcquireTaskRecord.Source.WIRELESS) && !root.canTry(SemanticAcquireTaskRecord.Source.STORAGE),
                    "even stale availability evidence cannot enlarge the current need permissions");
            check(SemanticAcquireTaskRecord.DEFAULT_SOURCES.contains(SemanticAcquireTaskRecord.Source.WIRELESS),
                    "unspecified ordinary acquisition retains explicitly represented wireless stock access");
            task.stop(world.player, Task.StopReason.REPLACED);
        }
    }

    private static void remember(InteractionWorldTestHarness world) throws Exception {
        var field = StockEvidence.class.getDeclaredField("NETWORK_CACHE"); field.setAccessible(true); Object cache = field.get(null);
        var record = cache.getClass().getDeclaredMethod("record", Object.class, Object.class, Map.class, StockEvidence.Snapshot.class); record.setAccessible(true);
        record.invoke(cache, world.player, world.level, Map.of(), new StockEvidence.Snapshot(StockEvidence.Source.AE2, Map.of(ITEM, 2L), Set.of(), world.level.getGameTime()));
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
