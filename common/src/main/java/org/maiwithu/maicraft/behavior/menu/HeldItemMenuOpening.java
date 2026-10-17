// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;

import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.MovesToMainhand;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 拿着一件物品右键打开它自己的界面（例如身上的无线终端）：换到主手 → 右键（界面编号变了才算点开）→
 * 认领点开的那一份界面、新建读端 → 等内容同步完。不用走路，物品带在身上就能开。
 *
 * <p>和点开方块的 {@link ClientMenuOpening} 一样：认不出两侧怎么分的界面一格都不点，
 * 内容一直没同步完按卡住失败，失败时已经点开的界面请游戏关上。
 */
public final class HeldItemMenuOpening implements MenuOpening {

    /** 右键最多按住几刻等界面打开；物品界面是右键的那一下就开，十刻足够。 */
    static final int OPEN_HOLD_TICKS = 10;

    private enum Stage { HAND, CLICK, SYNC, OPENED }

    private final String itemId;
    private final MenuLayouts layouts;
    private final MovesToMainhand toMainhand;
    private final Interactions interactions;
    private final Supplier<PlayerContext> contexts;
    private Stage stage = Stage.HAND;
    private Action step;
    private ClientMenuChannel channel;
    private ClientMenuContent content;
    private int waited;
    private OpenedMenu opened;

    /**
     * @param itemId 要拿出来右键的那件物品的 ID
     */
    public HeldItemMenuOpening(String itemId, MenuLayouts layouts, MovesToMainhand toMainhand,
            Interactions interactions, Supplier<PlayerContext> contexts) {
        this.itemId = Objects.requireNonNull(itemId, "itemId");
        this.layouts = Objects.requireNonNull(layouts, "layouts");
        this.toMainhand = Objects.requireNonNull(toMainhand, "toMainhand");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    @Override public Optional<OpenedMenu> opened() {
        return Optional.ofNullable(opened);
    }

    @Override public ActionStatus tick(TickContext tick) {
        if (tick.player() == null || tick.player().localPlayer() == null) return ActionStatus.running();
        return switch (stage) {
            case HAND -> toHand(tick);
            case CLICK -> click(tick);
            case SYNC -> waitSync();
            case OPENED -> ActionStatus.done();
        };
    }

    // 换到主手：已经在主手就不动；换不过来如实失败。
    private ActionStatus toHand(TickContext tick) {
        if (step == null) {
            if (heldInMainhand(tick.player())) {
                stage = Stage.CLICK;
                return ActionStatus.progressed();
            }
            Optional<Action> move = toMainhand.moveToMainhand(itemId);
            if (move.isEmpty()) {
                return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "身上没有 " + itemId + "，或者换不到主手", null));
            }
            step = move.get();
        }
        ActionStatus status = step.tick(tick);
        if (status instanceof ActionStatus.Failed failed) {
            return ActionStatus.failed(Problem.of(failed.problem().kind(),
                    "没能把 " + itemId + " 拿到主手：" + failed.problem().message(), null));
        }
        if (status instanceof ActionStatus.Done) {
            finishStep();
            stage = Stage.CLICK;
            return ActionStatus.progressed();
        }
        return status;
    }

    // 拿着右键：界面编号变了才算点开；没打开就如实带原因失败。
    private ActionStatus click(TickContext tick) {
        if (step == null) {
            int before = tick.player().localPlayer().containerMenu.containerId;
            step = interactions.useHeldItem(InteractionHand.MAIN_HAND, InteractionConfirmation.menuChanged(before),
                    OPEN_HOLD_TICKS);
        }
        ActionStatus status = step.tick(tick);
        if (status instanceof ActionStatus.Failed failed) {
            return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                    "拿着 " + itemId + " 右键没打开界面：" + failed.problem().message(), null));
        }
        if (status instanceof ActionStatus.Done) {
            finishStep();
            channel = ClientMenuChannel.claimCurrent(contexts);
            if (channel == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "界面打开了又马上没了", null));
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
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "界面刚打开就被关上了", null));
        }
        if (++waited <= ClientMenuOpening.SYNC_WAIT_TICKS) {
            return ActionStatus.running();
        }
        channel.requestClose();
        if (layouts.classify(new ClientMenuSlots(contexts)) instanceof MenuLayout.Unsupported unsupported) {
            return ActionStatus.failed(Problem.of(Problem.Kind.UNSUPPORTED,
                    "这种界面认不出两侧怎么分，一格都不点：" + unsupported.reason(), null));
        }
        return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "界面打开了，内容一直没同步完", null));
    }

    private boolean heldInMainhand(PlayerContext player) {
        var held = player.localPlayer().getMainHandItem();
        return !held.isEmpty() && itemId.equals(BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
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

    // 没做完就被收尾：换手与右键一并收尾；已经打开、还没交出去的界面请游戏关上。
    @Override public void close() {
        finishStep();
        if (stage == Stage.SYNC && channel != null && channel.stillOpen()) {
            channel.requestClose();
        }
    }

    @Override public String describe() {
        return switch (stage) {
            case HAND -> "把 " + itemId + " 拿到主手";
            case CLICK -> "拿着 " + itemId + " 右键打开";
            case SYNC -> "等 " + itemId + " 的界面内容同步完";
            case OPENED -> "打开了 " + itemId + " 的界面";
        };
    }
}
