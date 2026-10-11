// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.mekanism;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;

import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 改物流分拣器的一项设置：腾出空手（拿着配置器右键是改侧面，不是开界面）→ 走过去点开它的界面 → 等规则同步过来
 * → 已经是要的样子就不动；不然发 Mekanism 自己的包（按开关、新建或修改规则），等服务器同步回新的样子 → 关上界面。
 *
 * <p>界面里看见的样子记进分拣器的读数，machine_configure 读回按它核对。已经有别的规则时如实报冲突，
 * 不删原来的规则；按了没等到服务器同步回来也如实说，不再按第二下。
 */
final class SorterChange implements Action {

    private enum Stage { HAND, OPEN, READ, SEND, WAIT, CLOSE, FINISHED }

    /** 界面开了以后等规则同步过来最多这么多刻。 */
    private static final int SYNC_WAIT_TICKS = 40;
    /** 按下去以后等服务器同步回新样子最多这么多刻。 */
    private static final int CONFIRM_TIMEOUT_TICKS = 60;

    private final MekanismCompat compat;
    private final PlayerServices services;
    private final BlockPos at;
    private final String key;
    private final String value;
    private final Permissions permissions;
    /** 分拣器的读数：每台上次打开界面时看见的样子，machine_configure 读回用。 */
    private final Map<BlockPos, MekanismReads.SorterState> seen;

    private Stage stage = Stage.HAND;
    private Action current;
    private MenuOpening opening;
    private OpenedMenu menu;
    private int waited;
    private String what;
    private Runnable send;
    private MenuConfirmation confirmation;
    private PendingMenuAction pending;
    private Problem problem;

    SorterChange(MekanismCompat compat, PlayerServices services, BlockPos at, String key, String value,
            Permissions permissions, Map<BlockPos, MekanismReads.SorterState> seen) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.at = at.immutable();
        this.key = Objects.requireNonNull(key, "key");
        this.value = Objects.requireNonNull(value, "value");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.seen = Objects.requireNonNull(seen, "seen");
    }

    @Override public ActionStatus tick(TickContext context) {
        return switch (stage) {
            case HAND -> hand(context);
            case OPEN -> open(context);
            case READ -> read();
            case SEND -> send();
            case WAIT -> await(context.player());
            case CLOSE -> close(context);
            case FINISHED -> problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
        };
    }

    // 腾出空手：手上拿着配置器、扳手右键分拣器是在改侧面或拆它，不是开界面；腾不出来就照原样去点。
    private ActionStatus hand(TickContext context) {
        if (current == null) {
            Problem invalid = invalid();
            if (invalid != null) return finish(invalid);
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

    // 值先核对：开关只认 on / off，只认的物品要是注册过的物品，不然点开界面也改不成。
    private Problem invalid() {
        if (key.equals(SorterSettings.AUTO_EJECT)) {
            return value.equals("on") || value.equals("off") ? null
                    : Problem.of(Problem.Kind.INVALID_PARAMETER, SorterSettings.AUTO_EJECT + " 只认 on 或 off：" + value);
        }
        ResourceLocation id = ResourceLocation.tryParse(value);
        boolean item = id != null && BuiltInRegistries.ITEM.getOptional(id).filter(found -> found != Items.AIR).isPresent();
        return item ? null : Problem.of(Problem.Kind.INVALID_PARAMETER, SorterSettings.ITEM + " 要写一样物品的 ID：" + value);
    }

    // 走过去点开：开箱子同一个动作，走到够得着、看得见的地方右键，认领界面。
    private ActionStatus open(TickContext context) {
        if (opening == null) {
            opening = new ClientMenuOpening(at, permissions, services.menuLayouts(), services.approach(),
                    services.interactions(), services.context());
        }
        ActionStatus status = opening.tick(context);
        if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
        if (!(status instanceof ActionStatus.Done)) return status;
        menu = opening.opened().orElse(null);
        if (menu == null) return finish(Problem.of(Problem.Kind.TARGET_GONE, "分拣器的界面没打开"));
        stage = Stage.READ;
        return ActionStatus.progressed();
    }

    // 等规则同步过来，记下看见的样子；已经是要的样子就关界面，冲突就带着原因关界面，不然备好要发的那一下。
    private ActionStatus read() {
        Optional<MekanismReads.SorterState> state = compat.openSorter(at);
        if (state.isEmpty()) {
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            return closeWith(Problem.of(Problem.Kind.STUCK, "分拣器的界面开了，规则一直没同步过来"));
        }
        seen.put(at, state.get());
        if (key.equals(SorterSettings.AUTO_EJECT)) {
            boolean wanted = value.equals("on");
            if (state.get().autoEject() == wanted) return closeWith(null);
            what = "按分拣器的\"没规则对得上时往外送\"开关";
            send = () -> compat.toggleSorterAutoEject(at);
            confirmation = synced(now -> now.autoEject() == wanted);
        } else {
            SorterSettings.Decision decision = SorterSettings.decide(state.get(), value);
            if (decision == SorterSettings.Decision.READY) return closeWith(null);
            if (decision == SorterSettings.Decision.CONFLICT) {
                return closeWith(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "分拣器上" + SorterSettings.itemValue(state.get())
                        + "，只认 " + value + " 要先去掉它们；原来的规则不擅自删"));
            }
            what = (decision == SorterSettings.Decision.ADD ? "给分拣器新建一条只认 " : "把分拣器那条规则改成只认 ")
                    + value + " 的精确规则";
            send = () -> compat.setSorterItemRule(at, value);
            confirmation = synced(now -> SorterSettings.decide(now, value) == SorterSettings.Decision.READY);
        }
        stage = Stage.SEND;
        return ActionStatus.progressed();
    }

    // 发那一下：界面还开着、上一下结清了、本刻有交互机会才发；发不出去下一刻再试，不算按过。
    private ActionStatus send() {
        Optional<PendingMenuAction> sent = menu.submitModAction(what, send, confirmation, CONFIRM_TIMEOUT_TICKS);
        if (sent.isEmpty()) return ActionStatus.running();
        pending = sent.get();
        stage = Stage.WAIT;
        return ActionStatus.progressed();
    }

    // 等服务器把新样子同步回来：对上了记下新样子；没等到就如实说，不按第二下。
    private ActionStatus await(PlayerContext player) {
        if (!pending.terminal()) pending = player.menuActions().poll(player, pending);
        if (!pending.terminal()) return ActionStatus.running();
        compat.openSorter(at).ifPresent(state -> seen.put(at, state));
        if (pending.status() == PendingMenuAction.Status.CONFIRMED_APPLIED) return closeWith(null);
        return closeWith(Problem.of(Problem.Kind.STUCK, what + "之后没等到服务器同步回新的样子：" + pending.detail()));
    }

    private ActionStatus close(TickContext context) {
        if (current == null) current = menu.closing();
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        current = null;
        return finish(problem);
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

    // 按开着的这台分拣器同步过来的样子核对：对上了算生效，还是老样子就接着等。
    private MenuConfirmation synced(Predicate<MekanismReads.SorterState> done) {
        return (context, ignored) -> compat.openSorter(at).filter(done).isPresent()
                ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING;
    }

    @Override public void pause() {
        if (current != null) current.pause();
        if (opening != null && menu == null) opening.pause();
    }

    // 中途收场：界面还开着就请游戏关一次，不把分拣器的界面留在画面上。
    @Override public void close() {
        if (current != null) current.close();
        if (menu != null && stage != Stage.FINISHED) menu.abandon();
        else if (opening != null && menu == null) opening.close();
    }

    @Override public Interruptibility interruptibility() {
        if (pending != null && !pending.terminal()) return Interruptibility.UNSAFE_TO_STOP;
        return current != null ? current.interruptibility() : Interruptibility.WORKING;
    }

    @Override public String describe() {
        return switch (stage) {
            case HAND -> "腾出空手去开分拣器";
            case OPEN -> opening == null ? "走去点开分拣器" : opening.describe();
            case READ -> "等分拣器的规则同步过来";
            case SEND, WAIT -> what;
            case CLOSE -> "关上分拣器的界面";
            case FINISHED -> "改完分拣器的「" + key + "」";
        };
    }
}
