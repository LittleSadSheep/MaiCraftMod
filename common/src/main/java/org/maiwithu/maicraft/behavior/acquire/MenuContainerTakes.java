// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.KnownContainer;
import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.ReportsUnconfirmed;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 开箱取货的生产实现：走到记得的箱子跟前、点开、认下界面，按要的量把装着想要东西的槽位
 * 搬进背包（整堆够用整堆拿，尾数按件拿，拿够就停），搬完、拿够或背包放不下了就关上。
 *
 * <p>点开、认领界面与等同步走玩家行为层共用的打开容器动作（每次点开都新建读端）。
 * 每一笔拿几件、点哪几下由取货计划给出，这里只按计划逐刻点、等游戏确认；
 * 拿走多少以重新清点为准：引擎在动作结束后按身上的数量结算，这里保证每一笔都等确认过、界面关上了。
 * 点出去了却一直没等到结果的那几下（背包塞满点不进、界面中途被关）实现交回接缝，
 * 原样交回给引擎转述，不与确认拿到的混在一起。记得的箱子到现场可能已经不在：
 * 点不开、界面一直没同步，都如实失败，引擎换别的路。
 */
public final class MenuContainerTakes implements ContainerTakes {

    /** 一次开箱取货的总期限（刻）；到点如实收场，不在一只箱子上耗死。 */
    private static final long GIVE_UP_TICKS = 20L * 30;
    /** 一笔搬运结清后，等两侧内容同步过来的宽限（刻）；过了容器那侧还纹丝不动就是背包放不下。 */
    private static final int SETTLE_TICKS = 5;

    private final ReadsItemTags tags;
    private final WorldMemory memory;
    private final Supplier<PlayerContext> contexts;
    /** 打开一只箱子的动作怎么建：按箱子位置与这次任务的许可。 */
    private final BiFunction<BlockPos, Permissions, MenuOpening> openings;

    /**
     * @param layouts 认得出哪些界面：原版加上联动模组证明过的（例如模组的箱子）
     */
    public MenuContainerTakes(BringsPlayerClose close, Interactions interactions, ReadsItemTags tags,
            WorldMemory memory, Supplier<PlayerContext> contexts, MenuLayouts layouts) {
        this(tags, memory, contexts, clientOpenings(close, interactions, contexts, layouts));
    }

    /** 打开箱子的动作由调用方给：生产走靠近与右键点开，测试换成替身箱子。 */
    MenuContainerTakes(ReadsItemTags tags, WorldMemory memory, Supplier<PlayerContext> contexts,
            BiFunction<BlockPos, Permissions, MenuOpening> openings) {
        this.tags = Objects.requireNonNull(tags, "tags");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.openings = Objects.requireNonNull(openings, "openings");
    }

    // 生产的打开方式：走到够得着的地方、右键点开、认领并等同步；走过去能动多少地形按这次任务的许可来。
    private static BiFunction<BlockPos, Permissions, MenuOpening> clientOpenings(BringsPlayerClose close,
            Interactions interactions, Supplier<PlayerContext> contexts, MenuLayouts layouts) {
        Objects.requireNonNull(close, "close");
        Objects.requireNonNull(interactions, "interactions");
        Objects.requireNonNull(layouts, "layouts");
        return (at, permissions) -> new ClientMenuOpening(at, permissions, layouts, close, interactions, contexts);
    }

    @Override
    public Optional<Action> take(KnownContainer container, ItemRequest request, Permissions permissions) {
        return Optional.of(new TakeAction(container, request, permissions));
    }

    /** 开箱取货的动作：点开 → 逐格搬 → 关上，跨刻推进；没能确认的点击经交回接缝原样交回。 */
    private final class TakeAction implements Action, ReportsUnconfirmed {

        private final KnownContainer container;
        private final ItemRequest request;
        private final BlockPos at;
        private final MenuOpening opening;
        private Stage stage = Stage.OPEN;
        private OpenedMenu menu;
        private Action closing;
        private int waited;
        /** 刚打开时容器那一侧装着想要的东西的总件数：拿走多少按它与当刻的差算。 */
        private int startTotal = -1;
        /** 正在点的那一笔动手之前，容器那一侧想要的东西有几件；没有在点的为 -1。 */
        private int beforeMove = -1;
        /** 正在点的那一笔按计划要搬几件、要点哪几下：结算与交回"没能确认"时说得清。 */
        private int moveAmount;
        private int moveClicks;
        /** 正在点的那一笔还剩哪几下没点；一笔的点击逐刻发，光标上的东西等放进背包才撒手。 */
        private final Deque<ContainerTakePlan.Click> pressing = new ArrayDeque<>();
        /** 点出去了却没能确认结果的交互，一句一笔，经交回接缝交给引擎。 */
        private final List<String> unconfirmed = new ArrayList<>();
        private int quietTicks;

        TakeAction(KnownContainer container, ItemRequest request, Permissions permissions) {
            this.container = container;
            this.request = request;
            this.at = container.cell();
            // 走过去能动多少地形按这次任务的许可来，不另开一套默认档。
            this.opening = openings.apply(at, permissions);
        }

        @Override
        public ActionStatus tick(TickContext tick) {
            if (++waited > GIVE_UP_TICKS) {
                // 总期限到了还在一笔上：点出去没等到结果的那几下照实交回，再以卡住收场。
                noteUnsettledMove("总期限到了");
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "开箱取货超时：" + container.name(), null));
            }
            return switch (stage) {
                case OPEN -> open(tick);
                case TRANSFER -> transfer();
                case CLOSE -> closeMenu(tick);
                case DONE -> ActionStatus.done();
            };
        }

        // 点开：走不到、点不开、界面认不出或一直没同步，都把打开动作的原因原样交回，引擎换路。
        private ActionStatus open(TickContext tick) {
            ActionStatus status = opening.tick(tick);
            if (!(status instanceof ActionStatus.Done)) return status;
            menu = opening.opened().orElseThrow();
            stage = Stage.TRANSFER;
            return ActionStatus.progressed();
        }

        // 按量搬：整堆够用整堆拿，尾数按件拿，拿够就停；上一笔没结清就本刻不动手，
        // 拿够、没有可拿的或背包放不下尾数了就去关界面。
        private ActionStatus transfer() {
            Optional<MenuContent.Reading> reading = menu.reading();
            if (reading.isEmpty()) {
                // 界面被合上了（打断或服务端关的）：没等到结果的那几下交回，拿走多少由引擎清点，这里照常收场。
                noteUnsettledMove("界面关上时");
                stage = Stage.DONE;
                return ActionStatus.done();
            }
            MenuContent.Reading open = reading.get();
            if (startTotal < 0) {
                startTotal = matchedTotal(open);
            }
            if (menu.busy()) return ActionStatus.running();
            if (beforeMove < 0) {
                // 上一笔已结清：按还缺的件数盘下一笔要点的几下，冻结动手前容器那侧的总数再动手。
                int now = matchedTotal(open);
                int remaining = request.count() - (startTotal - now);
                Optional<ContainerTakePlan.Move> next = ContainerTakePlan.next(open, this::matches, remaining);
                if (next.isEmpty()) return toClose(open);
                beforeMove = now;
                moveAmount = next.get().amount();
                moveClicks = next.get().clicks().size();
                quietTicks = 0;
                pressing.addAll(next.get().clicks());
            }
            if (!pressing.isEmpty()) {
                // 本刻发不出去（界面刚刷新还没画好、没有交互机会）就下一刻再点：没发出去不是背包放不下。
                if (!press(pressing.peek())) return ActionStatus.running();
                pressing.poll();
                return ActionStatus.progressed();
            }
            // 一笔的几下都点完了：等两侧内容同步过来再结算这一笔。
            int now = matchedTotal(open);
            if ((now == beforeMove || !menu.cursorEmpty()) && ++quietTicks <= SETTLE_TICKS) {
                return ActionStatus.running();
            }
            // 点了容器那侧纹丝不动：多半是背包放不下，不再硬点；但点出去是事实，结果没能确认照实交回。
            boolean stuck = now == beforeMove;
            beforeMove = -1;
            quietTicks = 0;
            if (stuck) {
                unconfirmed.add("从" + container.name() + "拿" + request.wanted().describe()
                        + "：搬 " + moveAmount + " 件的那几下都点出去了，容器那侧一直没动，没能确认结果");
                return toClose(open);
            }
            if (!menu.cursorEmpty()) {
                // 容器那侧动了、光标上还拿着东西：这一笔到底搬走多少没能确认完，也交回一句。
                unconfirmed.add("从" + container.name() + "拿" + request.wanted().describe()
                        + "：点完了光标上还拿着东西，这一笔搬走多少没能确认");
            }
            return ActionStatus.progressed();
        }

        // 点这一笔的下一下：快速移动整堆；普通点击先把要从的格子登记给界面会话（中途关界面时
        // 光标上的东西放回这一格），再发左右键。
        private boolean press(ContainerTakePlan.Click click) {
            if (click.quickMove()) return menu.quickMove(click.slotId());
            if (click.takesToCursor()) menu.noteCursorTakenFrom(click.slotId());
            return menu.click(click.slotId(), click.button());
        }

        // 有一笔点出去了却没等到结算就停了（界面被关、总期限到、被收尾）：点出去的那几下
        // 结果没能确认，一句交回；一下都没发出去的不算，那只是没点成。
        private void noteUnsettledMove(String circumstance) {
            if (beforeMove < 0) return;
            int submitted = moveClicks - pressing.size();
            if (submitted <= 0) return;
            unconfirmed.add("从" + container.name() + "拿" + request.wanted().describe()
                    + "：搬 " + moveAmount + " 件的这笔点了 " + submitted + " 下，"
                    + circumstance + "没能等到结算结果");
            beforeMove = -1;
        }

        // 去关之前把这只箱子此刻有什么记进世界记忆：位置带维度，种类按现场的方块读。
        private ActionStatus toClose(MenuContent.Reading reading) {
            rememberContents(reading);
            stage = Stage.CLOSE;
            return ActionStatus.progressed();
        }

        // 关界面：打开者负责关闭；关不上不冒充没开过，照常收场让引擎清点。
        private ActionStatus closeMenu(TickContext tick) {
            if (closing == null) closing = menu.closing();
            ActionStatus status = closing.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            stage = Stage.DONE;
            return ActionStatus.done();
        }

        // 容器那一侧装着想要的东西的总件数；界面内容按物品标签对上请求。
        private int matchedTotal(MenuContent.Reading reading) {
            int total = 0;
            for (SlotSnapshot snapshot : reading.containerSnapshots()) {
                if (matches(snapshot)) total += snapshot.count();
            }
            return total;
        }

        private boolean matches(SlotSnapshot snapshot) {
            if (snapshot.isEmpty()) return false;
            String itemId = BuiltInRegistries.ITEM.getKey(snapshot.stack().getItem()).toString();
            return request.wanted().matches(itemId, tags.tagsOf(itemId));
        }

        // 世界记忆按物品记"里面有什么"：取东西时按它找箱子；件数以到场再开为准。
        private void rememberContents(MenuContent.Reading reading) {
            PlayerContext current = contexts.get();
            ClientLevel level = current == null ? null : current.level();
            if (level == null) return;
            Map<String, Integer> contents = new LinkedHashMap<>();
            for (SlotSnapshot snapshot : reading.containerSnapshots()) {
                if (snapshot.isEmpty()) continue;
                contents.merge(BuiltInRegistries.ITEM.getKey(snapshot.stack().getItem()).toString(),
                        snapshot.count(), Integer::sum);
            }
            WorldPosition position = new WorldPosition(at.getX(), at.getY(), at.getZ(),
                    level.dimension().location().toString());
            String blockType = BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock()).toString();
            memory.rememberContainerOpened(position, blockType, List.copyOf(contents.keySet()), Instant.now());
        }

        // 光标上拿着东西的那几下停不得：半途撒手会把拆出来的尾数交给关界面的流程，先等它放进背包。
        @Override
        public Interruptibility interruptibility() {
            return !pressing.isEmpty() && !menu.cursorEmpty()
                    ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
        }

        // 被生存需求打断：正在走、正在点的先停住；界面开着就请游戏关上，回来时引擎按身上的清点再决定。
        @Override
        public void pause() {
            opening.pause();
            if (menu != null) menu.abandon();
        }

        // 收尾：打开动作一并收尾；界面还开着就请游戏关上，不让它悬着占着身体。
        // 收尾时一笔还没结清的，点出去的那几下先交回没能确认，再关界面。
        @Override
        public void close() {
            opening.close();
            if (closing != null) closing.close();
            if (menu != null && stage != Stage.DONE) {
                noteUnsettledMove("收尾时");
                menu.abandon();
            }
        }

        @Override
        public List<String> unconfirmedFacts() {
            return List.copyOf(unconfirmed);
        }

        @Override
        public String describe() {
            return "从" + container.name() + "拿" + request.wanted().describe();
        }
    }

    /** 开箱取货的先后顺序。 */
    private enum Stage { OPEN, TRANSFER, CLOSE, DONE }
}
