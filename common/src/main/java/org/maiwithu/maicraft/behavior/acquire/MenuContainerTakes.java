// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.time.Instant;
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
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 开箱取货的生产实现：走到记得的箱子跟前、点开、认下界面，把装着想要东西的槽位
 * 一格一格整堆搬进背包，拿够、搬完或背包放不下了就关上。
 *
 * <p>点开、认领界面与等同步走玩家行为层共用的打开容器动作（每次点开都新建读端）。
 * 拿走多少以重新清点为准：引擎在动作结束后按身上的数量结算，这里只保证每一笔都等游戏确认过、
 * 界面关上了。记得的箱子到现场可能已经不在：点不开、界面一直没同步，都如实失败，引擎换别的路。
 */
public final class MenuContainerTakes implements ContainerTakes {

    /** 一次开箱取货的总期限（刻）；到点如实收场，不在一只箱子上耗死。 */
    private static final long GIVE_UP_TICKS = 20L * 30;
    /** 一笔搬运结清后，等两侧内容同步过来的宽限（刻）；过了容器那侧还纹丝不动就是背包放不下。 */
    private static final int SETTLE_TICKS = 5;

    private final BringsPlayerClose close;
    private final Interactions interactions;
    private final ReadsItemTags tags;
    private final WorldMemory memory;
    private final Supplier<PlayerContext> contexts;
    /** 认得出哪些界面：原版加上联动模组证明过的（例如模组的箱子）。 */
    private final MenuLayouts layouts;

    public MenuContainerTakes(BringsPlayerClose close, Interactions interactions, ReadsItemTags tags,
            WorldMemory memory, Supplier<PlayerContext> contexts, MenuLayouts layouts) {
        this.close = Objects.requireNonNull(close, "close");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.tags = Objects.requireNonNull(tags, "tags");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.layouts = Objects.requireNonNull(layouts, "layouts");
    }

    @Override
    public Optional<Action> take(KnownContainer container, ItemRequest request, Permissions permissions) {
        return Optional.of(new TakeAction(container, request, permissions));
    }

    /** 开箱取货的动作：点开 → 逐格搬 → 关上，跨刻推进。 */
    private final class TakeAction implements Action {

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
        /** 正在等结算的那一笔点下去之前，容器那一侧想要的东西有几件；没有在等的为 -1。 */
        private int beforeMove = -1;
        private int quietTicks;

        TakeAction(KnownContainer container, ItemRequest request, Permissions permissions) {
            this.container = container;
            this.request = request;
            this.at = BlockPos.containing(container.x(), container.y(), container.z());
            // 走过去能动多少地形按这次任务的许可来，不另开一套默认档。
            this.opening = new ClientMenuOpening(at, permissions, layouts, close, interactions, contexts);
        }

        @Override
        public ActionStatus tick(TickContext tick) {
            if (++waited > GIVE_UP_TICKS) {
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

        // 逐格整堆搬：上一笔还在等确认就本刻不动手；拿够、没有可搬的或背包放不下了就去关界面。
        private ActionStatus transfer() {
            Optional<MenuContent.Reading> reading = menu.reading();
            if (reading.isEmpty()) {
                // 界面被合上了（打断或服务端关的）：拿走多少由引擎清点，这里照常收场。
                stage = Stage.DONE;
                return ActionStatus.done();
            }
            MenuContent.Reading open = reading.get();
            if (startTotal < 0) {
                startTotal = matchedTotal(open);
            }
            if (menu.busy()) return ActionStatus.running();
            int now = matchedTotal(open);
            if (beforeMove >= 0) {
                if (now == beforeMove && ++quietTicks <= SETTLE_TICKS) return ActionStatus.running();
                // 点了容器那侧纹丝不动：背包放不下了，不再硬点。
                boolean stuck = now == beforeMove;
                beforeMove = -1;
                quietTicks = 0;
                if (stuck) return toClose(open);
            }
            Integer next = nextMatchedSlot(open);
            if (startTotal - now >= request.count() || next == null) {
                return toClose(open);
            }
            beforeMove = now;
            menu.quickMove(next);
            return ActionStatus.progressed();
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

        // 被生存需求打断：正在走、正在点的先停住；界面开着就请游戏关上，回来时引擎按身上的清点再决定。
        @Override
        public void pause() {
            opening.pause();
            if (menu != null) menu.abandon();
        }

        // 收尾：打开动作一并收尾；界面还开着就请游戏关上，不让它悬着占着身体。
        @Override
        public void close() {
            opening.close();
            if (closing != null) closing.close();
            if (menu != null && stage != Stage.DONE) menu.abandon();
        }

        @Override
        public String describe() {
            return "从" + container.name() + "拿" + request.wanted().describe();
        }
    }

    /** 开箱取货的先后顺序。 */
    private enum Stage { OPEN, TRANSFER, CLOSE, DONE }
}
