// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.base;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.integration.machine.MachineMenu;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;
import org.maiwithu.maicraft.core.task.MouseButton;
import org.maiwithu.maicraft.core.task.interact.InteractAtTaskRecord;
import org.maiwithu.maicraft.core.task.menu.VisibleMenuSession;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 「走到方块前右键打开原生界面 → 交给子类菜单流」这类伴生任务的模板：
 * 相位推进、子任务派发与超时、方块在场检查、失败码拼装全部在这里，
 * 子类只提供目标方块位置与类型、输入准备、目标菜单判断和菜单流工厂。
 * 附魔台与切石机是当前两个实现；后续同类工序接入时优先复用而不是再抄一份相位机。
 *
 * <p>边界：本模板只覆盖「右键即开、菜单会话内瞬时完成」的工序。计时生产（如烹饪）
 * 需要额外的生产等待相位；机器观察锚定的设备走 MachineMenuOpenTask 的开门协议
 * （面对准星与机器身份绑定），都不在此列。
 *
 * <p>命名素材集中在 {@link Names}：失败码前缀、子任务编号中缀与方块通俗名词。
 * 注意失败码前缀（enchantment_/stonecutter_）与子任务编号中缀（enchant/stonecutter）
 * 历史上就不同，不要借"统一命名"之机改动对外可见的任务编号。
 */
public abstract class BlockMenuCompanionTask<R extends NativeSubmissionTaskRecord> extends AbstractCompanionTask<R> {
    private enum Phase { APPROACH, OPEN, WAIT_MENU, MENU_FLOW }

    /** 目标方块的命名素材；这些字符串会出现在失败码、子任务编号与回执里，改动即对外可见。 */
    public record Names(String codePrefix, String idPrefix, String targetNoun) {}

    private Phase phase = Phase.APPROACH;
    private final VisibleMenuSession travelMenu = new VisibleMenuSession();
    private BlockMenuFlow flow;
    private Task activeChild;
    private TaskRecord activeRecord;
    private boolean openRequested;
    private long waitMenuUntil;
    private String issue;
    private boolean inputsPrepared;

    protected BlockMenuCompanionTask(LocalPlayer player, R record) { super(player, record); }

    /** 目标方块的命名素材。 */
    protected abstract Names names();

    /** 要接近并打开的目标方块位置。 */
    protected abstract BlockPos targetPos();

    /** 目标方块类型；方块不再是它即判定丢失。 */
    protected abstract Block targetBlock();

    /** 子类专属的开局前置检查；返回失败码即以 INTERRUPTED 失败，返回 null 表示通过。 */
    protected abstract String additionalStartBlocker();

    /** 准备子类输入（盘点库存等）；失败时自行 failIssue 并返回 false。 */
    protected abstract boolean prepareInputs();

    /** 打开的菜单是不是本流程的目标菜单。 */
    protected abstract boolean isTargetMenu(AbstractContainerMenu menu);

    /** 目标菜单是否被占用：光标拿着物品，或工作格已被放入东西。 */
    protected abstract boolean targetMenuOccupied(AbstractContainerMenu menu);

    /** 目标菜单是否完全空置；cleanup 判断能否安全关闭自己打开的界面。 */
    protected abstract boolean targetMenuEmpty(AbstractContainerMenu menu);

    /** 用已确认可见且空置的目标菜单创建子类菜单流；menu 必然已通过 isTargetMenu。 */
    protected abstract BlockMenuFlow createFlow(AbstractContainerMenu menu);

    /** cleanup 关闭自开界面时的边界说明文案。 */
    protected abstract String boundaryLabel();

    /** 最近一次 failIssue 记下的失败码，供子类回执携带。 */
    protected final String issue() { return issue; }

    /** 当前菜单流的观察数据；未进入菜单阶段时为空映射。 */
    protected final Map<String, Object> flowData() {
        return flow == null ? Map.of() : flow.data();
    }

    @Override protected final void onStart() {
        // 目标有效性先检查；旧页面和鼠标物品交给首刻的公共准备，不在接单时提前拒绝加工。
        if (!targetPresent()) failIssue(names().codePrefix() + names().targetNoun() + "_missing_or_unloaded", FailureType.TARGET_LOST);
    }

    private void prepareAfterGui() {
        // 关页返料后再检查子类条件并盘点输入，不能沿用退料前的库存去误报缺料或开始加工。
        String blocker = additionalStartBlocker();
        if (blocker != null) { failIssue(blocker, FailureType.INTERRUPTED); return; }
        if (!targetPresent()) {
            failIssue(names().codePrefix() + names().targetNoun() + "_missing_or_unloaded", FailureType.TARGET_LOST);
            return;
        }
        if (prepareInputs()) inputsPrepared = true;
    }

    @Override protected final TaskState onTick() {
        var context = ClientRuntime.requireContext(player);
        if (!inputsPrepared) {
            if (!travelMenu.worldReady(context)) return TaskState.RUNNING;
            prepareAfterGui();
            if (issue != null) return TaskState.FAILED;
            if (!inputsPrepared) return TaskState.RUNNING;
        }
        if (flow != null) {
            // 目标方块消失只通知一次；已经进入安全归还后仍需让同一搬运子任务确认，不能每刻重新打断它。
            if (!targetPresent() && !flow.hasFailure()) {
                flow.abort(names().codePrefix() + names().targetNoun() + "_changed", FailureType.TARGET_LOST);
            }
            TaskState state = flow.tick(context);
            if (state == TaskState.FAILED) failIssue(flow.failure(), flow.failureType());
            return state;
        }
        if (!context.permitsNativeActions()) return failIssue(names().codePrefix() + "control_handed_over", FailureType.INTERRUPTED);
        if (!targetPresent()) return failIssue(names().codePrefix() + names().targetNoun() + "_missing_or_unloaded", FailureType.TARGET_LOST);
        if (activeChild != null) return tickChild();
        return switch (phase) {
            case APPROACH -> {
                if (!travelMenu.worldReady(context)) yield TaskState.RUNNING;
                if (player.distanceToSqr(Vec3.atCenterOf(targetPos())) <= 3.75 * 3.75) {
                    phase = Phase.OPEN; yield TaskState.RUNNING;
                }
                // 到目标方块附近只允许地面通行；不挖、搭路或自动建造另一台同类设备。
                yield startChild(new MoveToTaskRecord(r.getToolCallId() + "-" + names().idPrefix() + "-approach", deadline(1200),
                        (double) targetPos().getX(), (double) targetPos().getY(), (double) targetPos().getZ(), null,
                        false, false, TransportMode.GROUND, false, false, 2, 1));
            }
            case OPEN -> {
                if (!travelMenu.worldReady(context)) yield TaskState.RUNNING;
                openRequested = true;
                yield startChild(new InteractAtTaskRecord(r.getToolCallId() + "-" + names().idPrefix() + "-open", deadline(200),
                        MouseButton.RIGHT, targetPos(), 0, null, null, targetBlock()));
            }
            case WAIT_MENU -> {
                AbstractContainerMenu open = player.containerMenu;
                if (isTargetMenu(open)) {
                    if (targetMenuOccupied(open)) yield failIssue(names().codePrefix() + "opened_menu_already_occupied", FailureType.INTERRUPTED);
                    if (!context.menus().ensureVisible(context)) yield TaskState.RUNNING;
                    // 绑定这一张已可见且空置的原生菜单；后续换菜单都不能沿用旧操作。
                    MachineMenu.rememberNativeOpened(player, open, targetPos());
                    flow = createFlow(open);
                    phase = Phase.MENU_FLOW;
                    yield TaskState.RUNNING;
                }
                if (open != player.inventoryMenu)
                    yield failIssue(names().codePrefix() + "unexpected_menu_opened", FailureType.TARGET_LOST);
                if (player.level().getGameTime() >= waitMenuUntil)
                    yield failIssue(names().codePrefix() + "native_menu_did_not_open", FailureType.TIMED_OUT);
                yield TaskState.RUNNING;
            }
            case MENU_FLOW -> throw new IllegalStateException(names().codePrefix() + "menu_flow_missing");
        };
    }

    private TaskState startChild(TaskRecord record) {
        activeRecord = record; activeChild = TaskFactory.create(player, record); return TaskState.RUNNING;
    }

    private TaskState tickChild() {
        // 导航与原生右键各自检查超时；每次子任务结束都调用 result，避免把按键或未收尾操作留给下一阶段。
        TaskState state = player.level().getGameTime() >= activeRecord.getDeadlineGameTime() ? TaskState.TIMEOUT : runChild(activeChild);
        r.extendDeadlineTo(activeRecord.getDeadlineGameTime());
        if (state == null) return TaskState.RUNNING;
        if (state != TaskState.SUCCESS) activeChild.stop(player, Task.StopReason.REPLACED);
        var result = activeChild.result(state); activeChild = null; activeRecord = null;
        if (state != TaskState.SUCCESS) return failIssue(names().codePrefix() + phase.name().toLowerCase(Locale.ROOT)
                + "_failed: " + result.message(), state == TaskState.TIMEOUT ? FailureType.TIMED_OUT : FailureType.UNKNOWN);
        if (phase == Phase.APPROACH) phase = Phase.OPEN;
        else { phase = Phase.WAIT_MENU; waitMenuUntil = player.level().getGameTime() + 60; }
        return TaskState.RUNNING;
    }

    private boolean targetPresent() {
        return player.level().isLoaded(targetPos()) && player.level().getBlockState(targetPos()).is(targetBlock());
    }
    private long deadline(long ticks) {
        long deadline = player.level().getGameTime() + ticks; r.extendDeadlineTo(deadline); return deadline;
    }
    /** 记下失败码并结算失败；子类钩子（如输入准备）发现问题时也用它保持回执一致。 */
    protected final TaskState failIssue(String code, FailureType type) { issue = code; fail(code, type); return TaskState.FAILED; }

    @Override public void stop(LocalPlayer companion, Task.StopReason reason) {
        // 先把暂停传给正在控制身体的子任务；永久结束时 cleanup 再完成其 GUI 边界收尾。
        if (activeChild != null) activeChild.stop(player, reason);
        if (flow != null) flow.stop(reason);
        super.stop(companion, reason);
    }

    @Override protected void cleanup() {
        if (activeChild != null) {
            activeChild.stop(player, Task.StopReason.REPLACED); activeChild.result(TaskState.CANCELLED);
            activeChild = null; activeRecord = null;
        }
        if (flow != null) flow.cleanup();
        else if (openRequested && isTargetMenu(player.containerMenu) && targetMenuEmpty(player.containerMenu)) {
            // 尚未认领工作格就结束时，只能关闭自己打开且仍为空的界面；有人放入物品后原样保留。
            try {
                var context = ClientRuntime.requireContext(player);
                if (context.permitsNativeActions()) context.menus().closeForTaskBoundary(context, 40, boundaryLabel());
            } catch (RuntimeException ignored) { }
        }
        super.cleanup();
    }

    @Override public Map<String, Object> progress() {
        // 状态查询只组装已观察数据，不推进子任务或菜单；菜单进行中的细节由菜单流转发。
        var data = new LinkedHashMap<String, Object>(super.progress());
        data.put("phase", phase.name().toLowerCase(Locale.ROOT));
        data.putAll(flowData());
        return data;
    }
}
