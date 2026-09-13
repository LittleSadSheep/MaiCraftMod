// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.act.BlockDigger;
import org.maiwithu.maicraft.task.TaskState;

/** 注入已提交回执后使用真实原生确认端口轮询，验证空气同步和操作确认分离；测试不发送挖掘动作。 */
public final class BuildScaffoldCleanupReceiptTest {
    private static final BlockPos TARGET = new BlockPos(6, 1, 6);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        airWaitsForItsOwnedReceiptAndAdvancesOnce();
        externalAirDoesNotCountAsOurRemoval();
        rejectedAndMissingReceiptsKeepOwnership();
        System.out.println("BuildScaffoldCleanupReceiptTest: passed");
    }

    private static void airWaitsForItsOwnedReceiptAndAdvancesOnce() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var task = task(h); var record = record(task);
            var verdict = new AtomicReference<>(NativeConfirmation.Verdict.PENDING);
            NativeActionReceipt receipt = receipt(h, task, context -> verdict.get());
            h.set(TARGET, Blocks.AIR.defaultBlockState());
            for (int tick = 0; tick < 3; tick++) {
                check(step(task) == TaskState.RUNNING && record.scaffoldLedger().contains(TARGET)
                        && record.broken() == 0 && number(task, "scaffoldAt") == 0 && number(task, "scaffoldConfirmedRemovals") == 0,
                        "空气先同步时保留账和当前目标，不能提前删除或累计拆除");
                h.nextTick();
            }
            // 观察到原生已应用后仍需满足不同客户端刻的稳定确认；同刻多次查询不能凑满两刻。
            verdict.set(NativeConfirmation.Verdict.APPLIED);
            check(step(task) == TaskState.RUNNING && !receipt.terminal() && record.scaffoldLedger().contains(TARGET),
                    "第一刻应用观察仍等待原生稳定确认");
            step(task);
            check(!receipt.terminal() && number(task, "scaffoldAt") == 0, "同一刻重复轮询不得提前推进");
            h.nextTick();
            check(step(task) == TaskState.RUNNING && receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED,
                    "原生确认完成后才执行回收推进");
            check(!record.scaffoldLedger().contains(TARGET) && record.broken() == 1 && number(task, "scaffoldAt") == 1
                    && number(task, "scaffoldConfirmedRemovals") == 1 && number(task, "scaffoldPassRemovals") == 1,
                    "确认后恰好删一条账、记一次拆除并为下一轮提供真实进展");
            h.nextTick(); invoke(task, "scaffoldSelectTick");
            check(record.broken() == 1 && number(task, "scaffoldAt") == 1 && number(task, "scaffoldConfirmedRemovals") == 1,
                    "后续调度不重复累计已消费的回执");
            check(h.blockUses() == 0 && h.itemUses() == 0, "等待确认只轮询，不补发任何原生动作");
        }
    }

    private static void externalAirDoesNotCountAsOurRemoval() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var task = task(h); h.set(TARGET, Blocks.AIR.defaultBlockState());
            check(step(task) == TaskState.RUNNING && !record(task).scaffoldLedger().contains(TARGET)
                    && number(task, "scaffoldAt") == 1 && record(task).broken() == 0
                    && number(task, "scaffoldConfirmedRemovals") == 0 && number(task, "scaffoldPassRemovals") == 0,
                    "没有本任务破坏记录的空气只解除已移除账，不算自己的拆除成果");
        }
    }

    private static void rejectedAndMissingReceiptsKeepOwnership() throws Exception {
        for (NativeActionReceipt.Status status : List.of(NativeActionReceipt.Status.CONFIRMED_NOT_APPLIED,
                NativeActionReceipt.Status.DIVERGED, NativeActionReceipt.Status.UNCERTAIN)) {
            try (var h = new InteractionWorldTestHarness()) {
                var task = task(h); NativeActionReceipt receipt = receipt(h, task, context -> NativeConfirmation.Verdict.PENDING);
                var finish = NativeActionReceipt.class.getDeclaredMethod("finish", NativeActionReceipt.Status.class, String.class);
                finish.setAccessible(true); finish.invoke(receipt, status, "模拟原生未确认成功");
                h.set(TARGET, Blocks.AIR.defaultBlockState());
                check(step(task) == TaskState.FAILED && record(task).scaffoldLedger().contains(TARGET)
                        && number(task, "scaffoldAt") == 0 && record(task).broken() == 0 && number(task, "scaffoldConfirmedRemovals") == 0,
                        "未应用、分歧或不确定的回执必须停下，不能因空气已出现而当作成功");
            }
        }
        try (var h = new InteractionWorldTestHarness()) {
            var task = task(h); field(digger(task), "pos").set(digger(task), TARGET); h.set(TARGET, Blocks.AIR.defaultBlockState());
            check(step(task) == TaskState.FAILED && record(task).scaffoldLedger().contains(TARGET) && record(task).broken() == 0,
                    "只选定目标却没有提交回执时，不能把目标消失算成自己挖成");
        }
    }

    private static FirstPersonBuildCompanionTask task(InteractionWorldTestHarness h) throws Exception {
        h.player.inventoryMenu.setCarried(ItemStack.EMPTY); h.set(TARGET, Blocks.DIRT.defaultBlockState());
        var record = new BuildTaskRecord("scaffold-receipt", 1000, List.of(), false);
        record.scaffoldLedger().confirmed(TARGET, Blocks.DIRT.defaultBlockState());
        var task = new FirstPersonBuildCompanionTask(h.player, record);
        field(task, "scaffold").set(task, TARGET); field(task, "scaffoldQueue").set(task, List.of(TARGET));
        Field phase = field(task, "phase");
        for (Object state : phase.getType().getEnumConstants()) if (state.toString().equals("SCAFFOLD_BREAK")) phase.set(task, state);
        return task;
    }
    private static NativeActionReceipt receipt(InteractionWorldTestHarness h, Object task, NativeConfirmation confirmation) throws Exception {
        LocalPlayerContext context = ClientRuntime.requireContext(h.player);
        var constructor = NativeActionReceipt.class.getDeclaredConstructor(NativeActionReceipt.Kind.class, LocalPlayerContext.class,
                int.class, int.class, NativeConfirmation.class, BlockPos.class, Direction.class);
        constructor.setAccessible(true);
        NativeActionReceipt receipt = constructor.newInstance(NativeActionReceipt.Kind.BREAK_BLOCK, context, 40, 2, confirmation, TARGET, Direction.UP);
        // 仅恢复一份测试用已提交动作到真实确认端口，不调用 startDestroyBlock 或世界写入接口。
        var install = context.actions().getClass().getDeclaredMethod("install", NativeActionReceipt.class); install.setAccessible(true);
        install.invoke(context.actions(), receipt); field(digger(task), "pos").set(digger(task), TARGET); field(digger(task), "receipt").set(digger(task), receipt);
        return receipt;
    }
    private static BlockDigger digger(Object task) throws Exception { return (BlockDigger) field(task, "digger").get(task); }
    private static BuildTaskRecord record(Object task) throws Exception { return (BuildTaskRecord) field(task, "r").get(task); }
    private static int number(Object task, String name) throws Exception { return field(task, name).getInt(task); }
    private static TaskState step(Object task) throws Exception { return (TaskState) invoke(task, "scaffoldBreakTick"); }
    private static Object invoke(Object task, String name) throws Exception { var method = task.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(task); }
    private static Field field(Object instance, String name) throws Exception {
        for (Class<?> type = instance.getClass(); type != null; type = type.getSuperclass()) try {
            Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
        } catch (NoSuchFieldException inherited) { }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
