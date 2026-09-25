package org.maiwithu.maicraft.core.task.build;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.act.BlockDigger;
import org.maiwithu.maicraft.task.TaskState;

/** 推进真实施工阶段，确认开工前的砖墙和开挖中途新出现的机器都先停手并留下可定位报告。 */
public final class BuildClearanceExecutionTest {
    private static final BlockPos AT = new BlockPos(5, 1, 5);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (int changeStage : List.of(0, 1, 2)) {
            boolean changedAfterPreflight = changeStage > 0;
            try (var h = new InteractionWorldTestHarness()) {
                var initial = changedAfterPreflight ? Blocks.DIRT : Blocks.BRICKS;
                h.set(AT, initial.defaultBlockState());
                var target = new BuildTaskRecord.Target(Blocks.AIR, Items.AIR, AT, "room", null, null, null);
                var record = new BuildTaskRecord("clearance-execution", 1000, List.of(target), true);
                record.previewManaged(true);
                var task = new FirstPersonBuildCompanionTask(h.player, record);
                task.start(h.player);
                TaskState state = invoke(task, "preflightTick");
                if (changedAfterPreflight) {
                    check(state == TaskState.RUNNING, "natural dirt may reach excavation");
                    invoke(task, "excavationTick");
                    // 现场在路径/工具准备前换成箱子；真实 clearNavTick 必须先拒绝，不能开始挖掘或取工具。
                    h.set(AT, Blocks.CHEST.defaultBlockState());
                    // 接近站位前和真正下手前都要拦住新箱子，不能依赖前一阶段已经做过检查。
                    check(invoke(task, changeStage == 1 ? "clearNavTick" : "clearTick") == TaskState.RUNNING,
                            "new obstacle enters read-only reporting");
                    state = invoke(task, "clearanceReportTick");
                }
                check(state == TaskState.FAILED && record.broken() == 0 && record.placed() == 0,
                        "blocked construction performs no demolition or placement");
                var diggerField = task.getClass().getDeclaredField("digger"); diggerField.setAccessible(true);
                var digger = (BlockDigger) diggerField.get(task);
                check(digger.current() == null && !digger.hasPendingBreak(), "no native break remains pending");
                var data = task.result(state).data();
                check(BuildClearanceSurvey.FAILURE.equals(data.get("failure_code"))
                        && Boolean.FALSE.equals(data.get("mechanical_retry_allowed")), "failure goes back to site selection");
                var report = (Map<?, ?>) data.get("clearance_report");
                var obstacle = (Map<?, ?>) ((List<?>) report.get("obstacles")).getFirst();
                check(obstacle.get("at").equals(List.of(5, 1, 5)), "stopped obstruction has exact coordinates");
                check(h.level.getBlockState(AT).is(changedAfterPreflight ? Blocks.CHEST : Blocks.BRICKS),
                        "the non-whitelisted obstacle remains intact");
            }
        }
        System.out.println("BuildClearanceExecutionTest: passed");
    }

    private static TaskState invoke(Object task, String name) throws Exception {
        var method = task.getClass().getDeclaredMethod(name); method.setAccessible(true);
        return (TaskState) method.invoke(task);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
