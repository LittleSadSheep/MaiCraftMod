// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.task.suicide.SuicideArmorRestore;
import org.maiwithu.maicraft.core.task.suicide.SuicideRequest;
import org.maiwithu.maicraft.core.task.suicide.SuicideTask;
import org.maiwithu.maicraft.core.task.suicide.SuicideTaskRecord;
import org.maiwithu.maicraft.core.task.suicide.SuicideTaskTest;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** 洞里没有现成危险时用随身打火石或岩浆桶原地造危险，寻死前先脱甲收图腾，结束后穿回原件；原生结果由夹具注入，被拒绝时不换格反复操作。 */
public final class SuicideSelfHazardTest {
    private static final BlockPos CELL = new BlockPos(8, 1, 8);
    // 夹具按“刚登记收纳子任务”的上升沿补背包页；每个场景开始时清零。
    private static boolean stowingBefore;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        fire(); lavaBucket(); aimBounded(); armor(); restore();
        System.out.println("SuicideSelfHazardTest: passed");
    }

    private static void fire() throws Exception {
        try (var world = cave()) {
            // auto：没有岩浆、高处和怪物，带着打火石就低头对脚下地面点火，站在火里受原生伤害。
            world.inventory.setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
            world.mode.beforeBlockUse = () -> world.set(CELL, Blocks.FIRE.defaultBlockState());
            var task = task(world, "auto");
            run(world, task, () -> world.blockUses() == 1 && task.progress().get("ignitions_confirmed").equals(1));
            check(world.mode.usedHand == InteractionHand.MAIN_HAND && world.itemUses() == 0, "点火是对脚下方块的一次原生使用");
            check(task.progress().get("phase").equals("exposing_to_hazard") && task.progress().get("method").equals("fire"), "点着后应站在火里");
            world.player.setHealth(14);
            // 火自然熄灭后原地再点一次；火还烧着时不能重复右键浪费耐久。
            for (int tick = 0; tick < 20; tick++) step(world, task);
            check(world.blockUses() == 1, "火还烧着时不能重复点火");
            world.set(CELL, Blocks.AIR.defaultBlockState());
            run(world, task, () -> world.blockUses() == 2 && task.progress().get("ignitions_confirmed").equals(2));
            world.player.setHealth(0);
            check(task.observeDeath(world.player), "点火后本人死亡应完成寻死");
            var result = task.result(TaskState.SUCCESS);
            check(result.success() && result.data().get("death_observed").equals(true), "死亡成功应如实回报");
        }
        try (var world = cave()) {
            // 原生点击已发出却没出火（例如冒险模式拒绝）：只点这一次，不换邻格反复点，如实失败。
            world.inventory.setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
            var task = task(world, "fire");
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 400 && state == TaskState.RUNNING; tick++) state = step(world, task);
            check(state == TaskState.FAILED && world.blockUses() == 1, "点火被原生拒绝后不能换格重复点火");
            check(task.result(state).message().contains("rejected"), "失败说明应指出点火被拒绝");
        }
        try (var world = cave()) {
            // 没带点火物且身边没有危险：如实失败并说明缺点火物，不做任何原生使用。
            var task = task(world, "fire");
            TaskState state = TaskState.RUNNING;
            for (int tick = 0; tick < 40 && state == TaskState.RUNNING; tick++) state = step(world, task);
            check(state == TaskState.FAILED && world.blockUses() == 0, "没有点火物不能伪造点火");
            check(task.result(state).message().contains("No flint and steel"), "失败说明应指出缺少点火物");
            task.stop(world.player, Task.StopReason.REPLACED);
        }
    }

    private static void lavaBucket() throws Exception {
        try (var world = cave()) {
            // 只带岩浆桶：auto 用不上点火，就低头把岩浆倒进自己站的格子，原生回执确认扣桶后站在岩浆里等死。
            world.inventory.setItem(0, new ItemStack(Items.LAVA_BUCKET));
            world.mode.itemUse = player -> {
                world.set(CELL, Blocks.LAVA.defaultBlockState());
                world.inventory.setItem(0, new ItemStack(Items.BUCKET)); world.level.blockSequence++;
            };
            var task = task(world, "auto");
            run(world, task, () -> task.progress().get("lava_pours_confirmed").equals(1));
            check(world.itemUses() == 1 && world.blockUses() == 0 && world.mode.usedHand == InteractionHand.MAIN_HAND,
                    "倒岩浆是一次主手原生用桶，不点火也不另点方块");
            check(task.progress().get("method").equals("lava_bucket") && task.progress().get("phase").equals("exposing_to_hazard"),
                    "倒完岩浆应站在里面");
            for (int tick = 0; tick < 20; tick++) step(world, task);
            check(world.itemUses() == 1 && world.blockUses() == 0, "岩浆还在时不能再倒或改去点火");
            world.player.setHealth(0);
            check(task.observeDeath(world.player) && task.result(TaskState.SUCCESS).success(), "倒岩浆后本人死亡应完成寻死");
        }
        try (var world = cave()) {
            // 两样都带时 auto 先点火；点火已提交却没出火（原生拒绝），不换格再点，最后才倒岩浆桶。
            world.inventory.setItem(0, new ItemStack(Items.LAVA_BUCKET));
            world.inventory.setItem(1, new ItemStack(Items.FLINT_AND_STEEL));
            world.mode.itemUse = player -> {
                world.set(CELL, Blocks.LAVA.defaultBlockState());
                world.inventory.setItem(0, new ItemStack(Items.BUCKET)); world.level.blockSequence++;
            };
            var task = task(world, "auto");
            run(world, task, () -> task.progress().get("lava_pours_confirmed").equals(1));
            check(world.blockUses() == 1 && world.itemUses() == 1, "点火被拒绝只试一次，随后倒桶一次");
            check(task.progress().get("attempts").toString().contains("fire use did not produce"), "回执应保留点火未生效的原因");
            task.stop(world.player, Task.StopReason.REPLACED);
        }
    }

    private static void armor() throws Exception {
        try (var world = cave()) {
            // 背包已满：护甲原生收不回去就留在身上并记入回执，寻死不因此失败，继续点火。
            for (int slot = 0; slot < 36; slot++) world.inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
            world.inventory.setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
            world.inventory.armor.set(EquipmentSlot.HEAD.getIndex(), new ItemStack(Items.IRON_HELMET));
            world.mode.beforeBlockUse = () -> world.set(CELL, Blocks.FIRE.defaultBlockState());
            var task = task(world, "fire");
            run(world, task, () -> world.blockUses() == 1);
            check(task.progress().get("armor_still_worn").toString().contains("iron_helmet")
                    && ((List<?>) task.progress().get("armor_removed")).isEmpty(), "没空位的护甲应如实记为仍穿着");
            check(!world.player.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "卸甲不能丢掉护甲腾位置");
            task.stop(world.player, Task.StopReason.REPLACED);
        }
        try (var world = cave()) {
            // 有空位：先经原生背包界面把头盔快速移回背包，再收副手图腾、把主手从图腾切到空快捷栏，最后点火；物品都留在背包里。
            world.enableCraftingTransactions(); world.h.minecraft.screen = null;
            world.inventory.setItem(0, new ItemStack(Items.TOTEM_OF_UNDYING));
            world.inventory.setItem(1, new ItemStack(Items.FLINT_AND_STEEL));
            world.inventory.setItem(Inventory.SLOT_OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            world.inventory.armor.set(EquipmentSlot.HEAD.getIndex(), new ItemStack(Items.IRON_HELMET));
            world.mode.beforeBlockUse = () -> world.set(CELL, Blocks.FIRE.defaultBlockState());
            SuicideArmorRestore.reset();
            var task = task(world, "fire");
            run(world, task, () -> world.blockUses() == 1);
            check(world.player.getItemBySlot(EquipmentSlot.HEAD).isEmpty() && PlayerInv.count(world.inventory, Items.IRON_HELMET) == 1,
                    "头盔应原生移回背包");
            check(!world.player.getMainHandItem().is(Items.TOTEM_OF_UNDYING) && !world.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
                    && PlayerInv.count(world.inventory, Items.TOTEM_OF_UNDYING) == 2, "两只图腾都应离手但仍在背包里");
            check(task.progress().get("armor_removed").toString().contains("iron_helmet")
                    && ((List<?>) task.progress().get("armor_still_worn")).isEmpty(), "回执应记录已脱下的护甲");
            check(task.progress().get("totems_stowed").toString().contains("offhand")
                    && task.progress().get("totems_stowed").toString().contains("mainhand")
                    && ((List<?>) task.progress().get("totems_still_held")).isEmpty(), "回执应记录收起的图腾");
            check(world.mode.menuClicks == 2 && world.h.minecraft.screen == null, "头盔与副手图腾各点一次背包，并在点火前关好界面");
            task.stop(world.player, Task.StopReason.REPLACED);
            check(SuicideArmorRestore.pending().containsKey(EquipmentSlot.HEAD) && SuicideArmorRestore.pending().size() == 1,
                    "寻死结束应只登记脱下的头盔待穿回，图腾不放回手里");
        }
    }

    private static void restore() throws Exception {
        try (var world = cave()) {
            // 重生或没死成后：只按原件把登记的头盔穿回头部；背包里另一只耐久不同的同种头盔不能顶替。
            var stowed = new ItemStack(Items.IRON_HELMET); stowed.setDamageValue(17);
            var decoy = new ItemStack(Items.IRON_HELMET);
            world.inventory.setItem(0, decoy); world.inventory.setItem(3, stowed.copy());
            world.mode.itemUse = player -> {
                // 夹具代替原生右键穿戴：把主手那件放进头部栏位。
                var held = world.inventory.getSelected().copy();
                world.inventory.armor.set(EquipmentSlot.HEAD.getIndex(), held); world.inventory.setItem(world.inventory.selected, ItemStack.EMPTY);
            };
            SuicideArmorRestore.reset(); SuicideArmorRestore.remember(world.player, Map.of(EquipmentSlot.HEAD, stowed));
            var chain = new SuicideArmorRestore();
            for (int tick = 0; tick < 100 && !SuicideArmorRestore.pending().isEmpty(); tick++) {
                world.nextTick();
                if (chain.canRun(world.player)) chain.tick(world.player);
                DiscardFireTest.align(world);
            }
            var head = world.player.getItemBySlot(EquipmentSlot.HEAD);
            check(SuicideArmorRestore.pending().isEmpty() && ItemStack.isSameItemSameComponents(head, stowed), "应穿回脱下的那一件头盔");
            check(ItemStack.isSameItemSameComponents(world.inventory.getItem(0), decoy) && world.itemUses() == 1, "同种的另一件不能被穿上");
            check(!chain.canRun(world.player), "穿完后不再占用身体");
        }
        try (var world = cave()) {
            // 部位已被别的装备占用时不替换；原件一直不在背包里时等满同步窗口后如实放弃，不做任何原生使用。
            world.inventory.armor.set(EquipmentSlot.HEAD.getIndex(), new ItemStack(Items.LEATHER_HELMET));
            SuicideArmorRestore.reset();
            SuicideArmorRestore.remember(world.player, Map.of(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET),
                    EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS)));
            var chain = new SuicideArmorRestore();
            for (int tick = 0; tick < 260 && !SuicideArmorRestore.pending().isEmpty(); tick++) {
                world.nextTick();
                check(!chain.canRun(world.player), "找不到原件时不能接管身体");
            }
            check(SuicideArmorRestore.pending().isEmpty() && world.itemUses() == 0 && world.mode.menuClicks == 0
                    && world.player.getItemBySlot(EquipmentSlot.HEAD).is(Items.LEATHER_HELMET), "占用部位不替换，缺原件如实放弃");
        }
    }

    private static void aimBounded() throws Exception {
        try (var world = cave()) {
            // 镜头始终低头失败（真实会话的瞄准卡点）：不能换格无限循环，同一方式连续三次停在提交之前就应有界失败，
            // 失败说明带最后一次诊断与候选格，逐次经过的原因留在 attempts。
            world.inventory.setItem(Inventory.SLOT_OFFHAND, new ItemStack(Items.FLINT_AND_STEEL));
            var task = task(world, "fire");
            TaskState state = TaskState.RUNNING;
            int moved = 0;
            for (int tick = 0; tick < 1500 && state == TaskState.RUNNING; tick++) {
                world.nextTick(); world.level.acknowledgedSequence = world.level.blockSequence;
                if (world.h.minecraft.screen != null) MenuVisibility.rendered(world.h.minecraft.screen);
                state = task.tick(world.player);
                // 接近不靠真实寻路（用例只验证瞄准有界性）：放弃后把身体直接放到下一格；
                // 镜头全程没有代转，模拟低头永远不收敛的会话。
                if (moved < 2 && "observing".equals(task.progress().get("phase"))) {
                    world.position(Vec3.atBottomCenterOf(new BlockPos(8 + ++moved, 1, 8)));
                    world.player.setOnGround(true);
                }
            }
            check(state == TaskState.FAILED && world.blockUses() == 0 && world.itemUses() == 0,
                    "瞄准不了应有界失败且从未出手: state=" + state + " progress=" + task.progress());
            var message = task.result(state).message();
            check(message.contains("never got submitted") && message.contains("candidate cell"),
                    "失败说明应携带最后诊断与候选格: " + message);
            check(message.contains("converge"), "诊断应指出卡在准星收敛: " + message);
            check(task.progress().get("attempts").toString().contains("could not aim"),
                    "逐次瞄准失败应留在 attempts");
            task.stop(world.player, Task.StopReason.REPLACED);
        }
    }

    private static InteractionWorldTestHarness cave() throws Exception {
        // 复用寻死夹具的模式与窗口，再撤掉岸边岩浆和石台，让角色站在空旷石地上，周围没有任何现成危险。
        var world = new InteractionWorldTestHarness();
        SuicideTaskTest.prepare(world);
        world.set(new BlockPos(9, 1, 8), Blocks.AIR.defaultBlockState());
        world.set(CELL, Blocks.AIR.defaultBlockState());
        world.position(Vec3.atBottomCenterOf(CELL)); world.player.setOnGround(true);
        world.h.minecraft.screen = null; stowingBefore = false;
        return world;
    }

    private static SuicideTask task(InteractionWorldTestHarness world, String method) {
        return new SuicideTask(world.player, new SuicideTaskRecord("self-hazard-test", new SuicideRequest(method, 8, 60, true)));
    }

    private static TaskState step(InteractionWorldTestHarness world, SuicideTask task) throws Exception {
        // 每刻先给出服务端方块确认并标记背包页已绘制，再推进任务，最后把身体控制器要求的视角落到角色上。
        world.nextTick(); world.level.acknowledgedSequence = world.level.blockSequence;
        if (world.h.minecraft.screen != null) MenuVisibility.rendered(world.h.minecraft.screen);
        TaskState state = task.tick(world.player);
        DiscardFireTest.align(world);
        // 无窗口夹具不能真正打开界面：启用真实菜单点击的场景里，每个收纳子任务刚登记时由夹具补上原生背包页；
        // 子任务点击后自己关页并等关闭确认，夹具不能再把页面塞回去，所以只在登记的那一刻补一次。
        boolean stowing = "stowing_protection".equals(task.progress().get("phase"));
        if (world.mode.craftingClicks && stowing && !stowingBefore && world.h.minecraft.screen == null)
            world.h.minecraft.screen = world.h.inventoryScreen();
        stowingBefore = stowing;
        return state;
    }

    private static void run(InteractionWorldTestHarness world, SuicideTask task, BooleanSupplier done) throws Exception {
        for (int tick = 0; tick < 300; tick++) {
            TaskState state = step(world, task);
            if (state != TaskState.RUNNING) throw new AssertionError("活着时不能提前结束寻死: " + task.result(state).message() + " " + task.progress());
            if (done.getAsBoolean()) return;
        }
        throw new AssertionError("未完成原生动作: " + task.progress() + " blocks=" + world.blockUses() + " items=" + world.itemUses());
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
