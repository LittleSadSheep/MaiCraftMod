// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.game.player.PlayerContext;

/** 测试用的角色上下文替身：只按脚本回答刻号、有效性和交互机会，不接触真实客户端。 */
public final class FakePlayerContext implements PlayerContext {
    public long tick;
    public boolean current = true;
    public boolean canInteract = true;
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
    @Override public long clientTick() { return tick; }
    @Override public boolean isCurrent() { return current; }
    @Override public boolean canInteractThisTick() { return canInteract; }
}
