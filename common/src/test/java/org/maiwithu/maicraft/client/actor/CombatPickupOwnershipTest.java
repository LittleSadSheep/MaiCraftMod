// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.task.combat.AttackCompanionTask;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** 死亡现场收取允许旧物和未知来源，整堆合并不能重复记账，未确认入包仍如实保留失败。 */
public final class CombatPickupOwnershipTest {
    private static final Vec3 SITE = new Vec3(5.5, 1, 5.5);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        oldStackGrowth(); oldStackMergesIntoNewDrop(); oldStackMergesIntoTrackedDrop(); trackedStacksMerge();
        unknownSource(false); unknownSource(true);
        System.out.println("CombatPickupOwnershipTest: old/unknown drops, merged counts and native pickup settlement passed");
    }

    private static void oldStackGrowth() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            // 旧堆先存在，击杀同步时变大；来源混合只进观察回执，角色仍收整堆。
            var old = item(world, 611, 3); var task = task(world); Object loot = loot(task);
            call(loot, "rememberPreexisting"); begin(loot); old.getItem().grow(2); call(loot, "discover");
            check(((List<?>) call(loot, "live")).contains(old), "旧物混堆仍可拾取");
            check(((Map<?, ?>) call(loot, "report")).get("ambiguous_merged_by_item").equals(Map.of("minecraft:brick", 2)),
                    "混入旧堆的来源差异保留在默认回执");
            collectAndFinish(world, task, 5);
        }
    }

    private static void oldStackMergesIntoNewDrop() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            // 旧三件并入新的一件，旧UUID已消失；新四件全部收取，推定击杀来源仍只记一件。
            var old = item(world, 621, 3); var task = task(world); Object loot = loot(task);
            call(loot, "rememberPreexisting"); begin(loot); world.level.entities.remove(old.getId());
            var survivor = item(world, 622, 4); call(loot, "discover");
            check(((List<?>) call(loot, "live")).contains(survivor), "旧物并入新实体不拒绝整堆收取");
            check(((Map<?, ?>) call(loot, "report")).get("attributed_by_item").equals(Map.of("minecraft:brick", 1)),
                    "整堆拾取不冒充四件击杀产物");
            collectAndFinish(world, task, 4);
        }
    }

    private static void oldStackMergesIntoTrackedDrop() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            // 先看到旧三件和新一件，再看到旧实体消失、新实体涨到四件，仍跟踪幸存堆且不因混堆跳过。
            var old = item(world, 625, 3); var task = task(world); Object loot = loot(task);
            call(loot, "rememberPreexisting"); begin(loot); var survivor = item(world, 626, 1); call(loot, "discover");
            world.level.entities.remove(old.getId()); survivor.getItem().grow(3); call(loot, "discover");
            check(((List<?>) call(loot, "live")).contains(survivor)
                    && ((Map<?, ?>) call(loot, "report")).get("observed_pickup_units_by_item").equals(Map.of("minecraft:brick", 4)),
                    "混堆后的幸存目标继续拾取，只记四件待入包");
            collectAndFinish(world, task, 4);
        }
    }

    private static void trackedStacksMerge() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            // 两个已追踪堆叠合成一个实体；消失实体的待收数量转入幸存堆，不重复记成新产物。
            var task = task(world); Object loot = loot(task); begin(loot);
            var donor = item(world, 631, 2); var survivor = item(world, 632, 3); call(loot, "discover");
            world.level.entities.remove(donor.getId()); survivor.getItem().grow(2); call(loot, "discover");
            check(((Map<?, ?>) call(loot, "report")).get("observed_pickup_units_by_item").equals(Map.of("minecraft:brick", 5)),
                    "已追踪实体合堆后仍只等待五件入包");
            // 同类库存提前增加时，仍在地上的实体不能被宣布已经拾取。
            world.inventory.setItem(0, new ItemStack(Items.BRICK, 5)); call(loot, "discover");
            check(((Map<?, ?>) call(loot, "confirmedGains")).isEmpty(), "活着的完整堆叠不被库存增加冒充拾取");
            collectAndFinish(world, task, 5);
        }
    }

    private static void unknownSource(boolean disappearsWithoutReceipt) throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            // 现场实体的出生年龄与击杀不符：可以尝试收取，但来源推断和原生入包仍分别报告。
            var task = task(world); Object loot = loot(task); begin(loot);
            var unknown = item(world, 641, 1); unknown.tickCount = 100; call(loot, "discover");
            check(((List<?>) call(loot, "live")).contains(unknown), "未证明击杀来源的现场物品仍可收取");
            check(((Map<?, ?>) call(loot, "report")).get("attributed_by_item").equals(Map.of()), "未知来源不冒充击杀产量");
            if (!disappearsWithoutReceipt) { collectAndFinish(world, task, 1); return; }
            world.level.entities.clear(); call(loot, "discover"); call(loot, "vanishState");
            for (int tick = 0; tick < 11; tick++) world.nextTick();
            check(call(task, "tickLoot") == TaskState.FAILED && ((Map<?, ?>) call(loot, "confirmedGains")).isEmpty(),
                    "真实未确认入包仍失败，删除归属门控不能伪报拾取成功");
        }
    }

    private static void collectAndFinish(InteractionWorldTestHarness world, AttackCompanionTask task, int count) throws Exception {
        // 先重放实体移除，等待背包包；两者对齐后走真实战斗收尾分支，混堆和未知来源不再改判失败。
        Object loot = loot(task); world.level.entities.clear(); call(loot, "discover");
        check(call(loot, "vanishState").toString().equals("WAITING_FOR_INVENTORY_SYNC")
                || !((Map<?, ?>) call(loot, "confirmedGains")).isEmpty(), "入包证据不够时保留同步等待");
        world.inventory.setItem(0, new ItemStack(Items.BRICK, count)); world.nextTick(); world.nextTick();
        check(call(task, "tickLoot") == TaskState.RUNNING && call(loot, "confirmedGains").equals(Map.of("minecraft:brick", count)),
                "真实整堆入包后正常结清战斗拾取");
        check(((Map<?, ?>) call(loot, "report")).get("active_sweep").equals(false), "来源观察不会重复抢占后续战斗");
    }

    private static AttackCompanionTask task(InteractionWorldTestHarness world) {
        return new AttackCompanionTask(world.player, new AttackTaskRecord("combat-pickup", 1000, List.of(600), false));
    }
    private static Object loot(AttackCompanionTask task) throws Exception { return ActorControlTestHarness.field(AttackCompanionTask.class, "loot").get(task); }
    private static void begin(Object loot) throws Exception {
        Method method = loot.getClass().getDeclaredMethod("begin", int.class, BlockPos.class);
        method.setAccessible(true); method.invoke(loot, 600, BlockPos.containing(SITE));
    }
    private static ItemEntity item(InteractionWorldTestHarness world, int id, int count) throws Exception {
        var item = ItemEntityReceiptsTest.item(world, id, SITE, new ItemStack(Items.BRICK, count));
        ActorControlTestHarness.field(Entity.class, "blockPosition").set(item, BlockPos.containing(SITE));
        ActorControlTestHarness.field(Entity.class, "deltaMovement").set(item, Vec3.ZERO); return item;
    }
    private static Object call(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(target);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
