// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 拿着遥控器按一下某个键：按住 10 刻再松开（按住期间每 5 刻续一下），叫电梯这类只要接收端通一下电的事。
 * 遥控器不在手里先拿到主手；手里的遥控器这个键没绑要的频率（身上有好几个遥控器拿错了）就照实停下，不乱按。
 * 遥控器不回报收端动没动，叫没叫来看电梯自己。
 */
final class RemotePress implements Action {

    /** 按住几刻再松开，隔几刻续一次"还按着"（收端 30 刻收不到当松开）。 */
    static final int HOLD_TICKS = 10;
    static final int KEEPALIVE = 5;

    private final CreateCompat compat;
    private final PlayerServices services;
    private final int key;
    private final List<String> frequency;
    private Action current;
    private boolean fetched;
    private long pressedAt = Long.MIN_VALUE;
    private boolean held;

    /**
     * @param key       第几个键（0 上、1 下、2 左、3 右、4 跳、5 潜行）
     * @param frequency 这个键该绑的频率（两格，写法同 {@link ElevatorCalls#frequencyKey}）
     */
    RemotePress(CreateCompat compat, PlayerServices services, int key, List<String> frequency) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.key = key;
        this.frequency = List.copyOf(frequency);
    }

    // 拿遥控器 → 核对这个键的频率 → 按下 → 按住 10 刻（每 5 刻续一下）→ 松开。
    @Override public ActionStatus tick(TickContext tick) {
        PlayerContext context = services.context().get();
        LocalPlayer player = context == null ? null : context.localPlayer();
        if (player == null) return stop(Problem.of(Problem.Kind.INTERNAL_ERROR, "角色不在，按不了遥控器"));
        if (current != null) {
            ActionStatus status = current.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            current.close();
            current = null;
            if (status instanceof ActionStatus.Failed failed) return stop(failed.problem());
            return ActionStatus.progressed();
        }
        ItemStack hand = held(player);
        if (pressedAt == Long.MIN_VALUE) {
            if (hand.isEmpty()) {
                if (fetched) return stop(Problem.of(Problem.Kind.STUCK, "拿了遥控器，手里却没有"));
                Optional<Action> move = services.toMainhand().moveToMainhand(RemoteButtons.REMOTE);
                if (move.isEmpty()) {
                    return stop(Problem.of(Problem.Kind.NEED_ITEM, "身上没有遥控器（" + RemoteButtons.REMOTE + "）"));
                }
                fetched = true;
                current = move.get();
                return ActionStatus.progressed();
            }
            List<List<String>> keys = ElevatorCalls.keysOf(compat, hand);
            if (key >= keys.size() || !keys.get(key).equals(frequency)) {
                return stop(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "手里这个遥控器的「" + name() + "」键没绑呼梯的频率（"
                        + ElevatorCalls.describe(frequency) + "），身上可能有好几个遥控器"));
            }
            compat.remoteButtons(List.of(key), true);
            held = true;
            pressedAt = tick.gameTick();
            return ActionStatus.progressed();
        }
        if (hand.isEmpty()) return stop(Problem.of(Problem.Kind.STUCK, "按着的时候遥控器不在手里了"));
        long heldFor = tick.gameTick() - pressedAt;
        if (heldFor >= HOLD_TICKS) {
            release();
            return ActionStatus.done();
        }
        if (heldFor % KEEPALIVE == 0) compat.remoteButtons(List.of(key), true);
        return ActionStatus.running();
    }

    // 服务端认手里的遥控器：主手是遥控器用主手，不是看副手。
    private static ItemStack held(LocalPlayer player) {
        if (ElevatorCalls.isRemote(player.getMainHandItem())) return player.getMainHandItem();
        if (ElevatorCalls.isRemote(player.getOffhandItem())) return player.getOffhandItem();
        return ItemStack.EMPTY;
    }

    private void release() {
        if (!held) return;
        compat.remoteButtons(List.of(key), false);
        held = false;
    }

    private ActionStatus stop(Problem problem) {
        release();
        return ActionStatus.failed(problem);
    }

    private String name() {
        return key >= 0 && key < ElevatorCalls.REMOTE_KEYS.size() ? ElevatorCalls.REMOTE_KEYS.get(key) : String.valueOf(key);
    }

    // 被打断：松开按着的键，回来从头按一次。
    @Override public void pause() {
        if (current != null) current.pause();
        release();
        pressedAt = Long.MIN_VALUE;
    }

    @Override public void close() {
        if (current != null) {
            current.close();
            current = null;
        }
        release();
    }

    @Override public Interruptibility interruptibility() {
        return current != null ? current.interruptibility() : Interruptibility.WORKING;
    }

    @Override public String describe() {
        return "用遥控器的「" + name() + "」键呼梯";
    }
}
