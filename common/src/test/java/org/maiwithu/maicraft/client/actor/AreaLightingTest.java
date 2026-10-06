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
            h.nextTick();
            // 补光转向要按正常角速度推进；夹具像真实客户端那样在游戏刻之间喂渲染帧，直到它真的出手。
            for (int i = 0; i < 60 && h.blockUses() == 0; i++) {
                pass.tick(h.player);
                AfterNavigationAction.run(ClientRuntime.requireContext(h.player));
                h.renderFrames(2);
                if (h.blockUses() > 0) break;
                h.nextTick();
            }
            check(h.blockUses() == 1 && h.mode.usedHand == InteractionHand.OFF_HAND, "恢复后只用副手出手一次");
            h.set(target.pos(), target.desiredState()); h.player.getOffhandItem().shrink(1);
            h.level.acknowledgedSequence = h.level.blockSequence;
            TaskState state = TaskState.RUNNING;
            for (int i = 0; i < 4 && !state.isTerminal(); i++) { h.nextTick(); state = pass.tick(h.player); }
            check(state == TaskState.SUCCESS && build.placed() == 1 && h.blockUses() == 1,
                    "原生确认后记录一支灯，重复 tick 不重复扣料");
            pass.result(state);
        }
        // 006 局实机回归：草原营地在手 48 支火把却零放置零调查，no_support 收场两次复现。
        // 根因是候选资格按 canBeReplaced 选中草丛格，副手放置闸却要求严格空气，每个灯位
        // 都在提交前被拒，放置轮空转至停滞后再以"无新增亮格"的同一文案失败。
        try (var h = new InteractionWorldTestHarness()) {
            AutomaticLightingTest.prepareBody(h);
            h.level.blockLight = 8;
            h.level.blockLightByCell = Map.of(new BlockPos(4, 1, 4), 2);
            var torchState = Blocks.TORCH.defaultBlockState();
            BlockPos grassCell = new BlockPos(5, 1, 4);
            h.set(new BlockPos(5, 0, 4), Blocks.GRASS_BLOCK.defaultBlockState());
            h.set(grassCell, Blocks.TALL_GRASS.defaultBlockState());
            // 两道闸必须说同一种话：资格闸接受的可替换格，出手闸也必须允许提交。
            check(org.maiwithu.maicraft.core.task.lighting.RoutineTorchPlacement.usable(
                    h.player, grassCell, torchState, grassCell.below(),
                    net.minecraft.core.Direction.UP, Set.of()),
                    "草丛格应与资格闸一致地被判定为合法立地灯位");
            h.set(grassCell, Blocks.STONE.defaultBlockState());
            check(!org.maiwithu.maicraft.core.task.lighting.RoutineTorchPlacement.usable(
                    h.player, grassCell, torchState, grassCell.below(),
                    net.minecraft.core.Direction.UP, Set.of()),
                    "被实体方块占用的格子仍不是灯位");
            h.set(grassCell, Blocks.TALL_GRASS.defaultBlockState());
            h.inventory.setItem(40, new ItemStack(Items.TORCH, 8));
            var target = new BuildTaskRecord.Target(torchState, Items.TORCH, grassCell, "torch", null, null, null);
            var build = new BuildTaskRecord("lighting-grass", 600, List.of(target), false, true, false);
            var pass = new TorchLightingPass(h.player, build, List.of(new BlockPos(4, 1, 4)), 8, Set.of(), Set.of());
            pass.start(h.player);
            // 草丛灯位同样要等补光平滑转到它；逐刻喂渲染帧直到真实出手。
            for (int i = 0; i < 60 && h.blockUses() == 0; i++) {
                pass.tick(h.player);
                AfterNavigationAction.run(ClientRuntime.requireContext(h.player));
                h.renderFrames(2);
                if (h.blockUses() > 0) break;
                h.nextTick();
            }
            check(h.blockUses() == 1, "草丛灯位必须真实提交副手放置，不能静默拒绝空转到停滞");
            h.set(grassCell, torchState);
            h.player.getOffhandItem().shrink(1);
            h.level.acknowledgedSequence = h.level.blockSequence;
            TaskState state = TaskState.RUNNING;
            for (int i = 0; i < 4 && !state.isTerminal(); i++) { h.nextTick(); state = pass.tick(h.player); }
            check(state == TaskState.SUCCESS && build.placed() == 1 && h.blockUses() == 1,
                    "草丛格上的原生确认结算为一支灯，不重复扣料");
            pass.result(state);
        }
        // 005 局 141 实机回归：半径扩大后最近灯位贴着农田/水体，到达后被资格闸否决，旧形态里
        // 放置轮在原地反复重试到停滞，其余候选从未尝试，整批零出手。现在单个灯位被闸淘汰后
        // 换下一候选继续，拒绝明细（坐标+闸名）随回执交付；导航不可达走同一淘汰路径。
        try (var h = new InteractionWorldTestHarness()) {
            AutomaticLightingTest.prepareBody(h);
            // 本用例的导航走真实寻路栈，测试桩需要给 baritone 一个可写目录才能完成运行时初始化。
            var gameDirectory = net.minecraft.client.Minecraft.class.getDeclaredField("gameDirectory");
            gameDirectory.setAccessible(true);
            gameDirectory.set(net.minecraft.client.Minecraft.getInstance(),
                    new java.io.File("area-lighting-gate-fixture"));
            h.inventory.setItem(40, new ItemStack(Items.TORCH, 8));
            var torchState = Blocks.TORCH.defaultBlockState();
            // 最近灯位被实体方块占用，到位后 stillUsable 否决；次近灯位正常可放。
            BlockPos occupied = new BlockPos(1, 1, 3);
            BlockPos reachable = new BlockPos(5, 1, 4);
            h.set(occupied, Blocks.STONE.defaultBlockState());
            var targets = List.of(
                    new BuildTaskRecord.Target(torchState, Items.TORCH, occupied, "torch", null, null, null),
                    new BuildTaskRecord.Target(torchState, Items.TORCH, reachable, "torch", null, null, null));
            var build = new BuildTaskRecord("lighting-gate-rejected", 1200, targets, false, true, false);
            var pass = new TorchLightingPass(h.player, build, List.of(new BlockPos(2, 1, 2)), 8,
                    Set.of(), Set.of());
            pass.start(h.player);
            TaskState state = TaskState.RUNNING;
            for (int i = 0; i < 300 && !state.isTerminal(); i++) {
                state = pass.tick(h.player);
                AfterNavigationAction.run(ClientRuntime.requireContext(h.player));
                // 这里的补光转向也要按真实帧率推进，不然镜头永远到不了灯位。
                h.renderFrames(2);
                if (h.blockUses() == 1) {
                    h.set(reachable, torchState);
                    h.player.getOffhandItem().shrink(1);
                    h.level.acknowledgedSequence = h.level.blockSequence;
                }
                h.nextTick();
            }
            check(pass.rejections().size() == 1
                    && pass.rejections().get(0).gate().equals("site_not_usable")
                    && pass.rejections().get(0).pos().equals(occupied),
                    "被资格闸否决的最近灯位记为单点闸拒绝，携带坐标与闸名");
            check(state == TaskState.SUCCESS && build.placed() == 1 && h.blockUses() == 1,
                    "被否决灯位淘汰后换下一候选真实出手，整轮不再零放置连坐");
            @SuppressWarnings("unchecked") var data = (Map<String, Object>) pass.result(state).data();
            check(((List<?>) data.get("rejected_sites")).size() == 1,
                    "放置轮回执携带逐灯位拒绝明细");
        }
        System.out.println("AreaLightingTest: passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
