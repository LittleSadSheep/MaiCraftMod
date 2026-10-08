// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;

/**
 * {@link PlayerContext} 的每刻实现：保存这一刻的具体玩家、世界与连接，
 * 是否仍然有效、本刻还能不能动手，都回到创建它的 {@link PlayerControlBoundary} 核对。
 */
final class DefaultPlayerContext implements PlayerContext {
    private final PlayerControlBoundary owner;
    private final LocalPlayer player;
    private final ClientLevel level;
    private final ClientPacketListener connection;
    private final long bodyEpoch;
    private final long controlRevision;
    private final long tickRevision;
    private final boolean permitsNativeActions;

    DefaultPlayerContext(
            PlayerControlBoundary owner,
            LocalPlayer player,
            ClientLevel level,
            ClientPacketListener connection,
            long bodyEpoch,
            long controlRevision,
            long tickRevision,
            boolean permitsNativeActions) {
        this.owner = owner;
        this.player = player;
        this.level = level;
        this.connection = connection;
        this.bodyEpoch = bodyEpoch;
        this.controlRevision = controlRevision;
        this.tickRevision = tickRevision;
        this.permitsNativeActions = permitsNativeActions;
    }

    @Override public LocalPlayer localPlayer() { return player; }
    @Override public ClientLevel level() { return level; }
    @Override public ClientPacketListener connection() { return connection; }
    @Override public PlayerInput input() { return owner.input(); }
    @Override public long clientTick() { return tickRevision; }
    boolean permitsNativeActions() { return permitsNativeActions && isCurrent(); }
    @Override public boolean canInteractThisTick() { return owner.mutationAvailable(this); }
    @Override public boolean isCurrent() { return owner.isCurrent(this); }

    /** 本刻的玩家与控制权版本；交互轨的端口用它核对确认仍然属于同一次操作。 */
    long bodyEpoch() { return bodyEpoch; }
    long controlRevision() { return controlRevision; }

    /** 上下文还有效也不够，玩家必须仍由自动化控制；例如 F8 已归还输入时就不能继续发操作。 */
    void requireSubmissionAuthority() {
        requireCurrent();
        if (!permitsNativeActions() || !owner.input().automationOwnsControls()) {
            throw new IllegalStateException("automation does not own the local player");
        }
    }

    /** 检查控制权后占用本刻的一次操作机会，不能绕过外层计数直接多点一次。 */
    void claimMutation() {
        requireSubmissionAuthority();
        owner.claimMutation(this);
    }

    private void requireCurrent() {
        if (!isCurrent()) {
            throw new IllegalStateException("the local-player context is stale");
        }
    }
}
