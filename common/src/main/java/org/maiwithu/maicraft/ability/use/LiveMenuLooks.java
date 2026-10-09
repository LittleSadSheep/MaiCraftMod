// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;

import org.maiwithu.maicraft.behavior.menu.ClientMenuChannel;
import org.maiwithu.maicraft.behavior.menu.ClientMenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuSession;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 看看点开的界面的生产实现：认领这一次点开的那份界面，等内容同步完列出里面有什么，再关上。
 *
 * <p>每次点开都新建读端，绑上的就是这一份界面（读端只绑一次，复用上一次的会一直读旧的那份）。
 * 界面认不出（村民交易这类）或内容一直没同步完时列不出内容，照样关上：打开者负责关闭。
 * 关闭走界面会话，一份会话从头用到尾，只请求游戏关一次。
 */
final class LiveMenuLooks implements UseSeams.LooksInMenus {

    /** 等界面内容同步完的期限（刻）；等不到就不列，不把没同步的界面当成空的。 */
    static final int SYNC_WAIT_TICKS = 40;

    private final Supplier<PlayerContext> context;

    LiveMenuLooks(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public Optional<UseSeams.MenuLook> opened(int menuBefore) {
        PlayerContext current = context.get();
        LocalPlayer player = current == null ? null : current.localPlayer();
        if (player == null || player.containerMenu == player.inventoryMenu
                || player.containerMenu.containerId == menuBefore) {
            return Optional.empty();
        }
        ClientMenuChannel channel = ClientMenuChannel.claimCurrent(context);
        return channel == null ? Optional.empty() : Optional.of(new Look(channel, new ClientMenuContent(context)));
    }

    /** 看一次界面：等同步 → 列内容 → 关上。 */
    private static final class Look implements UseSeams.MenuLook {

        private enum Stage { WAIT_SYNC, CLOSE, DONE }

        private final ClientMenuChannel channel;
        private final MenuContent content;
        private Stage stage = Stage.WAIT_SYNC;
        private int waited;
        private Map<String, Integer> contents;
        private MenuSession session;

        Look(ClientMenuChannel channel, MenuContent content) {
            this.channel = channel;
            this.content = content;
        }

        @Override public Optional<Map<String, Integer>> contents() {
            return Optional.ofNullable(contents);
        }

        @Override public ActionStatus tick(TickContext tick) {
            return switch (stage) {
                case WAIT_SYNC -> waitSync();
                case CLOSE -> closeMenu(tick);
                case DONE -> ActionStatus.done();
            };
        }

        // 同步完了就按物品合并列出来；等到期限还没同步完就不列，直接去关。
        private ActionStatus waitSync() {
            Optional<MenuContent.Reading> reading = content.current();
            if (reading.isPresent()) {
                contents = merged(reading.get().containerSnapshots());
                stage = Stage.CLOSE;
                return ActionStatus.progressed();
            }
            if (++waited > SYNC_WAIT_TICKS || !channel.stillOpen()) {
                stage = Stage.CLOSE;
            }
            return ActionStatus.running();
        }

        // 关上：一份会话从头用到尾；界面先一步没了（玩家关掉、被顶掉）也算关上了。
        private ActionStatus closeMenu(TickContext tick) {
            if (session == null) {
                MenuSession.Claim claim = MenuSession.claim(channel);
                if (claim instanceof MenuSession.Claim.Refused) {
                    // 认领不了：界面已经不在，或光标上挂着不是自己拿的东西；后者照样请游戏关一次，
                    // 光标上的东西由原版的关闭流程还回背包。
                    if (channel.stillOpen()) channel.requestClose();
                    stage = Stage.DONE;
                    return ActionStatus.done();
                }
                session = ((MenuSession.Claim.Owned) claim).session();
            }
            return switch (session.closeNow(channel, tick.player().clientTick())) {
                case MenuSession.Closing.Closed closed -> {
                    stage = Stage.DONE;
                    yield ActionStatus.done();
                }
                case MenuSession.Closing.Failed failed -> ActionStatus.failed(failed.problem());
                default -> ActionStatus.running();
            };
        }

        // 任务被取消时界面还开着：请游戏关一次，不把点开的界面留给玩家收拾。
        @Override public void close() {
            if (stage != Stage.DONE && channel.stillOpen()) {
                channel.requestClose();
            }
        }

        @Override public String describe() {
            return stage == Stage.WAIT_SYNC ? "等点开的界面同步完" : "关上点开的界面";
        }

        // 按物品合并的界面内容；数量大的全列，不悄悄截断。
        private static Map<String, Integer> merged(List<SlotSnapshot> snapshots) {
            Map<String, Integer> merged = new LinkedHashMap<>();
            for (SlotSnapshot snapshot : snapshots) {
                if (snapshot.isEmpty()) continue;
                merged.merge(BuiltInRegistries.ITEM.getKey(snapshot.stack().getItem()).toString(),
                        snapshot.count(), Integer::sum);
            }
            return merged;
        }
    }
}
