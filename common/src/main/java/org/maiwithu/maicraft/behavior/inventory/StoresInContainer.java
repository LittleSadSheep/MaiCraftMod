// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 往一只容器里存东西：走过去点开、等内容同步完、按顺序一堆堆搬进去、把里面有什么记进世界记忆、关上。
 * 存东西能力一只只换着存、腾地方存进记得的箱子，都用这一份。
 *
 * <p>只记确认过的量：每一笔按背包少了几件、容器多了几件对上才记进账，对不上的记进没能确认，不凑数。
 * 搬到一半界面被关掉（被打断、被别人关）就重新点开接着存，最多两次；关之前没结算的那一笔，
 * 重新点开后按同一只容器两侧的总数补结算，补不上就照实记进没能确认。
 * 点不开（锁着、坐着猫、被拒绝、界面认不出）以问题失败，经 {@link #openFailure()} 交给调用方，换别的容器。
 */
public final class StoresInContainer implements Action {

    /** 一只容器搬到一半界面被关掉时，重新点开接着存的次数。 */
    private static final int REOPEN_LIMIT = 2;

    /** 打开一只容器：走过去、点开、等内容同步完；之后的搬运与关闭经它交出的那一份界面。 */
    @FunctionalInterface
    public interface OpensContainer {
        MenuOpening open(BlockPos at, Permissions permissions);
    }

    /**
     * 存放要用的现场部件：存东西能力与腾地方共用一份。
     *
     * @param opens  打开一只容器
     * @param memory 世界记忆：存完把容器里有什么记下来，取东西时按它找箱子；没接上为 null
     */
    public record Parts(OpensContainer opens, WorldMemory memory) {

        public Parts {
            Objects.requireNonNull(opens, "opens");
        }
    }

    /** 存放的账：还要存几件、确认存进了几件、哪一下没能确认、试过什么。 */
    public interface Ledger {
        /** 这种东西还要存几件。 */
        int left(String itemId);

        /** 确认存进了这么多。 */
        void stored(String itemId, int amount);

        /** 点了但两侧对不上的一下，原样记下。 */
        void unconfirmed(String fact);

        /** 试过的一次办法及结果：开不了、放不下、界面被关了重开。 */
        void attempt(String tried, String whatHappened);
    }

    /** 走过去点开 → 搬 → 关上 → 结束；搬到一半界面被关掉就回到点开。 */
    private enum Stage { OPEN, TRANSFER, CLOSE, FINISHED }

    private final KnownContainer container;
    private final List<String> order;
    private final Ledger ledger;
    private final Permissions permissions;
    private final Parts parts;

    private Stage stage = Stage.OPEN;
    private MenuOpening opening;
    private ContainerTransfer transfer;
    private Action closing;
    /** 界面被关掉时没结算的那一笔：重新点开后补结算。 */
    private ContainerTransfer.Unsettled carryOver;
    private int reopened;
    private Set<String> full = Set.of();
    private Problem openFailure;

    /**
     * @param container   存进哪一只
     * @param order       要存的东西，按存什么的顺序
     * @param ledger      存放的账
     * @param permissions 这次任务的许可：走过去能动多少地形按它来
     * @param parts       存放要用的现场部件
     */
    public StoresInContainer(KnownContainer container, List<String> order, Ledger ledger, Permissions permissions,
            Parts parts) {
        this.container = Objects.requireNonNull(container, "container");
        this.order = List.copyOf(order);
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.parts = Objects.requireNonNull(parts, "parts");
    }

    /** 这只容器里放不下的东西；搬完才有。 */
    public Set<String> full() {
        return full;
    }

    /** 点不开时的原因；点开过或还没试时为空。 */
    public Optional<Problem> openFailure() {
        return Optional.ofNullable(openFailure);
    }

    @Override
    public ActionStatus tick(TickContext context) {
        return switch (stage) {
            case OPEN -> open(context);
            case TRANSFER -> transfer(context);
            case CLOSE -> closeMenu(context);
            case FINISHED -> ActionStatus.done();
        };
    }

    // 走过去点开并等同步完就开始搬；点不开记一笔，失败交回去让调用方换别的。
    private ActionStatus open(TickContext context) {
        if (opening == null) {
            opening = parts.opens().open(container.cell(), permissions);
        }
        return switch (opening.tick(context)) {
            case ActionStatus.Running running -> running;
            case ActionStatus.Done done -> {
                transfer = new ContainerTransfer(opening.opened().orElseThrow(), order, ledger, carryOver);
                carryOver = null;
                stage = Stage.TRANSFER;
                yield ActionStatus.progressed();
            }
            case ActionStatus.Failed failed -> {
                dropCarryOver();
                openFailure = failed.problem();
                ledger.attempt("打开" + container.name(), failed.problem().message());
                yield finish(failed);
            }
        };
    }

    // 搬完就记下容器里现在有什么再关上；界面中途被关掉就重新点开接着存，次数用完交回失败。
    private ActionStatus transfer(TickContext context) {
        return switch (transfer.tick(context)) {
            case ActionStatus.Running running -> running;
            case ActionStatus.Done done -> {
                transfer.contentsAfter().ifPresent(this::remember);
                full = transfer.full();
                if (!full.isEmpty()) {
                    ledger.attempt("往" + container.name() + "里存", "放不下了：" + String.join("、", full));
                }
                OpenedMenu menu = opening.opened().orElseThrow();
                closing = menu.closing();
                stage = Stage.CLOSE;
                yield ActionStatus.progressed();
            }
            case ActionStatus.Failed failed -> {
                carryOver = transfer.unsettled().orElse(null);
                transfer.close();
                transfer = null;
                if (reopened < REOPEN_LIMIT) {
                    reopened++;
                    ledger.attempt("往" + container.name() + "里存",
                            failed.problem().message() + "；重新点开接着存");
                    opening = null;
                    stage = Stage.OPEN;
                    yield ActionStatus.progressed();
                }
                ledger.attempt("往" + container.name() + "里存", failed.problem().message() + "；换下一只");
                dropCarryOver();
                yield finish(failed);
            }
        };
    }

    // 关上界面：存进去的已经确认过，界面关得利不利索只记一笔，不改写存进了多少。
    private ActionStatus closeMenu(TickContext context) {
        return switch (closing.tick(context)) {
            case ActionStatus.Running running -> running;
            case ActionStatus.Done done -> finish(done);
            case ActionStatus.Failed failed -> {
                ledger.attempt("关上" + container.name() + "的界面", failed.problem().message());
                yield finish(ActionStatus.done());
            }
        };
    }

    private ActionStatus finish(ActionStatus ending) {
        stage = Stage.FINISHED;
        return ending;
    }

    // 上一趟没结算的那一笔补不上了：照实记进没能确认。
    private void dropCarryOver() {
        if (carryOver == null) return;
        ledger.unconfirmed("往" + container.name() + "里搬 " + carryOver.itemId()
                + " 的那一下还没结算界面就被关掉了");
        carryOver = null;
    }

    // 世界记忆按物品记"里面有什么"：取东西时按它找箱子。
    private void remember(Map<String, Integer> contents) {
        if (parts.memory() == null) return;
        parts.memory().rememberContainerOpened(container.at(), container.blockTypeId(),
                List.copyOf(contents.keySet()), Instant.now());
    }

    // 被生存需求打断：正在点开的停下；正在搬的请游戏关上界面，恢复后重新点开接着存。
    @Override
    public void pause() {
        switch (stage) {
            case OPEN -> {
                if (opening != null) opening.pause();
            }
            case TRANSFER -> transfer.pause();
            case CLOSE -> closing.pause();
            case FINISHED -> { }
        }
    }

    // 不再存了（被取消、角色没了）：没结算的那一笔记进没能确认，界面请游戏关上。
    @Override
    public void close() {
        switch (stage) {
            case OPEN -> {
                if (opening != null) opening.close();
                if (opening == null || opening.opened().isEmpty()) dropCarryOver();
            }
            case TRANSFER -> transfer.close();
            case CLOSE -> closing.close();
            case FINISHED -> { }
        }
        stage = Stage.FINISHED;
    }

    @Override
    public Interruptibility interruptibility() {
        return switch (stage) {
            case OPEN -> opening == null ? Interruptibility.WORKING : opening.interruptibility();
            case TRANSFER -> transfer.interruptibility();
            case CLOSE -> closing.interruptibility();
            case FINISHED -> Interruptibility.WORKING;
        };
    }

    @Override
    public String describe() {
        return switch (stage) {
            case OPEN -> "走过去点开" + container.name();
            case TRANSFER -> transfer.describe();
            case CLOSE -> "关上" + container.name();
            case FINISHED -> "存完了" + container.name();
        };
    }
}
