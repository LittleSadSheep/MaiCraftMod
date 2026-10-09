// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.game.menu.MenuSynchronization;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 界面内容读取的读端：把当前打开的容器界面按布局分侧，读成搬运判定要的分侧读数。
 *
 * <p>两侧怎么分交给布局判定：证明不了的模组界面给空，不照着普通箱子的样子报两侧。
 * 界面刚打开、格子还没同步完时也给空——服务端的槽位同步还没到，读到的是本地预测的旧样子，
 * 不能把没同步的界面当成"空的容器"报出去；等过一次往返窗口或看到版本变过才给读数。
 */
public final class ClientMenuContent implements MenuContent {

    private final Supplier<PlayerContext> contexts;
    /** 绑定过的标记：绑定推迟到第一次读的时候，读端可以先建、等界面打开后再绑。 */
    private boolean bound;
    /** 界面内容读取绑定的通道；绑定时的界面就是它核对的那一份。 */
    private ClientMenuChannel channel;
    private MenuSlots slots;
    private IntFunction<SlotSnapshot> snapshotAt;
    /** 绑定时的菜单版本与绑定时刻：判断同步是否已经到了。 */
    private int boundStateId;
    private long boundTick;

    public ClientMenuContent(Supplier<PlayerContext> contexts) {
        this.contexts = Objects.requireNonNull(contexts);
    }

    // 绑定推迟到第一次读：动手动作可以先建读端再点开界面，绑上的就是点开的那份。
    private void bindOnce() {
        if (bound) {
            return;
        }
        bound = true;
        PlayerContext context = contexts.get();
        // 上下文不在或没有打开的容器界面时，读端空转：current 一直给空，等界面打开后重新建读端。
        this.channel = ClientMenuChannel.claimCurrent(contexts);
        this.slots = new ClientMenuSlots(contexts);
        this.snapshotAt = slot -> {
            var menu = context == null ? null : context.localPlayer().containerMenu;
            if (menu == null || slot < 0 || slot >= menu.slots.size()) return SlotSnapshot.empty();
            ItemStack stack = menu.getSlot(slot).getItem();
            return stack.isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(stack);
        };
        this.boundStateId = context == null ? -1 : context.localPlayer().containerMenu.getStateId();
        this.boundTick = context == null ? Long.MIN_VALUE : context.clientTick();
    }

    @Override
    public Optional<Reading> current() {
        bindOnce();
        PlayerContext context = contexts.get();
        if (context == null || channel == null) return Optional.empty();
        if (!synced(context)) return Optional.empty();
        return reading(slots, channel, snapshotAt);
    }

    // 同步判断：看到菜单版本变过，或稳定地过了不止一次往返窗口，才算同步到了。
    private boolean synced(PlayerContext context) {
        if (context.localPlayer().containerMenu.getStateId() != boundStateId) return true;
        return context.clientTick() - boundTick > MenuSynchronization.windowTicks(context);
    }

    /**
     * 从布局判定与逐槽快照拼一次分侧读数；纯函数，离线测试用替身摆布局与内容。
     * 布局证明不了时给空：读数只报证明得了两侧的界面。
     */
    static Optional<Reading> reading(MenuSlots slots, MenuChannel channel, IntFunction<SlotSnapshot> snapshotAt) {
        MenuLayout.Layout layout = MenuLayout.classify(slots);
        if (!(layout instanceof MenuLayout.Supported supported)) return Optional.empty();
        List<Integer> containerSlotIds = new ArrayList<>();
        List<SlotSnapshot> containerSnapshots = new ArrayList<>();
        for (int slot : supported.containerSlots()) {
            containerSlotIds.add(slot);
            containerSnapshots.add(snapshotAt.apply(slot));
        }
        List<Integer> playerSlotIds = new ArrayList<>();
        List<SlotSnapshot> playerSnapshots = new ArrayList<>();
        for (int slot : supported.playerSlots()) {
            playerSlotIds.add(slot);
            playerSnapshots.add(snapshotAt.apply(slot));
        }
        return Optional.of(new Reading(channel, slots, containerSlotIds, playerSlotIds,
                containerSnapshots, playerSnapshots));
    }
}
