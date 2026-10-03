// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.task.lighting.SemanticLightAreaCompanionTask;
import org.maiwithu.maicraft.core.task.lighting.SemanticLightAreaTaskRecord;
import org.maiwithu.maicraft.core.task.lighting.TorchLightingPass;
import org.maiwithu.maicraft.core.tools.work.SemanticLightAreaApi;
import org.maiwithu.maicraft.intent.SemanticResultView;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.AfterNavigationAction;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;

/** 全区实测必须看见最后一个暗角；前一盏灯已经覆盖的候选不得再次消耗。 */
public final class AreaLightingTest {
    public static void main(String[] args) throws Exception {
        var arguments = JsonParser.parseString("{\"center_x\":4,\"center_y\":1,\"center_z\":4,\"radius\":2}").getAsJsonObject();
        var record = SemanticLightAreaApi.newRecord(new ToolContext("area-light", 0), arguments);
        check(record.coverage == SemanticLightAreaTaskRecord.Coverage.ALL && record.minimumLight == 8,
                "默认要求全部地面亮度八");
        try (var h = new InteractionWorldTestHarness()) {
            AutomaticLightingTest.prepareBody(h);
            h.level.blockLight = 8;
            h.level.blockLightByCell = Map.of(new BlockPos(5, 1, 4), 7);
            var task = new SemanticLightAreaCompanionTask(h.player, record); task.start(h.player);
            var boundary = ActorControlTestHarness.field(SemanticLightAreaCompanionTask.class, "areaBoundaryVerified");
            TaskState state = TaskState.RUNNING;
            for (int i = 0; i < 5 && !boundary.getBoolean(task); i++) { state = task.tick(h.player); h.nextTick(); }
            check(boundary.getBoolean(task) && state == TaskState.RUNNING, "只剩一个暗格也不能宣布全覆盖");
            var dataMethod = SemanticLightAreaCompanionTask.class.getDeclaredMethod("resultData"); dataMethod.setAccessible(true);
            @SuppressWarnings("unchecked") var data = (Map<String, Object>) dataMethod.invoke(task);
            @SuppressWarnings("unchecked") var observation = (Map<String, Object>) data.get("lighting_observation");
            check(((List<?>) observation.get("dark_cells")).size() == 1
                    && SemanticResultView.data(data).get("lighting_observation").equals(observation), "暗角证据经过任务投影仍完整");
            // 已有灯光照亮整个候选覆盖区时，快速放置轮直接跳过，不动副手、不走到灯位再放一支。
            h.level.blockLightByCell = null;
            var target = new BuildTaskRecord.Target(Blocks.TORCH.defaultBlockState(), Items.TORCH,
                    new BlockPos(6, 1, 4), "torch", null, null, null);
            var build = new BuildTaskRecord("lighting-skip", 600, List.of(target), false, true, false);
            var pass = new TorchLightingPass(h.player, build, List.of(new BlockPos(5, 1, 4)), 8, Set.of(), Set.of());
            pass.start(h.player);
            check(pass.tick(h.player) == TaskState.SUCCESS && h.blockUses() == 0, "实测足够亮时省下候选火把");
            var verified = new SemanticLightAreaCompanionTask(h.player,
                    SemanticLightAreaApi.newRecord(new ToolContext("area-lit", 0), arguments));
            verified.start(h.player); state = TaskState.RUNNING;
            for (int i = 0; i < 5 && !state.isTerminal(); i++) { state = verified.tick(h.player); h.nextTick(); }
            check(state == TaskState.SUCCESS, "全部实测达到八才验收成功");
        }
        try (var h = new InteractionWorldTestHarness()) {
            AutomaticLightingTest.prepareBody(h);
            h.inventory.setItem(40, new ItemStack(Items.TORCH, 8));
            var target = new BuildTaskRecord.Target(Blocks.TORCH.defaultBlockState(), Items.TORCH,
                    new BlockPos(5, 1, 4), "torch", null, null, null);
            var build = new BuildTaskRecord("lighting-native", 600, List.of(target), false, true, false);
            var pass = new TorchLightingPass(h.player, build, List.of(new BlockPos(6, 1, 4)), 8, Set.of(), Set.of());
            pass.start(h.player); pass.tick(h.player);
            pass.stop(h.player, Task.StopReason.PREEMPTED);
            AfterNavigationAction.run(ClientRuntime.requireContext(h.player));
            check(h.blockUses() == 0, "暂停撤销尚未执行的帧末点击");
            h.nextTick(); pass.tick(h.player); AfterNavigationAction.run(ClientRuntime.requireContext(h.player));
            check(h.blockUses() == 1 && h.mode.usedHand == InteractionHand.OFF_HAND, "恢复后只用副手出手一次");
            h.set(target.pos(), target.desiredState()); h.player.getOffhandItem().shrink(1);
            h.level.acknowledgedSequence = h.level.blockSequence;
            TaskState state = TaskState.RUNNING;
            for (int i = 0; i < 4 && !state.isTerminal(); i++) { h.nextTick(); state = pass.tick(h.player); }
            check(state == TaskState.SUCCESS && build.placed() == 1 && h.blockUses() == 1,
                    "原生确认后记录一支灯，重复 tick 不重复扣料");
            pass.result(state);
        }
        System.out.println("AreaLightingTest: passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
