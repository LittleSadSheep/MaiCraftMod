// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 在一台 Create 方块的界面里做一件事：腾出空手 → 走过去点开界面 → 界面里一步步做（由子类说）→ 关上界面。
 * 每刻最多点一下，上一下没结清就等；中途撒手时请游戏关一次界面，不把界面留在画面上。
 */
abstract class CreateMenuWork implements Action {

    private enum Stage { HAND, OPEN, WORK, CLOSE, FINISHED }

    protected final PlayerServices services;
    protected final BlockPos at;
    private final Permissions permissions;
    private final String machine;
    private Stage stage = Stage.HAND;
    private Action current;
    private MenuOpening opening;
    private OpenedMenu menu;
    private Problem problem;

    CreateMenuWork(PlayerServices services, BlockPos at, Permissions permissions, String machine) {
        this.services = Objects.requireNonNull(services, "services");
        this.at = at.immutable();
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.machine = Objects.requireNonNull(machine, "machine");
    }

    /**
     * 界面开着时的一刻：读数没同步过来时 reading 为空。做完了调 {@link #closeWith}；要等就给 running。
     */
    protected abstract ActionStatus work(ClientLevel level, OpenedMenu menu, Optional<MenuContent.Reading> reading);

    /** 界面里正在做什么，给描述用。 */
    protected abstract String doing();

    @Override public final ActionStatus tick(TickContext context) {
        return switch (stage) {
            case HAND -> hand(context);
            case OPEN -> open(context);
            case WORK -> menu.busy() ? ActionStatus.running() : work(context.player().level(), menu, menu.reading());
            case CLOSE -> close(context);
            case FINISHED -> problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
        };
    }

    // 腾出空手：手上拿着扳手右键是拆它，拿着方块会放下去；腾不出来就照原样去点。
    private ActionStatus hand(TickContext context) {
        if (current == null) {
            Optional<Action> empty = services.toMainhand().emptyMainhand();
            if (empty.isEmpty()) {
                stage = Stage.OPEN;
                return ActionStatus.progressed();
            }
            current = empty.get();
        }
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        current.close();
        current = null;
        if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
        stage = Stage.OPEN;
        return ActionStatus.progressed();
    }

    private ActionStatus open(TickContext context) {
        if (opening == null) {
            opening = new ClientMenuOpening(at, permissions, services.menuLayouts(), services.approach(),
                    services.interactions(), services.context());
        }
        ActionStatus status = opening.tick(context);
        if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
        if (!(status instanceof ActionStatus.Done)) return status;
        menu = opening.opened().orElse(null);
        if (menu == null) return finish(Problem.of(Problem.Kind.TARGET_GONE, machine + "的界面没打开"));
        stage = Stage.WORK;
        return ActionStatus.progressed();
    }

    private ActionStatus close(TickContext context) {
        if (current == null) current = menu.closing();
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        current = null;
        return finish(problem);
    }

    /** 界面里的事做完了（reason 为空）或做不下去了：关上界面再收场。 */
    protected final ActionStatus closeWith(Problem reason) {
        problem = reason;
        stage = Stage.CLOSE;
        return ActionStatus.progressed();
    }

    private ActionStatus finish(Problem reason) {
        problem = reason;
        stage = Stage.FINISHED;
        return reason == null ? ActionStatus.done() : ActionStatus.failed(reason);
    }

    /** 容器那一侧第 index 格（按界面布局证明的顺序）的界面格号与快照。 */
    protected static int containerSlot(MenuContent.Reading reading, int index) {
        return reading.containerSlotIds().get(index);
    }

    protected static SlotSnapshot inside(MenuContent.Reading reading, int index) {
        return reading.containerSnapshots().get(index);
    }

    @Override public void pause() {
        if (current != null) current.pause();
        if (opening != null && menu == null) opening.pause();
    }

    // 中途收场：界面还开着就请游戏关一次。
    @Override public void close() {
        if (current != null) current.close();
        if (menu != null && stage != Stage.FINISHED) menu.abandon();
        else if (opening != null && menu == null) opening.close();
    }

    @Override public Interruptibility interruptibility() {
        return current != null ? current.interruptibility() : Interruptibility.WORKING;
    }

    @Override public String describe() {
        return switch (stage) {
            case HAND -> "腾出空手去开" + machine;
            case OPEN -> opening == null ? "走去点开" + machine : opening.describe();
            case WORK -> doing();
            case CLOSE -> "关上" + machine + "的界面";
            case FINISHED -> machine + "那边做完了";
        };
    }
}
