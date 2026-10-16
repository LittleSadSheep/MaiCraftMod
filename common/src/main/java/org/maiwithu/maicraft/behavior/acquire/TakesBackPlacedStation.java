// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 在刚摆下的工作台、石切台上做完就收回：先把做东西这一步做完（做成或做砸都算做完），再走到台子跟前挖下来、
 * 捡起掉出来的那台，从世界记忆里划掉这一格。真人在野外掏出工作台做完东西，也会顺手挖起来揣回包里。
 *
 * <p>被吃饭、自卫打断时手上那一步停住，回来接着做、接着收。收回只是顺手的事：收不回（挖不下来、掉的没捡到）
 * 记进 attempts，交出去的仍是做东西那一步的结论。
 */
final class TakesBackPlacedStation implements Action {

    private final Action run;
    private final WorkstationRecipe.Kind kind;
    /** 摆台那一步写下的落点；摆台没成就是空，没有要收的。 */
    private final AtomicReference<WorldPosition> placedAt;
    private final Permissions permissions;
    /** 发起任务的记账口；null 表示这次不记。 */
    private final TaskRecords records;
    private final SetsUpWorkstation placer;
    private final WorldMemory memory;
    /** 身上此刻有几台这种设施：收回后多出来才算捡回了包里。 */
    private final IntSupplier carried;
    /** 做东西那一步交出的结论；还没做完为 null。 */
    private ActionStatus result;
    /** 正在收回台子的动作；收完或不用收为 null。 */
    private Action takingBack;
    private int carriedBefore;

    TakesBackPlacedStation(Action run, WorkstationRecipe.Kind kind, AtomicReference<WorldPosition> placedAt,
            Permissions permissions, TaskRecords records, SetsUpWorkstation placer, WorldMemory memory,
            IntSupplier carried) {
        this.run = run;
        this.kind = kind;
        this.placedAt = placedAt;
        this.permissions = permissions;
        this.records = records;
        this.placer = placer;
        this.memory = memory;
        this.carried = carried;
    }

    // 做东西 → 做完收回台子 → 交出做东西那一步的结论。
    @Override public ActionStatus tick(TickContext tick) {
        if (result == null) {
            ActionStatus status = run.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            result = status;
            return startTakingBack();
        }
        if (takingBack == null) return result;
        ActionStatus status = takingBack.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        settle(status);
        takingBack.close();
        takingBack = null;
        return result;
    }

    // 做完了：摆台没成就没东西要收；收回的现场动作没接上，如实记一笔，台子留在原地。
    private ActionStatus startTakingBack() {
        WorldPosition at = placedAt.get();
        if (at == null || placer == null) return result;
        carriedBefore = carried.getAsInt();
        takingBack = placer.takeBack(at, permissions).orElse(null);
        if (takingBack == null) {
            attempt("没收回，台子留在 " + spot(at) + "：收回的现场动作还没接上");
            return result;
        }
        return ActionStatus.progressed();
    }

    // 收回的结局：那一格挖掉了就从记忆里划掉；捡回包里记一笔变化，挖掉了没捡到、没挖下来各记一笔试过的办法。
    private void settle(ActionStatus status) {
        WorldPosition at = placedAt.get();
        boolean dug = status instanceof ActionStatus.Done || placer.gone(at, kind.blockType());
        boolean pocketed = carried.getAsInt() > carriedBefore;
        String why = status instanceof ActionStatus.Failed failed ? "：" + failed.problem().headline() : "";
        if (dug) {
            memory.forget(MemoryKind.WORKSTATION, at);
        }
        if (dug && pocketed) {
            change("收回了临时摆的" + kind.displayName() + " " + spot(at));
        } else if (dug) {
            change("挖掉了临时摆的" + kind.displayName() + " " + spot(at) + "，掉出来的没捡回背包");
            attempt("挖下来了，掉出来的" + kind.displayName() + "没捡回背包" + why);
        } else {
            attempt("没收回，台子留在 " + spot(at) + why);
        }
    }

    private void change(String note) {
        if (records != null) records.change(new Change(Change.Kind.BLOCK_BROKEN, kind.blockType(), 1, note));
    }

    private void attempt(String whatHappened) {
        if (records != null) records.attempt("收回临时摆的" + kind.displayName(), whatHappened);
    }

    private static String spot(WorldPosition at) {
        return at.x() + ", " + at.y() + ", " + at.z();
    }

    // 被生存需求打断：正在收就停住收回，还在做就停住做东西那一步。
    @Override public void pause() {
        if (takingBack != null) takingBack.pause();
        else if (result == null) run.pause();
    }

    @Override public void close() {
        run.close();
        if (takingBack != null) takingBack.close();
    }

    @Override public Interruptibility interruptibility() {
        if (takingBack != null) return takingBack.interruptibility();
        return result == null ? run.interruptibility() : Interruptibility.BETWEEN_ACTIONS;
    }

    @Override public String describe() {
        return takingBack != null ? "收回临时摆的" + kind.displayName() + "：" + takingBack.describe() : run.describe();
    }
}
