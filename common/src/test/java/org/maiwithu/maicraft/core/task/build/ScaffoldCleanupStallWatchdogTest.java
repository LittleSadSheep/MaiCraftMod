// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.task.TaskState;

/** 清理阶段看门狗：台账与身体双冻结到阈值即放弃剩余支撑，交付照常完成且回执列出遗留。 */
public final class ScaffoldCleanupStallWatchdogTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            BlockPos target = h.player.blockPosition().above();
            BlockPos deepA = h.player.blockPosition().below(20);
            BlockPos deepB = h.player.blockPosition().below(21);
            var record = new BuildTaskRecord("stall-watchdog", 1000,
                    List.of(new BuildTaskRecord.Target(Blocks.AIR, Items.AIR, target, "room", null, null, null)), true);
            record.previewManaged(true);
            // 两块自有支撑登记进台账；世界状态与看门狗无关，冻结判据只看台账规模与身体位移。
            record.scaffoldLedger().confirmed(deepA, Blocks.COBBLESTONE.defaultBlockState());
            record.scaffoldLedger().confirmed(deepB, Blocks.COBBLESTONE.defaultBlockState());
            var task = new FirstPersonBuildCompanionTask(h.player, record);
            task.start(h.player);
            FieldAccess.setPhase(task, "SCAFFOLD_SELECT");
            FieldAccess.set(task, "scaffoldQueue", List.of(deepA, deepB));
            Method arm = FirstPersonBuildCompanionTask.class.getDeclaredMethod("armScaffoldWatch");
            arm.setAccessible(true); arm.invoke(task);
            // 把看门狗基准拨到阈值之外：此刻台账与身体都无变化，应当立即判死。
            FieldAccess.setLong(task, "scaffoldWatchTick", h.level.getGameTime() - 601);
            Method step = FirstPersonBuildCompanionTask.class.getDeclaredMethod("stepPhase");
            step.setAccessible(true);
            check(step.invoke(task) == TaskState.RUNNING, "看门狗放弃清理后转入最终状态验收而不是失败");
            int guard = 0;
            TaskState state = TaskState.RUNNING;
            while (state != TaskState.SUCCESS && guard++ < 50) state = (TaskState) step.invoke(task);
            check(state == TaskState.SUCCESS, "清理被放弃后交付必须照常完成");
            check(record.scaffoldLedger().snapshot().size() == 2, "放弃的支撑保留在台账里，不能凭空消失");
            var data = task.result(TaskState.SUCCESS).data();
            check(((Number) data.get("temporary_supports_remaining")).intValue() == 2,
                    "回执必须列出遗留临时支撑的数量");
            task.result(TaskState.SUCCESS);
        }
        System.out.println("ScaffoldCleanupStallWatchdogTest: passed");
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    /** 测试内的小反射助手：统一处理私有字段与方法访问；Phase 是私有嵌套枚举，按名字解析。 */
    private static final class FieldAccess {
        static void set(Object owner, String name, Object value) throws Exception {
            var field = FirstPersonBuildCompanionTask.class.getDeclaredField(name); field.setAccessible(true);
            field.set(owner, value);
        }
        static void setPhase(Object owner, String constant) throws Exception {
            var field = FirstPersonBuildCompanionTask.class.getDeclaredField("phase"); field.setAccessible(true);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object value = Enum.valueOf((Class<? extends Enum>) field.getType(), constant);
            field.set(owner, value);
        }
        static void setLong(Object owner, String name, long value) throws Exception {
            var field = FirstPersonBuildCompanionTask.class.getDeclaredField(name); field.setAccessible(true);
            field.setLong(owner, value);
        }
    }
}
