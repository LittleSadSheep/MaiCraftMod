// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;

import org.maiwithu.maicraft.behavior.inventory.spi.NetworkStorage;
import org.maiwithu.maicraft.behavior.menu.HeldItemMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 往 ME 网络里存东西的生产实现：拿出身上的无线终端打开（不用走路）→ 核对是无线终端的界面、等连接状态同步过来
 * → 把要存的几样东西一样样、一格格快速移动进网络（像玩家潜行点背包格）→ 都存完才关上，不每样开关一次。
 *
 * <p>每一下按背包里那种东西一共少了几件结算，少了才记"存进了网络"；点了一会儿没少，是网络放不下（存储满了、
 * 不收这种东西），如实收场。没连上网络（超出访问点范围、没电）照抄界面上的原因收场，腾地方改走箱子或丢。
 */
public final class MenuNetworkStores implements NetworkStorage {

    /** 一次存东西的总期限（刻）：拿出终端、打开、存几格、关上，半分钟足够；到点如实收场。 */
    private static final long GIVE_UP_TICKS = 20L * 30;
    /** 打开后等连接状态同步过来的期限（刻）。 */
    private static final int LINK_WAIT_TICKS = 40;
    /** 快速移动发出去后等背包同步过来的宽限（刻）；过了还没少就是网络放不下。 */
    private static final int SETTLE_TICKS = 10;

    private final Ae2Compat compat;
    private final PlayerServices services;

    public MenuNetworkStores(Ae2Compat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public boolean available() {
        return carriedTerminal().isPresent();
    }

    @Override public Optional<Action> store(List<BackpackStack> stacks, String purpose, TaskRecords records) {
        if (stacks.isEmpty()) return Optional.empty();
        return carriedTerminal().map(terminal -> new Storing(terminal, stacks, purpose, records));
    }

    // 身上（主背包、快捷栏、副手）第一件绑过访问点的无线终端的物品 ID。
    private Optional<String> carriedTerminal() {
        PlayerContext player = services.context().get();
        if (player == null || player.localPlayer() == null) return Optional.empty();
        var inventory = player.localPlayer().getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty() && compat.linkedWirelessTerminal(stack)) {
                return Optional.of(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            }
        }
        return Optional.empty();
    }

    private enum Stage { OPEN, SYNC, STORE, CLOSE, DONE }

    /** 存几堆进网络：打开 → 等连上 → 一样样一格格快速移动 → 关上，跨刻推进。 */
    private final class Storing implements Action {

        private final String terminal;
        private final List<BackpackStack> stacks;
        /** 正在存第几样。 */
        private int current;
        /** 这一样确认存进的件数。 */
        private int storedThis;
        private BackpackStack stack;
        private final String purpose;
        private final TaskRecords records;
        private final MenuOpening opening;
        private Stage stage = Stage.OPEN;
        private OpenedMenu menu;
        private Action closing;
        private long waited;
        private int linkWaited;
        /** 确认存进网络的件数。 */
        private int stored;
        /** 正在等结算的那一下：发之前背包里这种东西一共几件（没有在等的为 -1），与等了几刻。 */
        private int before = -1;
        private int quiet;
        /** 收场的原因：一件都没存进时按它失败；存进了就算做完。 */
        private Problem stopReason;
        /** 最近一样存不进的原因：样样都存不进时按它收场。 */
        private Problem lastProblem;

        Storing(String terminal, List<BackpackStack> stacks, String purpose, TaskRecords records) {
            this.terminal = terminal;
            this.stacks = List.copyOf(stacks);
            this.stack = this.stacks.getFirst();
            this.purpose = purpose;
            this.records = records;
            this.opening = new HeldItemMenuOpening(terminal, services.menuLayouts(), services.toMainhand(),
                    services.interactions(), services.context());
        }

        @Override public ActionStatus tick(TickContext tick) {
            if (++waited > GIVE_UP_TICKS && stage.ordinal() < Stage.CLOSE.ordinal()) {
                stop(Problem.of(Problem.Kind.STUCK, "往 " + terminal + " 连着的网络存东西超时", null));
            }
            return switch (stage) {
                case OPEN -> open(tick);
                case SYNC -> sync(tick);
                case STORE -> storeNext();
                case CLOSE -> closeMenu(tick);
                case DONE -> result();
            };
        }

        // 拿出终端右键打开：打不开把原因原样交回，腾地方改走别的路。
        private ActionStatus open(TickContext tick) {
            ActionStatus status = opening.tick(tick);
            if (!(status instanceof ActionStatus.Done)) return status;
            menu = opening.opened().orElseThrow();
            stage = Stage.SYNC;
            return ActionStatus.progressed();
        }

        // 核对是无线终端的界面，等连接状态同步过来；没连上就照抄界面上的原因收场。
        private ActionStatus sync(TickContext tick) {
            AbstractContainerMenu raw = tick.player().localPlayer().containerMenu;
            if (menu.reading().isEmpty()) {
                return finish(Problem.of(Problem.Kind.TARGET_GONE, "终端界面刚打开就被关上了", null));
            }
            if (!compat.wirelessMenu(raw)) {
                return stopNow(Problem.of(Problem.Kind.TARGET_GONE, "点开的不是 " + terminal + " 的界面", null));
            }
            Optional<Boolean> linked = compat.networkLinked(raw);
            if (linked.isEmpty()) {
                if (++linkWaited > LINK_WAIT_TICKS) {
                    return stopNow(Problem.of(Problem.Kind.STUCK, "终端界面开了，网络连接状态一直没同步过来", null));
                }
                return ActionStatus.running();
            }
            if (!linked.get()) {
                String why = compat.unlinkedWhy(raw).map(text -> "（" + text + "）").orElse("（没电或没频道）");
                return stopNow(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, terminal + " 没连上 ME 网络" + why + "，存不了", null));
            }
            stage = Stage.STORE;
            return ActionStatus.progressed();
        }

        // 上一下少了几件就记账；没在等的话挑背包里下一格这种东西快速移动进网络，存够这一样接着存下一样，都存完才关上。
        private ActionStatus storeNext() {
            Optional<MenuContent.Reading> reading = menu.reading();
            if (reading.isEmpty()) return finish(null);
            if (menu.busy()) return ActionStatus.running();
            int now = total(reading.get().playerSnapshots());
            if (before >= 0) {
                if (now < before) {
                    int moved = before - now;
                    stored += moved;
                    storedThis += moved;
                    records.change(new Change(Change.Kind.ITEM_STORED, stack.itemId(), moved,
                            purpose + "：存进了 " + terminal + " 连着的网络"));
                    before = -1;
                } else if (++quiet < SETTLE_TICKS) {
                    return ActionStatus.running();
                } else {
                    // 这一样放不下（存储满了、不收这种东西）：记一笔，接着存下一样。
                    before = -1;
                    return nextKind(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                            "网络放不下 " + stack.itemId() + "（存储满了或不收这种东西）", null));
                }
            }
            if (storedThis >= stack.count()) return nextKind(null);
            int index = slotHolding(reading.get().playerSnapshots());
            if (index < 0) {
                return nextKind(storedThis > 0 ? null
                        : Problem.of(Problem.Kind.NEED_ITEM, "背包里已经没有 " + stack.itemId() + " 了", null));
            }
            // 本刻发不出去（上一下没结清、界面没画好、没有交互机会）就下一刻再点，不算点过。
            if (!menu.quickMove(reading.get().playerSlotIds().get(index))) return ActionStatus.running();
            before = now;
            quiet = 0;
            return ActionStatus.progressed();
        }

        // 这一样存完或存不进：存不进的记一笔试过；还有下一样就接着存，都过完了才关上。
        private ActionStatus nextKind(Problem problem) {
            if (problem != null) {
                lastProblem = problem;
                records.attempt("把 " + stack.itemId() + " 存进 " + terminal + " 连着的网络", problem.message());
            }
            if (++current >= stacks.size()) {
                return stopNow(stored > 0 ? null : lastProblem);
            }
            stack = stacks.get(current);
            storedThis = 0;
            quiet = 0;
            return ActionStatus.progressed();
        }

        // 关界面：打开者负责关闭；关不上照常收场。
        private ActionStatus closeMenu(TickContext tick) {
            if (closing == null) closing = menu.closing();
            ActionStatus status = closing.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            stage = Stage.DONE;
            return result();
        }

        // 收场：存进了就算做完；一件都没存进就按收场原因失败，腾地方改走别的路。
        private ActionStatus result() {
            if (stored > 0 || stopReason == null) return ActionStatus.done();
            return ActionStatus.failed(stopReason);
        }

        private ActionStatus stopNow(Problem reason) {
            stop(reason);
            return ActionStatus.progressed();
        }

        // 收场：还没打开界面就没有要关的，直接结束；开着就去关。
        private void stop(Problem reason) {
            if (stopReason == null) stopReason = reason;
            stage = menu == null ? Stage.DONE : Stage.CLOSE;
        }

        // 界面已经不在了：不用关，直接收场。
        private ActionStatus finish(Problem reason) {
            if (stopReason == null) stopReason = reason;
            stage = Stage.DONE;
            return result();
        }

        private int total(List<SlotSnapshot> player) {
            int sum = 0;
            for (SlotSnapshot slot : player) {
                if (!slot.isEmpty() && itemId(slot).equals(stack.itemId())) sum += slot.count();
            }
            return sum;
        }

        private int slotHolding(List<SlotSnapshot> player) {
            for (int i = 0; i < player.size(); i++) {
                if (!player.get(i).isEmpty() && itemId(player.get(i)).equals(stack.itemId())) return i;
            }
            return -1;
        }

        private String itemId(SlotSnapshot slot) {
            return BuiltInRegistries.ITEM.getKey(slot.stack().getItem()).toString();
        }

        @Override public void pause() {
            opening.pause();
            if (menu != null) menu.abandon();
        }

        // 收尾：打开动作一并收尾；界面还开着就请游戏关上，光标上的东西由原版的关闭流程还回背包。
        @Override public void close() {
            opening.close();
            if (closing != null) closing.close();
            if (menu != null && stage != Stage.DONE) menu.abandon();
        }

        @Override public String describe() {
            return stacks.size() == 1
                    ? "把 " + stack.count() + " 个 " + stack.itemId() + " 存进 " + terminal + " 连着的网络"
                    : "把 " + stacks.size() + " 样东西存进 " + terminal + " 连着的网络（正在存 " + stack.itemId() + "）";
        }
    }
}
