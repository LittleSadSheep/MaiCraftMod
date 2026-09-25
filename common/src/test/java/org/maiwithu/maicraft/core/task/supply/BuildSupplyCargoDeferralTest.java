// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.supply;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.preview.PreviewSession.Decision;
import org.maiwithu.maicraft.core.pathing.settings.ScaffoldMaterials;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.build.BuildExcavationSpoilSupply;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.container.ContainerSupplySources;
import org.maiwithu.maicraft.core.task.container.ContainerSupplySourcesTest;
import org.maiwithu.maicraft.core.task.container.SemanticContainerTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 验证延期整理不阻塞可完成的建造，也不能吞掉满包或未确认搬运；夹具变动不代表原生 GUI 验收。 */
public final class BuildSupplyCargoDeferralTest {
    private static final BlockPos TARGET = new BlockPos(5, 1, 5);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var runners = runners(); var build = runners.get(BuildTaskRecord.class); var container = runners.get(SemanticContainerTaskRecord.class);
        List<String> scaffolds = ScaffoldMaterials.storedIds(null);
        try {
            ScaffoldMaterials.store(null, ScaffoldMaterials.factoryDefaultIds());
            noWarehouseDoesNotBlockFundedConstruction();
            unchangedConditionsDoNotRetryButNewStorageCan();
            fullInventoryCannotFetchAnotherBatch();
            narrowInventoryStartsMaterialSupply();
            uncertainTransfersAndUnsettledMenusStop();
            receiptAdmissionRequiresExplicitNoEffects();
        } finally {
            if (build == null) runners.remove(BuildTaskRecord.class); else runners.put(BuildTaskRecord.class, build);
            if (container == null) runners.remove(SemanticContainerTaskRecord.class); else runners.put(SemanticContainerTaskRecord.class, container);
            ScaffoldMaterials.store(null, scaffolds); ContainerSupplySources.reset();
        }
        System.out.println("BuildSupplyCargoDeferralTest: passed");
    }

    private static void noWarehouseDoesNotBlockFundedConstruction() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            ContainerSupplySourcesTest.worldEntities(h); var task = task(h); ordinaryCargo(h, true);
            TaskFactory.register(BuildTaskRecord.class, (player, record) -> new Task() {
                // 仅模拟子任务完成后的世界和背包观察，真正检验的是父任务没有因找不到箱子而提前失败。
                public TaskState tick(LocalPlayer ignored) { h.set(TARGET, Blocks.OAK_PLANKS.defaultBlockState()); h.inventory.getItem(0).shrink(1); return TaskState.SUCCESS; }
                public void stop(LocalPlayer ignored, StopReason reason) {}
                public String name() { return "模拟已确认施工"; }
                public TaskResult result(TaskState state) { return TaskResult.ok("模拟完成", Map.of("remaining_scaffolds", List.of())); }
            });
            task.start(h.player); task.tick(h.player);
            check(task.tick(h.player) == TaskState.RUNNING && Boolean.TRUE.equals(task.resultData().get("cleanup_deferred")),
                    "附近没有仓库，但建材和空位充足时延期清包而不是直接判施工失败");
            check(task.tick(h.player) == TaskState.RUNNING && field(task, "activeChild").get(task) != null, "延期后仍派发普通施工");
            task.tick(h.player); check(task.tick(h.player) == TaskState.SUCCESS, "已确认的建筑可以正常完成");
            var result = task.result(TaskState.SUCCESS);
            check(Boolean.TRUE.equals(result.data().get("goal_satisfied")) && Boolean.TRUE.equals(result.data().get("cleanup_deferred"))
                    && result.data().get("cleanup_remaining").equals(Map.of("minecraft:dirt", 64))
                    && result.message().contains("cleanup deferred"), "完成回执明确留下六十四份余料，不宣称全部存好");
        }
    }

    private static void unchangedConditionsDoNotRetryButNewStorageCan() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var entities = ContainerSupplySourcesTest.worldEntities(h); var task = task(h); ordinaryCargo(h, true);
            task.start(h.player); task.tick(h.player); task.tick(h.player);
            var prepare = SemanticBuildSupplyCompanionTask.class.getDeclaredMethod("prepareCargo"); prepare.setAccessible(true);
            field(task, "cargoRetryAt").setLong(task, 0);
            for (int i = 0; i < 30; i++) check(!(boolean) prepare.invoke(task), "条件不变时不能每次检查都重新找箱子");
            check(!spoil(task).active() && field(task, "cargoDeferrals").getInt(task) == 1, "没有变化不会消耗新的整理轮次");
            // 陌生箱子出现不会获得整理许可；确认它是土料箱后才允许有界重试。
            ContainerSupplySourcesTest.addBarrel(h, entities, new BlockPos(3, 1, 3));
            check(!(boolean) prepare.invoke(task), "新出现但内容未知的箱子不能唤起整理");
            ContainerSupplySourcesTest.rememberContents(h, new BlockPos(3, 1, 3), ResourceLocation.parse("minecraft:dirt"), 1);
            check((boolean) prepare.invoke(task) && spoil(task).active(), "观察到新仓库后可以有界重试整理");
            task.result(TaskState.CANCELLED);
        }
    }

    /** 复现背包只剩两个空格但缺少一份成品：应进入原供料流程，不能先要求四个空槽。 */
    private static void narrowInventoryStartsMaterialSupply() throws Exception {
        for (boolean surplus : List.of(false, true)) try (var h = new InteractionWorldTestHarness()) {
            ContainerSupplySourcesTest.worldEntities(h); var task = task(h);
            for (int slot = 0; slot < 34; slot++) h.inventory.setItem(slot, new ItemStack(Items.BREAD, 64));
            // 带少量土料且没有已授权仓库时，也不能经延期清包分支再次把两个可用槽位判成零容量。
            if (surplus) h.inventory.setItem(0, new ItemStack(Items.DIRT, 64));
            var supply = (SemanticMaterialSupplyCoordinator) field(task, "supply").get(task);
            task.start(h.player);
            for (int tick = 0; tick < 8 && !supply.active(); tick++) task.tick(h.player);
            check(supply.active() && !task.resultData().containsKey("failure_code"), "成品有真实容积时派发取料");
            check(h.itemUses() == 0 && h.blockUses() == 0, "仅交接需求，不假造取料或施工完成");
            task.result(TaskState.CANCELLED);
        }
    }

    private static void fullInventoryCannotFetchAnotherBatch() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var entities = ContainerSupplySourcesTest.worldEntities(h); var task = task(h);
            ContainerSupplySourcesTest.addBarrel(h, entities, new BlockPos(3, 1, 3));
            ContainerSupplySourcesTest.rememberContents(h, new BlockPos(3, 1, 3), ResourceLocation.parse("minecraft:dirt"), 1);
            for (int slot = 0; slot < 36; slot++) h.inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
            TaskFactory.register(SemanticContainerTaskRecord.class, (player, record) -> new EmptyDeposit(false, false));
            task.start(h.player); TaskState state = TaskState.RUNNING;
            for (int i = 0; i < 8 && state == TaskState.RUNNING; i++) state = task.tick(h.player);
            var data = task.resultData();
            // 实际背包和仓库都放不下时返回容量失败及缺口，不挂起等待供料器之外的决策流程。
            check(state == TaskState.FAILED && "inventory_capacity_blocked".equals(data.get("failure_code"))
                    && Boolean.FALSE.equals(data.get("requires_decision")), "箱子全满且背包放不进第一份建材时直接报告实际失败");
            var capacity = (Map<?, ?>) data.get("inventory_capacity");
            check(capacity.get("empty_main_slots").equals(0L) && capacity.get("minimum_additional_slots").equals(1),
                    "真正满包时带回空槽和缺口，提示先腾空间");
            check(!((SemanticMaterialSupplyCoordinator) field(task, "supply").get(task)).active()
                    && field(task, "activeChild").get(task) == null, "满包不能继续派发取料或无材料施工");
            task.result(TaskState.FAILED);
        }
    }

    private static void uncertainTransfersAndUnsettledMenusStop() throws Exception {
        for (boolean cursor : List.of(false, true)) try (var h = new InteractionWorldTestHarness()) {
            var entities = ContainerSupplySourcesTest.worldEntities(h); var task = task(h); ordinaryCargo(h, true);
            ContainerSupplySourcesTest.addBarrel(h, entities, new BlockPos(3, 1, 3));
            ContainerSupplySourcesTest.rememberContents(h, new BlockPos(3, 1, 3), ResourceLocation.parse("minecraft:dirt"), 1);
            TaskFactory.register(SemanticContainerTaskRecord.class, (player, record) -> new EmptyDeposit(!cursor, cursor) {
                @Override public TaskState tick(LocalPlayer owner) {
                    if (cursor) owner.inventoryMenu.setCarried(new ItemStack(Items.DIRT));
                    return TaskState.SUCCESS;
                }
            });
            task.start(h.player); TaskState state = TaskState.RUNNING;
            for (int i = 0; i < 8 && state == TaskState.RUNNING; i++) state = task.tick(h.player);
            check(state == TaskState.FAILED && "excavation_spoil_storage_failed".equals(task.resultData().get("failure_code"))
                    && Boolean.FALSE.equals(task.resultData().get("cleanup_deferred")), "有未确认存入或鼠标未清空时不能把失败降成延期");
            if (!cursor) check(Boolean.TRUE.equals(task.resultData().get("outcome_uncertain")), "真实不确定性仍传到总任务");
            task.result(TaskState.FAILED); h.player.inventoryMenu.setCarried(ItemStack.EMPTY);
        }
    }

    private static void receiptAdmissionRequiresExplicitNoEffects() {
        Map<String, Object> root = new LinkedHashMap<>(Map.of("failure_code", "excavation_spoil_no_verified_storage_capacity",
                "outcome_uncertain", false, "confirmed_deposited", Map.of(), "warehouse_attempts", 1));
        Map<String, Object> child = new LinkedHashMap<>(deposit(false, false)); root.put("last_container_receipt", child);
        check(SemanticBuildSupplyCompanionTask.storageUnavailableWithoutEffects(root), "明确零搬运且已知未点击的容量失败才可延期");
        child.put("effects_started", true); check(!SemanticBuildSupplyCompanionTask.storageUnavailableWithoutEffects(root), "点击已经发出即使数量为零也不能延期");
        child.remove("effects_started"); check(!SemanticBuildSupplyCompanionTask.storageUnavailableWithoutEffects(root), "缺少无副作用证明时保守停止");
        child.put("effects_started", false); root.put("confirmed_deposited", Map.of("minecraft:dirt", 1));
        check(!SemanticBuildSupplyCompanionTask.storageUnavailableWithoutEffects(root), "本次曾有实际搬运不能冒充完全未动过的失败");
        // AE 在识别无穷库存后尚未拆叠或存入，保留全部余料可以回到已有的空间充分施工分支。
        root.put("confirmed_deposited", Map.of());
        root.put("last_container_receipt", Map.of("operation", "deposit", "deposited", Map.of(),
                "confirmed_deposited_total", 0, "outcome_uncertain", false, "effects_started", false,
                "failure_code", "ae2_deposit_quantity_not_finitely_observable"));
        check(SemanticBuildSupplyCompanionTask.storageUnavailableWithoutEffects(root), "无穷库存的零动作拒绝可延期整理");
    }

    private static SemanticBuildSupplyCompanionTask task(InteractionWorldTestHarness h) {
        h.player.inventoryMenu.setCarried(ItemStack.EMPTY); h.set(TARGET, Blocks.AIR.defaultBlockState());
        var target = new BuildTaskRecord.Target(Blocks.OAK_PLANKS, Items.OAK_PLANKS, TARGET, "wall", null, null, null);
        var plan = new BuildTaskRecord("deferred-cargo-build", 1000, List.of(target), false);
        var record = new SemanticBuildSupplyTaskRecord("deferred-cargo", 1000, plan,
                SemanticMaterialSupplyCoordinator.MaterialPolicy.STORAGE_AVAILABLE, List.of(SemanticAcquireTaskRecord.Source.STORAGE), false, List.of(), false);
        return new SemanticBuildSupplyCompanionTask(h.player, record, (owner, frozen) -> Decision.DISABLED);
    }
    private static void ordinaryCargo(InteractionWorldTestHarness h, boolean funded) {
        if (funded) h.inventory.setItem(0, new ItemStack(Items.OAK_PLANKS));
        h.inventory.setItem(1, new ItemStack(Items.DIRT, 64)); h.inventory.setItem(2, new ItemStack(Items.DIRT, 64));
    }
    private static BuildExcavationSpoilSupply spoil(Object task) throws Exception { return (BuildExcavationSpoilSupply) field(task, "spoilSupply").get(task); }
    private static class EmptyDeposit implements Task {
        private final boolean uncertain, effects; EmptyDeposit(boolean uncertain, boolean effects) { this.uncertain = uncertain; this.effects = effects; }
        public TaskState tick(LocalPlayer player) { return TaskState.SUCCESS; }
        public void stop(LocalPlayer player, StopReason reason) {}
        public String name() { return "模拟零容量存入回执"; }
        public TaskResult result(TaskState state) { return TaskResult.ok("模拟零容量", deposit(uncertain, effects)); }
    }
    private static Map<String, Object> deposit(boolean uncertain, boolean effects) {
        return Map.of("operation", "deposit", "bounded_storage_deposit", true, "moved_count", 0,
                "moved_items", Map.of(), "outcome_uncertain", uncertain, "effects_started", effects);
    }
    @SuppressWarnings("unchecked") private static Map<Class<? extends TaskRecord>, TaskFactory.Runner<? extends TaskRecord>> runners() throws Exception {
        return (Map<Class<? extends TaskRecord>, TaskFactory.Runner<? extends TaskRecord>>) field(TaskFactory.class, "RUNNERS").get(null);
    }
    private static Field field(Object instance, String name) throws Exception {
        Class<?> type = instance instanceof Class<?> value ? value : instance.getClass(); Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
