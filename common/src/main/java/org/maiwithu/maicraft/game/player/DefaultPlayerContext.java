// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;

/**
 * {@link PlayerContext} 的每刻实现：保存这一刻的具体玩家、世界与连接，
 * 是否仍然有效、本刻还能不能动手，都回到创建它的 {@link PlayerControlBoundary} 核对。
 */
final class DefaultPlayerContext implements PlayerContext {
    private final PlayerControlBoundary owner;
    private final LocalPlayer player;
    private final ClientLevel level;
    private final ClientPacketListener connection;
    private final InteractionSender interactionSender;
    private final MenuActions menuActions;
    // 背包视图只包住当刻玩家对象，随上下文一起过期；读它没有副作用，不用等控制权。
    private final BackpackView backpack;
    private final long bodyEpoch;
    private final long controlRevision;
    private final long tickRevision;
    private final boolean permitsNativeActions;

    DefaultPlayerContext(
            PlayerControlBoundary owner,
            LocalPlayer player,
            ClientLevel level,
            ClientPacketListener connection,
            InteractionSender interactionSender,
            MenuActions menuActions,
            long bodyEpoch,
            long controlRevision,
            long tickRevision,
            boolean permitsNativeActions) {
        this.owner = owner;
        this.player = player;
        this.level = level;
        this.connection = connection;
        this.interactionSender = interactionSender;
        this.menuActions = menuActions;
        this.backpack = new ClientBackpackView(player);
        this.bodyEpoch = bodyEpoch;
        this.controlRevision = controlRevision;
        this.tickRevision = tickRevision;
        this.permitsNativeActions = permitsNativeActions;
    }

    @Override public LocalPlayer localPlayer() { return player; }
    @Override public ClientLevel level() { return level; }
    @Override public ClientPacketListener connection() { return connection; }
    @Override public PlayerInput input() { return owner.input(); }
    // 两个动手入口在启动时接进控制边界；没有接上之前上下文如实交出 null，由调用方等待接入。
    @Override public InteractionSender interactionSender() { return interactionSender; }
    @Override public MenuActions menuActions() { return menuActions; }
    @Override public BackpackView backpack() { return backpack; }
    @Override public long clientTick() { return tickRevision; }
    @Override public boolean isCurrent() { return owner.isCurrent(this); }

    /** 这一刻开始时自动化是否拥有控制权、且上下文仍属于本刻；按住使用键的投影续期只看这个，不占用交互机会。 */
    boolean permitsNativeActions() { return permitsNativeActions && isCurrent(); }

    // 能不能动手要同时满足三条：上下文属于本刻、这一刻开始时自动化就拥有控制权且现在仍拥有（F8 已归还就不行）、
    // 本刻还没出过手。控制权在本刻刚换过手时，控制边界已把本刻的机会标成用过。
    @Override public boolean canInteractThisTick() {
        return permitsNativeActions() && owner.input().automationOwnsControls() && owner.mutationAvailable(this);
    }

    @Override public boolean tryClaimInteraction() {
        if (!canInteractThisTick()) return false;
        owner.claimMutation(this);
        return true;
    }

    /** 本刻的玩家与控制权版本；交互轨的端口用它核对确认仍然属于同一次操作。 */
    long bodyEpoch() { return bodyEpoch; }
    long controlRevision() { return controlRevision; }

}
