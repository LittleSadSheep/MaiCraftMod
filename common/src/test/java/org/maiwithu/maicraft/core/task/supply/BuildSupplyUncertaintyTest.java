// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.supply;

import java.lang.reflect.Field;
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
import org.maiwithu.maicraft.core.task.container.ContainerSupplySourcesTest;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 先确认存入余料，再制造后续施工未知回执，验证干净仓库结果不能掩盖放置未知。 */
public final class BuildSupplyUncertaintyTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        List<String> previousScaffolds = ScaffoldMaterials.storedIds(null);
        ScaffoldMaterials.store(null, ScaffoldMaterials.factoryDefaultIds());
        try (var h = new InteractionWorldTestHarness()) {
            h.player.inventoryMenu.setCarried(ItemStack.EMPTY);
            h.inventory.setItem(0, new ItemStack(Items.OAK_PLANKS)); h.inventory.setItem(1, new ItemStack(Items.DIRT, 64));
            h.inventory.setItem(2, new ItemStack(Items.DIRT, 64));
            var entities = ContainerSupplySourcesTest.worldEntities(h);
            ContainerSupplySourcesTest.addBarrel(h, entities, new BlockPos(3, 1, 3));
            // 先选有真实土料线索的仓库，后续继续验证存入回执不能掩盖施工未知。
            ContainerSupplySourcesTest.rememberContents(h, new BlockPos(3, 1, 3), ResourceLocation.parse("minecraft:dirt"), 1);
            BlockPos target = new BlockPos(5, 1, 5); h.set(target, Blocks.AIR.defaultBlockState());
            var plan = new BuildTaskRecord("uncertain-after-cleanup", 1000, List.of(new BuildTaskRecord.Target(
                    Blocks.OAK_PLANKS, Items.OAK_PLANKS, target, "wall", null, null, null)), false);
            var record = new SemanticBuildSupplyTaskRecord("uncertain-parent", 1000, plan,
                    SemanticMaterialSupplyCoordinator.MaterialPolicy.STORAGE_AVAILABLE,
                    List.of(SemanticAcquireTaskRecord.Source.STORAGE), false, List.of(), false);
            var task = new SemanticBuildSupplyCompanionTask(h.player, record, (owner, frozen) -> Decision.DISABLED);
            task.start(h.player);
            var spoil = (BuildExcavationSpoilSupply) field(task, "spoilSupply").get(task);
            spoil.begin(h.player, "confirmed-spoil", 1000, Map.of(ResourceLocation.parse("minecraft:dirt"), 64), List.of(), 16);
            spoil.tick(h.player, ignored -> null);
            field(spoil, "child").set(spoil, new Receipt(TaskState.SUCCESS, Map.of("operation", "deposit", "bounded_storage_deposit", true,
                    "moved_count", 64, "moved_items", Map.of("minecraft:dirt", 64), "outcome_uncertain", false, "effects_started", true)));
            // 夹具同步库存减少，再经过真实整理器核对差量；这里不宣称进行了原生 GUI 点击。
            h.inventory.setItem(2, ItemStack.EMPTY);
            spoil.tick(h.player, child -> child.tick(h.player)); spoil.tick(h.player, ignored -> null);
            check(Boolean.TRUE.equals(spoil.receipt().get("all_proven_spoil_deposited"))
                    && Boolean.FALSE.equals(spoil.receipt().get("outcome_uncertain")), "前置仓库存入必须先有完整、确定的回执");

            task.tick(h.player);
            // 同时给出缺料和真实进展，验证不能因这些条件满足就跳过世界变更未知并再次取料。
            field(task, "activeChild").set(task, new Receipt(TaskState.FAILED, Map.of("failure_code", "material_exhausted",
                    "cleared", 1, "world_change_uncertain", true, "outcome_uncertain", false)));
            check(task.tick(h.player) == TaskState.FAILED, "带有未知放置的缺料回执不能自动进入下一轮补料");
            var result = task.result(TaskState.FAILED);
            check(Boolean.TRUE.equals(result.data().get("outcome_uncertain")) && Boolean.TRUE.equals(result.data().get("world_change_uncertain"))
                    && Boolean.FALSE.equals(result.data().get("goal_satisfied")), "总回执保留施工未知，不被存入的 false 覆盖");
            check(((Map<?, ?>) result.data().get("excavation_spoil")).get("confirmed_deposited").equals(Map.of("minecraft:dirt", 64))
                    && Boolean.TRUE.equals(((Map<?, ?>) result.data().get("last_build_evidence")).get("world_change_uncertain")),
                    "确定存入和未知放置两类证据同时保留，供上层决定如何恢复");
            check(!((SemanticMaterialSupplyCoordinator) field(task, "supply").get(task)).active(), "未知世界状态未解决前不重试取料");
        } finally { ScaffoldMaterials.store(null, previousScaffolds); }
        System.out.println("BuildSupplyUncertaintyTest: passed");
    }
    private static final class Receipt implements Task {
        private final TaskState state; private final Map<String, Object> data;
        Receipt(TaskState state, Map<String, Object> data) { this.state = state; this.data = data; }
        public TaskState tick(LocalPlayer player) { return state; }
        public void stop(LocalPlayer player, StopReason reason) {}
        public String name() { return "模拟阶段回执"; }
        public TaskResult result(TaskState terminal) { return new TaskResult(state == TaskState.SUCCESS, "模拟阶段已结束", false, false, data); }
    }
    private static Field field(Object target, String name) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
