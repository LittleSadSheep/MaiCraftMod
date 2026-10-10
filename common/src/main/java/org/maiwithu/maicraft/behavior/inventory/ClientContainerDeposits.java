// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.ReportsUnconfirmed;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 容器存取执行接缝的读端：走到记得的容器跟前、点开、把要腾的那一堆快速移动进去、
 * 确认存进去了再关上界面。
 *
 * <p>存没存成按两侧总数的前后对照核对：背包少了、容器多了才算存进；点了容器那侧纹丝不动
 * 是放不下，如实做不了换别的办法；点出去了却没能等到结算（界面被关、超时），照交回接缝
 * 原样交回，不冒充存进也不冒充没存。关界面之前把容器此刻有什么记进世界记忆，
 * 下次挑容器才有"已经放着同种东西"的线索。一次只存一笔：上一笔没收尾时换了目标就先如实说做不了。
 */
public final class ClientContainerDeposits implements ContainerDeposits, ReportsUnconfirmed, AbandonsStep {

    /** 一次存放的总期限（刻）：走过去、点开、搬运加关界面，到点如实收场，不在一只箱子上耗死。 */
    private static final long TOTAL_BUDGET_TICKS = 20L * 30;
    /** 一下点击结清后等两侧内容同步的宽限（刻）；过了还纹丝不动就是放不下。 */
    private static final int SETTLE_TICKS = 5;
    /** 两次调用隔了这么久就算"这一步没人管了"：撒手旧的，下次重新走、重新开。 */
    private static final long STALE_AFTER_TICKS = 200;

    /** 存放的先后顺序。 */
    private enum Stage { IDLE, OPEN, TRANSFER, CLOSE }

    private final BringsPlayerClose close;
    private final Interactions interactions;
    private final MenuLayouts layouts;
    private final WorldMemory memory;
    private final Supplier<PlayerContext> contexts;

    private Stage stage = Stage.IDLE;
    private KnownContainer container;
    private BackpackStack carrying;
    private BlockPos at;
    private MenuOpening opening;
    private OpenedMenu menu;
    private Action closing;
    /** 动手前两侧各有几件要存的东西：存进多少按差算。 */
    private int playerBefore;
    private int containerBefore;
    /** 确认存进了几件；还没结算为 -1。 */
    private int stored = -1;
    /** 快速移动那一下点出去没有。 */
    private boolean clickSubmitted;
    private int quietTicks;
    private long attemptStartTick;
    private long lastDrivenTick = Long.MIN_VALUE;
    /** 点出去了却没能确认结果的交互，一句一条。 */
    private final List<String> unconfirmed = new ArrayList<>();

    public ClientContainerDeposits(BringsPlayerClose close, Interactions interactions,
            MenuLayouts layouts, WorldMemory memory, Supplier<PlayerContext> contexts) {
        this.close = Objects.requireNonNull(close, "close");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.layouts = Objects.requireNonNull(layouts, "layouts");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    @Override
    public SpaceStepResult deposit(KnownContainer container, BackpackStack stack, Permissions permissions,
            TickContext tick) {
        if (stage != Stage.IDLE && !this.container.equals(container)) {
            // 上一笔还没收尾就换了目标：一次只存一笔，等它结清（或被撒手）再说。
            return SpaceStepResult.cannotDo("往 " + this.container.name() + " 存东西还没收尾，先不换目标");
        }
        // 隔了太久才被再次调用：中间现场可能变了（被打断、箱子被挖走），撒手旧的重新来。
        if (lastDrivenTick != Long.MIN_VALUE && tick.gameTick() - lastDrivenTick > STALE_AFTER_TICKS) {
            abandonStep();
        }
        lastDrivenTick = tick.gameTick();
        if (stage == Stage.IDLE) {
            startAttempt(container, stack, permissions, tick.gameTick());
        }
        if (tick.gameTick() - attemptStartTick > TOTAL_BUDGET_TICKS) {
            String name = container.name();
            noteUnsettledClick();
            if (menu != null) menu.abandon();
            resetAttempt();
            return SpaceStepResult.cannotDo("往 " + name + " 存东西超时，现场收掉了");
        }
        return switch (stage) {
            case IDLE -> SpaceStepResult.WORKING;
            case OPEN -> opening(tick);
            case TRANSFER -> transferring();
            case CLOSE -> closing(tick);
        };
    }

    // 定下这一笔：走到哪只箱子、存哪一堆。
    private void startAttempt(KnownContainer container, BackpackStack stack, Permissions permissions, long gameTick) {
        this.container = container;
        this.carrying = stack;
        this.at = container.cell();
        // 走过去能动多少地形按这次任务的许可来，不另开一套默认档。
        this.opening = new ClientMenuOpening(at, permissions, layouts, close, interactions, contexts);
        this.attemptStartTick = gameTick;
        this.stage = Stage.OPEN;
    }

    // 走到跟前点开：走不到、点不开、界面认不出，都把原因原样交回。
    private SpaceStepResult opening(TickContext tick) {
        ActionStatus status = opening.tick(tick);
        if (!(status instanceof ActionStatus.Done)) {
            if (status instanceof ActionStatus.Failed failed) {
                String name = container.name();
                resetAttempt();
                return SpaceStepResult.cannotDo("打不开 " + name + "：" + failed.problem().message());
            }
            return SpaceStepResult.WORKING;
        }
        menu = opening.opened().orElseThrow();
        stage = Stage.TRANSFER;
        return SpaceStepResult.WORKING;
    }

    // 把那一堆快速移动进容器：点出去后按两侧总数的前后对照结算。
    private SpaceStepResult transferring() {
        Optional<MenuContent.Reading> reading = menu.reading();
        if (reading.isEmpty()) {
            // 界面被合上了（打断或服务端关的）：点出去过就没等到结算，照实交回。
            return menuLost();
        }
        if (menu.busy()) return SpaceStepResult.WORKING;
        MenuContent.Reading open = reading.get();
        int playerNow = totalOf(open.playerSnapshots(), carrying.itemId());
        int containerNow = totalOf(open.containerSnapshots(), carrying.itemId());
        if (!clickSubmitted) {
            playerBefore = playerNow;
            containerBefore = containerNow;
            int sourceIndex = firstOf(open.playerSnapshots(), carrying.itemId());
            if (sourceIndex < 0) {
                // 背包里没有了：要么刚被存进去（容器多了），要么被别人拿走了；按差如实说。
                String itemId = carrying.itemId();
                String name = container.name();
                SpaceStepResult ending = containerNow > 0
                        ? SpaceStepResult.done(new Change(Change.Kind.ITEM_STORED, itemId,
                                Math.max(1, containerNow), "存进了 " + name))
                        : SpaceStepResult.cannotDo("背包里已经没有 " + itemId + " 可存");
                resetAttempt();
                return ending;
            }
            // 快速移动整堆：本刻发不出去（界面刚刷新、没有交互机会）就下一刻再点，不算点过。
            if (!menu.quickMove(open.playerSlotIds().get(sourceIndex))) return SpaceStepResult.WORKING;
            clickSubmitted = true;
            quietTicks = 0;
            return SpaceStepResult.WORKING;
        }
        boolean changed = containerNow > containerBefore || playerNow < playerBefore;
        if ((!changed || !menu.cursorEmpty()) && ++quietTicks <= SETTLE_TICKS) {
            return SpaceStepResult.WORKING;
        }
        if (changed && menu.cursorEmpty()) {
            stored = containerNow - containerBefore;
            rememberContents(open);
            stage = Stage.CLOSE;
            return SpaceStepResult.WORKING;
        }
        String itemText = carrying.itemId();
        String name = container.name();
        if (!changed) {
            menu.abandon();
            resetAttempt();
            return SpaceStepResult.cannotDo("往 " + name + " 存 " + itemText + "：容器放不下，一点没动");
        }
        unconfirmed.add("往 " + name + " 存 " + itemText + "：点完了光标上还拿着东西，"
                + "这一笔存进多少没能确认");
        menu.abandon();
        resetAttempt();
        return SpaceStepResult.cannotDo("往 " + name + " 存东西没能确认完，不冒充存进");
    }

    // 界面没了：点出去过就交回一句没能确认，一下都没点出去就是被别人关了，按做不了收场。
    private SpaceStepResult menuLost() {
        String why = "往 " + container.name() + " 存东西：容器界面被关上了";
        boolean submitted = clickSubmitted;
        noteUnsettledClick();
        resetAttempt();
        return SpaceStepResult.cannotDo(submitted ? why + "，点出去的那一下没等到结算结果" : why);
    }

    // 关界面：打开者负责关闭；存进去已经按两侧对照确认过，界面关得利不利索不挡腾格子。
    private SpaceStepResult closing(TickContext tick) {
        if (closing == null) closing = menu.closing();
        ActionStatus status = closing.tick(tick);
        if (status instanceof ActionStatus.Running) {
            return SpaceStepResult.WORKING;
        }
        Change storedNow = storedChange(stored);
        resetAttempt();
        return SpaceStepResult.done(storedNow);
    }

    private Change storedChange(int amount) {
        return new Change(Change.Kind.ITEM_STORED, carrying.itemId(), Math.max(1, amount),
                "存进了 " + container.name());
    }

    // 点出去了却没等到结算就停下的那一下：一句交回，不悄悄丢。
    private void noteUnsettledClick() {
        if (!clickSubmitted || stored >= 0) return;
        unconfirmed.add("往 " + container.name() + " 存 " + carrying.itemId() + "：快速移动点出去了，"
                + "停下时没能等到结算结果");
    }

    @Override
    public List<String> unconfirmedFacts() {
        return List.copyOf(unconfirmed);
    }

    @Override
    public void abandonStep() {
        if (menu != null && stage != Stage.IDLE) {
            noteUnsettledClick();
            menu.abandon();
        }
        resetAttempt();
    }

    // 回到起点：下一次调用重新走、重新开。
    private void resetAttempt() {
        stage = Stage.IDLE;
        container = null;
        carrying = null;
        at = null;
        opening = null;
        menu = null;
        closing = null;
        playerBefore = 0;
        containerBefore = 0;
        stored = -1;
        clickSubmitted = false;
        quietTicks = 0;
        lastDrivenTick = Long.MIN_VALUE;
    }

    // 关之前把容器此刻有什么记进世界记忆：位置带维度，种类按现场的方块读。
    private void rememberContents(MenuContent.Reading reading) {
        PlayerContext current = contexts.get();
        ClientLevel level = current == null ? null : current.level();
        if (level == null) return;
        Map<String, Integer> contents = new LinkedHashMap<>();
        for (SlotSnapshot snapshot : reading.containerSnapshots()) {
            if (snapshot.isEmpty()) continue;
            String itemId = BuiltInRegistries.ITEM.getKey(snapshot.stack().getItem()).toString();
            contents.merge(itemId, snapshot.count(), Integer::sum);
        }
        WorldPosition position = new WorldPosition(at.getX(), at.getY(), at.getZ(),
                level.dimension().location().toString());
        String blockType = BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock()).toString();
        memory.rememberContainerOpened(position, blockType, List.copyOf(contents.keySet()), Instant.now());
    }

    // 两侧里装着这种东西的总件数。
    private static int totalOf(List<SlotSnapshot> snapshots, String itemId) {
        int total = 0;
        for (SlotSnapshot snapshot : snapshots) {
            if (snapshot.isEmpty()) continue;
            if (BuiltInRegistries.ITEM.getKey(snapshot.stack().getItem()).toString().equals(itemId)) {
                total += snapshot.count();
            }
        }
        return total;
    }

    // 第一格装着这种东西的槽位下标；没有给 -1。
    private static int firstOf(List<SlotSnapshot> snapshots, String itemId) {
        for (int i = 0; i < snapshots.size(); i++) {
            SlotSnapshot snapshot = snapshots.get(i);
            if (snapshot.isEmpty()) continue;
            if (BuiltInRegistries.ITEM.getKey(snapshot.stack().getItem()).toString().equals(itemId)) return i;
        }
        return -1;
    }
}
