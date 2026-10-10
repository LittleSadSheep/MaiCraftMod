// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 打开一只容器的生产实现：走到够得着的地方 → 右键点开（界面编号变了才算点开）→
 * 认领点开的那一份界面，新建读端绑上它 → 等内容同步完。
 *
 * <p>读端每次点开都新建：读端只在第一次读时绑一次界面，复用上一次的会一直读旧的那份。
 * 点不开（锁着、盖子被压住、坐着猫、被服务器拒绝）如实按游戏拒绝失败；界面认不出两侧怎么分
 * 就一格都不点，按不支持失败；内容一直没同步完按卡住失败。失败时已经点开的界面请游戏关上。
 *
 * <p>界面挂在方块的一个部件上时（例如线缆上的 ME 终端面板），靠近与右键都对准部件框，不点线缆芯。
 */
public final class ClientMenuOpening implements MenuOpening {

    /** 等内容同步完的期限（刻）；等不到不把没同步的界面当成空的。 */
    static final int SYNC_WAIT_TICKS = 40;
    /** 站的位置看不到或够不着容器时，最多再换几个站位：换遍了才算点不开。 */
    static final int MORE_SPOTS = 2;

    private enum Stage { APPROACH, CLICK, SYNC, OPENED }

    private final BlockPos at;
    /** 只点方块上的这个部件；为 null 时整格方块。 */
    private final AABB part;
    private final MenuLayouts layouts;
    private final Permissions permissions;
    private final BringsPlayerClose close;
    private final Interactions interactions;
    private final Supplier<PlayerContext> contexts;
    private Stage stage = Stage.APPROACH;
    private Action step;
    private ClientMenuChannel channel;
    private ClientMenuContent content;
    private int waited;
    private OpenedMenu opened;
    /** 点的时候看不到、够不着的站位：再靠近时不站这些格。 */
    private final Set<BlockPos> poorSpots = new HashSet<>();

    /**
     * 打开一只容器：整格方块。
     *
     * @param at          容器方块的位置
     * @param permissions 这次任务的许可：走过去能动多少地形按它来
     * @param layouts     认得出哪些界面：原版加上联动模组证明过的
     */
    public ClientMenuOpening(BlockPos at, Permissions permissions, MenuLayouts layouts, BringsPlayerClose close,
            Interactions interactions, Supplier<PlayerContext> contexts) {
        this(at, null, permissions, layouts, close, interactions, contexts);
    }

    /**
     * 打开方块上一个部件的界面（例如挂在线缆上的 ME 终端）：靠近按部件框判够不够得着、看不看得见，右键也只点框里。
     *
     * @param part 部件框，世界坐标；读写端按模组的模型给出。为 null 时就是整格方块
     */
    public ClientMenuOpening(BlockPos at, AABB part, Permissions permissions, MenuLayouts layouts,
            BringsPlayerClose close, Interactions interactions, Supplier<PlayerContext> contexts) {
        this.at = at.immutable();
        this.part = part;
        this.layouts = Objects.requireNonNull(layouts, "layouts");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.close = Objects.requireNonNull(close, "close");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    @Override public Optional<OpenedMenu> opened() {
        return Optional.ofNullable(opened);
    }

    @Override public ActionStatus tick(TickContext tick) {
        return switch (stage) {
            case APPROACH -> approach(tick);
            case CLICK -> click(tick);
            case SYNC -> waitSync();
            case OPENED -> ActionStatus.done();
        };
    }

    // 走到够得着、看得见容器的地方；走不到如实失败，不隔空点开。
    private ActionStatus approach(TickContext tick) {
        if (step == null) {
            ApproachTarget target = part == null ? ApproachTarget.ofBlock(at)
                    : ApproachTarget.ofBlockPart(at, part);
            step = poorSpots.isEmpty() ? close.toward(target, permissions)
                    : close.toward(target, permissions, cell -> poorSpots.contains(cell));
        }
        ActionStatus status = step.tick(tick);
        if (status instanceof ActionStatus.Failed failed) {
            return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE,
                    "走不到 " + at.toShortString() + " 的容器跟前：" + failed.problem().message(), null));
        }
        if (status instanceof ActionStatus.Done) {
            finishStep();
            stage = Stage.CLICK;
        }
        return status instanceof ActionStatus.Done ? ActionStatus.progressed() : status;
    }

    // 右键点开：界面编号变了才算点开；游戏没让开就如实带原因失败。
    private ActionStatus click(TickContext tick) {
        if (step == null) {
            PlayerContext player = tick.player();
            int before = player.localPlayer().containerMenu.containerId;
            InteractionConfirmation opened = InteractionConfirmation.menuChanged(before);
            step = part == null ? interactions.useBlock(at, opened) : interactions.useBlockPart(at, part, opened);
        }
        ActionStatus status = step.tick(tick);
        // 站的位置看不到容器的任何一面、或够不着：像玩家一样挪个地方再点，换遍了才算点不开。
        if (status instanceof ActionStatus.Failed failed && failed.problem().kind() == Problem.Kind.UNREACHABLE
                && poorSpots.size() < MORE_SPOTS && tick.player().localPlayer() != null) {
            poorSpots.add(tick.player().localPlayer().blockPosition());
            finishStep();
            stage = Stage.APPROACH;
            return ActionStatus.progressed();
        }
        if (status instanceof ActionStatus.Failed failed) {
            return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                    "点开 " + at.toShortString() + " 的容器没打开界面（锁着、盖子被压住、上面坐着猫或被服务器拒绝）："
                            + failed.problem().message(), null));
        }
        if (status instanceof ActionStatus.Done) {
            finishStep();
            channel = ClientMenuChannel.claimCurrent(contexts);
            if (channel == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "界面点开了又马上没了", null));
            }
            content = new ClientMenuContent(contexts, layouts);
            stage = Stage.SYNC;
            return ActionStatus.progressed();
        }
        return status;
    }

    // 等内容同步完：认不出两侧的界面一格都不点；被关掉、等到期限都如实失败并请游戏关上。
    private ActionStatus waitSync() {
        if (content.current().isPresent()) {
            opened = new ClientOpenedMenu(channel, content, contexts);
            stage = Stage.OPENED;
            return ActionStatus.done();
        }
        if (!channel.stillOpen()) {
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "界面刚点开就被关上了", null));
        }
        if (++waited <= SYNC_WAIT_TICKS) {
            return ActionStatus.running();
        }
        channel.requestClose();
        if (layouts.classify(new ClientMenuSlots(contexts)) instanceof MenuLayout.Unsupported unsupported) {
            return ActionStatus.failed(Problem.of(Problem.Kind.UNSUPPORTED,
                    "这种界面认不出两侧怎么分，一格都不点：" + unsupported.reason(), null));
        }
        return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "界面点开了，内容一直没同步完", null));
    }

    private void finishStep() {
        if (step != null) {
            step.close();
            step = null;
        }
    }

    @Override public void pause() {
        if (step != null) step.pause();
    }

    // 没做完就被收尾：走到与点开一并收尾；已经点开、还没交出去的界面请游戏关上。
    @Override public void close() {
        finishStep();
        if (stage == Stage.SYNC && channel != null && channel.stillOpen()) {
            channel.requestClose();
        }
    }

    @Override public String describe() {
        return switch (stage) {
            case APPROACH -> "走到 " + at.toShortString() + " 的容器跟前";
            case CLICK -> "点开 " + at.toShortString() + " 的容器";
            case SYNC -> "等容器界面的内容同步完";
            case OPENED -> "容器界面开着";
        };
    }
}
