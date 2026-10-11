// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.AimAndInteract;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.menu.ClientMenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 到设施跟前把界面点开：走到能点着它的站位 → 右键点开 → 等格子同步完、认下界面类型。
 * 站的位置看不到台面或够不着时换个站位再点，换遍了才如实失败；做配方和烧炉子共用这一段，
 * 烧炉子关着界面等完回来取货时再走一遍。
 */
final class StationVisit {

    /** 等界面打开并同步完的期限（刻）。 */
    private static final int MENU_WAIT_LIMIT = 40;
    /** 站的位置看不到或够不着设施时，最多再换几个站位：换遍了才弃台。 */
    private static final int MORE_SPOTS = 2;

    private enum Stage { APPROACH, OPEN, WAIT, OPENED }

    private final BringsPlayerClose close;
    private final Interactions interactions;
    private final Supplier<PlayerContext> context;
    private final BlockPos at;
    /** 这次任务的许可：走到设施跟前时能动多少地形按它来。 */
    private final Permissions permissions;
    /** 设施叫什么、来做什么：失败时说给 LLM 听。 */
    private final String stationName;
    private final String purpose;
    /** 界面类型对得上这种设施才算点开了对的东西。 */
    private final Predicate<MenuContent.Reading> matches;

    private Stage stage = Stage.APPROACH;
    private Action approaching;
    private AimAndInteract opening;
    /** 点的时候看不到、够不着的站位：再靠近时不站这些格。 */
    private final Set<BlockPos> poorSpots = new HashSet<>();
    /** 界面打开后才建的读端：绑的就是点开的那份界面。 */
    private MenuContent menus;
    private int menuWaited;

    StationVisit(BringsPlayerClose close, Interactions interactions, Supplier<PlayerContext> context,
            WorldPosition station, Permissions permissions, String stationName, String purpose,
            Predicate<MenuContent.Reading> matches) {
        this.close = Objects.requireNonNull(close, "close");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.context = Objects.requireNonNull(context, "context");
        this.at = new BlockPos(station.x(), station.y(), station.z());
        this.permissions = permissions;
        this.stationName = stationName;
        this.purpose = purpose;
        this.matches = Objects.requireNonNull(matches, "matches");
    }

    /** 推进一刻：界面打开、同步完、类型对上时给完成，之后用 {@link #menus()} 读写这份界面。 */
    ActionStatus tick(TickContext tick) {
        return switch (stage) {
            case APPROACH -> approach(tick);
            case OPEN -> open(tick);
            case WAIT -> waitMenu();
            case OPENED -> ActionStatus.done();
        };
    }

    /** 点开的那份界面的读端；还没点开时为空。 */
    MenuContent menus() {
        return menus;
    }

    // 走到设施跟前：够不着就如实失败，不隔空点开。
    private ActionStatus approach(TickContext tick) {
        if (approaching == null) {
            // 走过去能动多少地形按这次任务的许可来，不另开一套默认档。
            approaching = poorSpots.isEmpty() ? close.toward(ApproachTarget.ofBlock(at), permissions)
                    : close.toward(ApproachTarget.ofBlock(at), permissions, cell -> poorSpots.contains(cell));
        }
        ActionStatus status = approaching.tick(tick);
        if (status instanceof ActionStatus.Running) {
            return status;
        }
        if (status instanceof ActionStatus.Failed failed) {
            return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE,
                    "走不到" + stationName + "跟前，" + purpose + "不成：" + failed.problem().message()));
        }
        stage = Stage.OPEN;
        return ActionStatus.progressed();
    }

    // 点开设施：确认条件是界面打开（容器编号变了），点完等界面递过来。
    private ActionStatus open(TickContext tick) {
        if (opening == null) {
            int menuIdBefore = tick.player().localPlayer().containerMenu.containerId;
            opening = interactions.useBlock(at, InteractionConfirmation.menuChanged(menuIdBefore));
        }
        ActionStatus status = opening.tick(tick);
        if (status instanceof ActionStatus.Running) {
            return status;
        }
        // 站的位置看不到台面、或够不着：挪个站位再点，换遍了才弃台。
        if (status instanceof ActionStatus.Failed failed && failed.problem().kind() == Problem.Kind.UNREACHABLE
                && poorSpots.size() < MORE_SPOTS) {
            poorSpots.add(tick.player().localPlayer().blockPosition());
            opening.close();
            opening = null;
            approaching.close();
            approaching = null;
            stage = Stage.APPROACH;
            return ActionStatus.progressed();
        }
        if (status instanceof ActionStatus.Failed failed) {
            return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                    "点开" + stationName + "没成功，" + purpose + "不成：" + failed.problem().message()));
        }
        stage = Stage.WAIT;
        return ActionStatus.progressed();
    }

    // 等格子同步完，并认下界面类型：认不出的界面不乱点。
    private ActionStatus waitMenu() {
        if (menus == null) {
            // 读端在这里才建：绑的就是刚点开、还没被换掉的这份界面。
            menus = new ClientMenuContent(context);
        }
        Optional<MenuContent.Reading> reading = menus.current();
        if (reading.isEmpty()) {
            if (++menuWaited > MENU_WAIT_LIMIT) {
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                        stationName + "点开了但界面一直没同步，" + purpose + "不成"));
            }
            return ActionStatus.running();
        }
        if (!matches.test(reading.get())) {
            return ActionStatus.failed(Problem.of(Problem.Kind.UNSUPPORTED,
                    "打开的不是认得的" + stationName + "界面，" + purpose + "不成"));
        }
        stage = Stage.OPENED;
        return ActionStatus.done();
    }

    /** 被生存需求打断：正在走的那一趟、正在点的那一下先停住。 */
    void pause() {
        if (approaching != null) approaching.pause();
        if (opening != null) opening.pause();
    }

    /** 不再用：没走完的走到、没确认的点开一并收尾。界面由用它的动作负责关。 */
    void close() {
        if (approaching != null) approaching.close();
        if (opening != null) opening.close();
    }
}
