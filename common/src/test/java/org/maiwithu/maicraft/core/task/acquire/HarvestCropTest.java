// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 验证成熟度、冻结范围、保护和补种收尾；夹具只注入采收结果，不伪称已经完成实机农田操作。 */
public final class HarvestCropTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        matureAndProtected(); ownershipUntilReplant(); sourcePermissions();
        System.out.println("HarvestCropTest: passed");
    }
    private static void matureAndProtected() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var at = new BlockPos(3, 1, 3); var carrots = (CropBlock) Blocks.CARROTS;
            h.set(at.below(), Blocks.FARMLAND.defaultBlockState()); h.set(at, carrots.getStateForAge(carrots.getMaxAge()));
            check(HarvestCropCompanionTask.permitted(h.player, at, h.player.blockPosition(), 8), "a loaded mature field is a harvest candidate");
            check(!HarvestCropCompanionTask.permitted(h.player, at, h.player.blockPosition(), 1), "walking cannot widen the frozen field radius");
            check(!NavigationSafetyContext.withProtectedArea(List.of(at), List.of(),
                    () -> HarvestCropCompanionTask.permitted(h.player, at, h.player.blockPosition(), 8)), "protected crops remain untouched");
            h.set(at, carrots.getStateForAge(2));
            check(!HarvestCropCompanionTask.permitted(h.player, at, h.player.blockPosition(), 8), "unripe plants are not food stock");
            h.set(at, carrots.getStateForAge(carrots.getMaxAge())); h.set(at.below(), Blocks.DIRT.defaultBlockState());
            check(!HarvestCropCompanionTask.permitted(h.player, at, h.player.blockPosition(), 8), "harvest requires a real replantable field");
            check(HarvestCropCompanionTask.supports(Items.WHEAT) && !HarvestCropCompanionTask.supports(Items.BREAD),
                    "wheat is harvested while bread still requires its actual crafting process");
        }
    }
    private static void ownershipUntilReplant() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var at = new BlockPos(3, 1, 3); var carrots = (CropBlock) Blocks.CARROTS;
            h.set(at.below(), Blocks.FARMLAND.defaultBlockState());
            var record = new HarvestCropTaskRecord("crop-settlement", 1000, List.of(Items.CARROT), 2, h.player.blockPosition(), 8);
            var task = new HarvestCropCompanionTask(h.player, record);
            set(task, "at", at); set(task, "crop", HarvestCropCompanionTask.mature(carrots.getStateForAge(carrots.getMaxAge())));
            set(task, "harvest", new Task() {
                public TaskState tick(LocalPlayer player) { return TaskState.SUCCESS; }
                public void stop(LocalPlayer player, StopReason reason) { /* 模拟采收已停止。 */ }
                public TaskResult result(TaskState state) { return TaskResult.ok("fixture harvest", Map.of("confirmed_source_breaks", 1)); }
                public String name() { return "settled crop fixture"; }
            });
            h.inventory.setItem(0, new ItemStack(Items.CARROT, 3));
            check(task.mustSettleBeforeSatisfiedCancellation(), "predicted or collected food cannot cancel its pending replant responsibility");
            task.requestSatisfiedSettlement();
            check(task.tick(h.player) == TaskState.RUNNING && task.mustSettleBeforeSatisfiedCancellation(), "harvest settlement yields to replanting before success");
            // 补种现场被改成实体方块时停止；不继续挖下一株，也不把库存已经够数当成成功。
            h.set(at, Blocks.STONE.defaultBlockState());
            check(task.tick(h.player) == TaskState.FAILED, "a changed planting site stops the owned harvest");
            var result = task.result(TaskState.FAILED);
            check(Boolean.TRUE.equals(result.data().get("replant_pending")) && result.data().get("replanted_crops").equals(0)
                    && h.blockUses() == 0 && h.inventory.getItem(0).getCount() == 3, "uncompleted replanting stays explicit with no fabricated seed consumption");
        }
    }
    private static void sourcePermissions() {
        var carrot = ResourceLocation.parse("minecraft:carrot");
        var restricted = new SemanticAcquireTaskRecord("loose-only", 1000, List.of(carrot), 2,
                List.of(Source.NEARBY), false, SemanticAcquireTaskRecord.SourceHint.empty(), List.of(), 16);
        check(!restricted.allowedSources.contains(Source.HARVEST), "loose-item pickup never implies permission to break crops");
        var need = new AcquisitionNeed(List.of(carrot), 2, 0, Set.of(carrot), Set.of(), Set.of(), List.of(Source.TRADE, Source.HARVEST));
        check(AcquisitionSources.order(need, new AcquisitionSources.Readiness(false, false, false, false)).getFirst() == Source.HARVEST,
                "permitted mature field collection precedes merchant trading");
    }
    private static void set(Object value, String name, Object content) throws Exception {
        Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); field.set(value, content);
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
