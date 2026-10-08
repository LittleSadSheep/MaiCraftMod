// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import net.minecraft.client.multiplayer.ClientPacketListener;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.game.menu.MenuActions;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.game.player.PlayerContext;

/** 测试用的角色上下文替身：只按脚本回答刻号、有效性和交互机会，不接触真实客户端。 */
public final class FakePlayerContext implements PlayerContext {
    public long tick;
    public boolean current = true;
    public boolean canInteract = true;
    /** 本刻已经占用交互机会的刻号；同一刻第二次占用会被拒绝，换刻自动恢复。 */
    private long claimedTick = Long.MIN_VALUE;
    /** 需要经过上下文取动手入口的测试，先在这里放进替身；默认没有，读的时候如实报缺。 */
    public InteractionSender sender;
    public MenuActions menu;
    public final LocalPlayer player;

    public FakePlayerContext(LocalPlayer player) {
        this.player = player;
    }

    public FakePlayerContext nextTick() {
        tick++;
        return this;
    }

    public FakePlayerContext expired() {
        current = false;
        return this;
    }

    @Override public LocalPlayer localPlayer() { return player; }
    @Override public ClientLevel level() { return null; }
    @Override public ClientPacketListener connection() { return null; }
    @Override public long clientTick() { return tick; }
    @Override public boolean isCurrent() { return current; }
    @Override public boolean canInteractThisTick() { return current && canInteract && claimedTick != tick; }
    @Override public boolean tryClaimInteraction() {
        if (!canInteractThisTick()) return false;
        claimedTick = tick;
        return true;
    }
    @Override public PlayerInput input() {
        // 交互协议测试不经过按键输入；哪天真用到了，把这个占位换成输入替身。
        throw new UnsupportedOperationException("FakePlayerContext 没有输入入口");
    }
    @Override public InteractionSender interactionSender() {
        if (sender == null) throw new UnsupportedOperationException("FakePlayerContext 没有交互提交入口");
        return sender;
    }
    @Override public MenuActions menuActions() {
        if (menu == null) throw new UnsupportedOperationException("FakePlayerContext 没有容器界面入口");
        return menu;
    }
}
