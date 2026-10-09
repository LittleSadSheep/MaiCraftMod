// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.approach.InteractionTarget;
import org.maiwithu.maicraft.behavior.interaction.AimAndInteract;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.KnownContainer;
import org.maiwithu.maicraft.behavior.menu.ClientQuickMoves;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuSession;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 开箱取货的生产实现：走到记得的箱子跟前、点开、认下界面，把装着想要东西的槽位
 * 一格一格整堆搬进背包，拿够或搬完就关上。
 *
 * <p>拿走多少以重新清点为准：引擎在动作结束后按身上的数量结算，这里只保证
 * 每一笔整堆搬运都点出去了、界面关上了。记得的箱子到现场可能已经不在：
 * 点开没反应、界面一直没同步，都如实失败，引擎换别的路。
 */
public final class MenuContainerTakes implements ContainerTakes {

    /** 等界面打开并同步完的期限（刻）。 */
    private static final int MENU_WAIT_LIMIT = 40;
    /** 一次开箱取货的总期限（刻）；到点如实收场，不在一只箱子上耗死。 */
    private static final long GIVE_UP_TICKS = 20L * 30;

    private final BringsPlayerClose close;
    private final Interactions interactions;
    private final MenuContent menus;
    private final ClientQuickMoves quickMoves;
    private final ReadsItemTags tags;
    private final WorldMemory memory;

    public MenuContainerTakes(BringsPlayerClose close, Interactions interactions, MenuContent menus,
            ClientQuickMoves quickMoves, ReadsItemTags tags, WorldMemory memory) {
        this.close = Objects.requireNonNull(close, "close");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.menus = Objects.requireNonNull(menus, "menus");
        this.quickMoves = Objects.requireNonNull(quickMoves, "quickMoves");
        this.tags = Objects.requireNonNull(tags, "tags");
        this.memory = Objects.requireNonNull(memory, "memory");
    }

    @Override
    public Optional<Action> take(KnownContainer container, ItemRequest request) {
        return Optional.of(new TakeAction(container, request));
    }

    /** 开箱取货的动作：靠近 → 点开 → 等同步 → 逐格搬 → 关上，跨刻推进。 */
    private final class TakeAction implements Action {

        private final KnownContainer container;
        private final ItemRequest request;
        private Stage stage = Stage.APPROACH;
        private Action approaching;
        private AimAndInteract opening;
        private int menuWaited;
        private int waited;
        /** 刚打开时容器那一侧装着想要的东西的总件数：搬走多少按它与当刻的差算。 */
        private int startTotal = -1;
        private Problem failure;

        TakeAction(KnownContainer container, ItemRequest request) {
            this.container = container;
            this.request = request;
        }

        @Override
        public ActionStatus tick(TickContext tick) {
            if (failure != null) return ActionStatus.failed(failure);
            if (++waited > GIVE_UP_TICKS) {
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                        "开箱取货超时：" + container.name(), null));
            }
            return switch (stage) {
                case APPROACH -> approach(tick);
                case OPEN -> open(tick);
                case WAIT -> waitMenu();
                case TRANSFER -> transfer(tick);
                case CLOSE -> close(tick);
            };
        }

        // 走到箱子跟前：够不着就如实失败，不隔空点开。
        private ActionStatus approach(TickContext tick) {
            if (approaching == null) {
                BlockPos at = new BlockPos((int) container.x(), (int) container.y(), (int) container.z());
                approaching = close.toward(InteractionTarget.ofBlock(at), Permissions.DEFAULT);
            }
            ActionStatus status = approaching.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            if (status instanceof ActionStatus.Failed failed) {
                return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE,
                        "走不到" + container.name() + "跟前：" + failed.problem().message(), null));
            }
            stage = Stage.OPEN;
            return ActionStatus.progressed();
        }

        // 点开箱子：确认条件是界面打开（容器编号变了），点完等界面递过来。
        private ActionStatus open(TickContext tick) {
            if (opening == null) {
                int menuIdBefore = tick.player().localPlayer().containerMenu.containerId;
                BlockPos at = new BlockPos((int) container.x(), (int) container.y(), (int) container.z());
                opening = interactions.useBlock(at, InteractionConfirmation.menuChanged(menuIdBefore));
            }
            ActionStatus status = opening.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            if (status instanceof ActionStatus.Failed failed) {
                return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        "点开" + container.name() + "没成功：" + failed.problem().message(), null));
            }
            stage = Stage.WAIT;
            return ActionStatus.progressed();
        }

        // 等格子同步完；等不到不把没同步的界面当成空箱子。
        private ActionStatus waitMenu() {
            Optional<MenuContent.Reading> reading = menus.current();
            if (reading.isEmpty()) {
                if (++menuWaited > MENU_WAIT_LIMIT) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                            container.name() + "点开了但界面一直没同步", null));
                }
                return ActionStatus.running();
            }
            startTotal = matchedTotal(reading.get());
            stage = Stage.TRANSFER;
            return ActionStatus.progressed();
        }

        // 逐格整堆搬：这一笔还在确认就本刻不动手（搬运读端自己等），搬够或没有可搬的就去关界面。
        private ActionStatus transfer(TickContext tick) {
            Optional<MenuContent.Reading> reading = menus.current();
            if (reading.isEmpty()) {
                // 界面被合上了（打断或服务端关的）：拿走多少由引擎清点，这里照常收场。
                return ActionStatus.done();
            }
            MenuContent.Reading open = reading.get();
            int taken = Math.max(0, startTotal - matchedTotal(open));
            if (taken >= request.count()) {
                stage = Stage.CLOSE;
                return ActionStatus.progressed();
            }
            Integer next = nextMatchedSlot(open);
            if (next == null) {
                stage = Stage.CLOSE;
                return ActionStatus.progressed();
            }
            quickMoves.quickMove(tick.player(), next);
            rememberContents(open, tick.gameTick());
            return ActionStatus.running();
        }

        // 关界面：打开者负责关闭；关不上不冒充没开过，照常收场让引擎清点。
        private ActionStatus close(TickContext tick) {
            Optional<MenuContent.Reading> reading = menus.current();
            if (reading.isEmpty()) return ActionStatus.done();
            MenuSession.Claim claim = MenuSession.claim(reading.get().channel());
            if (claim instanceof MenuSession.Claim.Refused) return ActionStatus.done();
            var closing = ((MenuSession.Claim.Owned) claim).session()
                    .closeNow(reading.get().channel(), tick.gameTick());
            if (closing instanceof MenuSession.Closing.Closed) return ActionStatus.done();
            if (closing instanceof MenuSession.Closing.Failed) return ActionStatus.done();
            return ActionStatus.running();
        }

        // 容器那一侧装着想要的东西的总件数；界面内容按物品标签对上请求。
        private int matchedTotal(MenuContent.Reading reading) {
            int total = 0;
            for (SlotSnapshot snapshot : reading.containerSnapshots()) {
                if (matches(snapshot)) total += snapshot.count();
            }
            return total;
        }

        // 下一格装着想要东西的容器侧槽位；没有给 null。
        private Integer nextMatchedSlot(MenuContent.Reading reading) {
            List<Integer> slotIds = reading.containerSlotIds();
            List<SlotSnapshot> snapshots = reading.containerSnapshots();
            for (int i = 0; i < slotIds.size(); i++) {
                if (matches(snapshots.get(i))) return slotIds.get(i);
            }
            return null;
        }

        private boolean matches(SlotSnapshot snapshot) {
            if (snapshot.isEmpty()) return false;
            String itemId = BuiltInRegistries.ITEM.getKey(snapshot.stack().getItem()).toString();
            return request.wanted().matches(itemId, tags.tagsOf(itemId));
        }

        // 开过就往世界记忆里刷新一次：内容按当刻界面记，位置按箱子记。
        private void rememberContents(MenuContent.Reading reading, long gameTick) {
            Map<String, Integer> contents = new LinkedHashMap<>();
            for (SlotSnapshot snapshot : reading.containerSnapshots()) {
                if (snapshot.isEmpty()) continue;
                String itemId = BuiltInRegistries.ITEM.getKey(snapshot.stack().getItem()).toString();
                contents.merge(itemId, snapshot.count(), Integer::sum);
            }
            WorldPosition at = new WorldPosition((int) container.x(), (int) container.y(), (int) container.z(), null);
            memory.rememberContainerOpened(at, containerTypeId(), List.copyOf(contents.keySet()), Instant.now());
        }

        // 箱子叫法开头是方块类型，取来记进世界记忆；写岔了按记过的样子记，不冒充认得。
        private String containerTypeId() {
            int space = container.name().indexOf(' ');
            return space > 0 ? container.name().substring(0, space) : "minecraft:chest";
        }

        // 被生存需求打断：正在走的那一趟、正在点的那一下先停住，恢复后接着推进。
        @Override
        public void pause() {
            if (approaching != null) approaching.pause();
            if (opening != null) opening.pause();
        }

        // 收尾：没走完的走到、没确认的点开一并收尾，不让它们悬着占着身体。
        @Override
        public void close() {
            if (approaching != null) approaching.close();
            if (opening != null) opening.close();
        }

        @Override
        public String describe() {
            return "从" + container.name() + "拿" + request.wanted().describe();
        }
    }

    /** 开箱取货的先后顺序。 */
    private enum Stage { APPROACH, OPEN, WAIT, TRANSFER, CLOSE }
}
