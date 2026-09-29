// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.inventory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** 缺料后的整理仍识别近期施工输入，旧版本蓝图不会一直积累成永久保留清单。 */
public final class InventoryWorkItemsTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var newest = task("maicraft:build_machine", "{\"blocks\":[{\"block_id\":\"minecraft:chest\"}],\"input\":\"minecraft:gold_ingot\"}");
        newest.setState(TaskState.FAILED);
        var old = task("maicraft:build_machine", "{\"input\":\"minecraft:diamond\"}");
        var acquire = task("maicraft:acquire_items", "{\"item_id\":\"minecraft:quartz\"}");
        var items = new HashSet<Item>();
        InventoryWorkItems.retainLatestConstruction(List.of(acquire, newest, old), items);
        check(items.equals(Set.of(Items.CHEST, Items.GOLD_INGOT)), "only the latest construction input declaration is retained");
        newest.setState(TaskState.CANCELLED); items.clear();
        InventoryWorkItems.retainLatestConstruction(List.of(newest, old), items);
        check(items.isEmpty(), "cancelling the latest plan does not revive an older design reservation");
        // 工作作用域可以嵌套；退出或异常都必须恢复原作用域，避免影响以后无关的整理。
        InventoryWorkItems.within(Set.of(Items.COAL), () -> {
            check(scoped().equals(Set.of(Items.COAL)), "outer work scope is active");
            InventoryWorkItems.within(Set.of(Items.IRON_INGOT), () -> {
                check(scoped().equals(Set.of(Items.COAL, Items.IRON_INGOT)), "nested reservations combine"); return null;
            });
            check(scoped().equals(Set.of(Items.COAL)), "inner scope restores outer reservation"); return null;
        });
        try { InventoryWorkItems.within(Set.of(Items.GOLD_INGOT), () -> { throw new IllegalStateException("test scope exit"); }); }
        catch (IllegalStateException expected) { check(scoped().isEmpty(), "exception restores empty reservation scope"); }
        check(scoped().isEmpty(), "no work scope escapes its tick");
        System.out.println("InventoryWorkItemsTest: passed");
    }
    private static IntentTaskRecord task(String ability, String parameters) {
        // outcome中的物品名只是意图文字，整理规则只取正式参数中的声明，不能从叙述猜材料。
        return new IntentTaskRecord(UUID.randomUUID(), null, new Goal(ability, "minecraft:emerald", null, parameters, "{}", List.of(), List.of()));
    }
    private static Set<?> scoped() {
        try { var field = InventoryWorkItems.class.getDeclaredField("SCOPED"); field.setAccessible(true); return (Set<?>) ((ThreadLocal<?>) field.get(null)).get(); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
