// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * 往样板供应器里放一张样板或拿出一张：腾出空手 → 走过去点开它的界面 → 看样板格里有哪些
 * → 要放进去就把背包里产出那样东西的样板快速移动过去，要拿出来就把样板格里那张快速移动回背包
 * → 样板格变成要的样子才算做成 → 关上界面。看见的样板格记下来给读回用。
 *
 * <p>本来就是要的样子不动；背包里没有那张样板、样板格满了都如实说，不硬塞。
 */
final class PatternChange implements Action {

    private enum Stage { HAND, OPEN, READ, WAIT, CLOSE, FINISHED }

    /** 界面开了以后等样板格同步过来、快速移动之后等它挪过去，各最多这么多刻。 */
    private static final int SYNC_WAIT_TICKS = 40;

    private final Ae2Compat compat;
    private final PlayerServices services;
    private final BlockPos at;
    private final String output;
    private final boolean wantInside;
    private final Permissions permissions;
    /** 每台样板供应器上次打开界面时样板格里样板的产出。 */
    private final Map<BlockPos, List<String>> seen;

    private Stage stage = Stage.HAND;
    private Action current;
    private MenuOpening opening;
    private OpenedMenu menu;
    private int waited;
    private Problem problem;

    PatternChange(Ae2Compat compat, PlayerServices services, BlockPos at, String output, boolean wantInside,
            Permissions permissions, Map<BlockPos, List<String>> seen) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.at = at.immutable();
        this.output = Objects.requireNonNull(output, "output");
        this.wantInside = wantInside;
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.seen = Objects.requireNonNull(seen, "seen");
    }

    @Override public ActionStatus tick(TickContext context) {
        return switch (stage) {
            case HAND -> hand(context);
            case OPEN -> open(context);
            case READ -> read(context.player().level());
            case WAIT -> await(context.player().level());
            case CLOSE -> close(context);
            case FINISHED -> problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
        };
    }

    // 腾出空手：手上拿着扳手右键是拆它，拿着别的有时会被当成往上放东西；腾不出来就照原样去点。
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
        if (menu == null) return finish(Problem.of(Problem.Kind.TARGET_GONE, "样板供应器的界面没打开"));
        stage = Stage.READ;
        return ActionStatus.progressed();
    }

    // 读样板格：已经是要的样子就关界面；要放进去就挑背包里产出它的那张，要拿出来就挑样板格里那张，快速移动过去。
    private ActionStatus read(ClientLevel level) {
        Optional<MenuContent.Reading> reading = menu.reading();
        if (reading.isEmpty()) {
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            return closeWith(Problem.of(Problem.Kind.STUCK, "样板供应器的界面开了，样板格一直没同步过来"));
        }
        List<String> inside = outputs(reading.get().containerSnapshots(), level);
        seen.put(at, inside);
        if (inside.contains(output) == wantInside) return closeWith(null);
        int slot = wantInside ? carriedSlot(reading.get(), level) : insideSlot(reading.get(), level);
        if (slot < 0) {
            return closeWith(Problem.of(Problem.Kind.NEED_ITEM, "背包里没有产出 " + output + " 的样板"));
        }
        if (wantInside && reading.get().containerSnapshots().stream().noneMatch(SlotSnapshot::isEmpty)) {
            return closeWith(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "样板供应器的样板格都满了，放不进产出 " + output + " 的样板"));
        }
        // 发不出去（上一下没结清、本刻没交互机会）就下一刻再来，不算移动过。
        if (!menu.quickMove(slot)) return ActionStatus.running();
        waited = 0;
        stage = Stage.WAIT;
        return ActionStatus.progressed();
    }

    // 等样板挪过去：样板格变成要的样子就做成了；等不到如实说，不再移第二下。
    private ActionStatus await(ClientLevel level) {
        Optional<MenuContent.Reading> reading = menu.reading();
        if (reading.isPresent()) {
            List<String> inside = outputs(reading.get().containerSnapshots(), level);
            seen.put(at, inside);
            if (inside.contains(output) == wantInside) return closeWith(null);
        }
        if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
        return closeWith(Problem.of(Problem.Kind.STUCK, (wantInside ? "放进" : "拿出") + "产出 " + output
                + " 的样板之后，样板格一直没变成要的样子"));
    }

    private ActionStatus close(TickContext context) {
        if (current == null) current = menu.closing();
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        current = null;
        return finish(problem);
    }

    private List<String> outputs(List<SlotSnapshot> slots, ClientLevel level) {
        List<String> found = new ArrayList<>();
        for (SlotSnapshot slot : slots) {
            if (!slot.isEmpty()) compat.patternOutput(slot.stack(), level).ifPresent(found::add);
        }
        return found;
    }

    // 背包里产出那样东西的样板在哪一格（界面格号）；没有为 -1。
    private int carriedSlot(MenuContent.Reading reading, ClientLevel level) {
        for (int i = 0; i < reading.playerSnapshots().size(); i++) {
            SlotSnapshot slot = reading.playerSnapshots().get(i);
            if (!slot.isEmpty() && compat.patternOutput(slot.stack(), level).filter(output::equals).isPresent()) {
                return reading.playerSlotIds().get(i);
            }
        }
        return -1;
    }

    // 样板格里产出那样东西的样板在哪一格（界面格号）；没有为 -1。
    private int insideSlot(MenuContent.Reading reading, ClientLevel level) {
        for (int i = 0; i < reading.containerSnapshots().size(); i++) {
            SlotSnapshot slot = reading.containerSnapshots().get(i);
            if (!slot.isEmpty() && compat.patternOutput(slot.stack(), level).filter(output::equals).isPresent()) {
                return reading.containerSlotIds().get(i);
            }
        }
        return -1;
    }

    private ActionStatus closeWith(Problem reason) {
        problem = reason;
        stage = Stage.CLOSE;
        return ActionStatus.progressed();
    }

    private ActionStatus finish(Problem reason) {
        problem = reason;
        stage = Stage.FINISHED;
        return reason == null ? ActionStatus.done() : ActionStatus.failed(reason);
    }

    @Override public void pause() {
        if (current != null) current.pause();
        if (opening != null && menu == null) opening.pause();
    }

    // 中途收场：界面还开着就请游戏关一次，不把样板供应器的界面留在画面上。
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
            case HAND -> "腾出空手去开样板供应器";
            case OPEN -> opening == null ? "走去点开样板供应器" : opening.describe();
            case READ, WAIT -> (wantInside ? "把产出 " : "拿出产出 ") + output + " 的样板";
            case CLOSE -> "关上样板供应器的界面";
            case FINISHED -> "样板供应器里的样板改完了";
        };
    }
}
