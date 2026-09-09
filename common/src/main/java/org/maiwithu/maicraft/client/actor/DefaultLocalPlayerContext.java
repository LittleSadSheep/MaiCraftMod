// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;

/** 保存这一刻的具体玩家和连接，所有动作最终回到创建它的 ClientActorBoundary 检查是否仍有效。 */
public final class DefaultLocalPlayerContext implements LocalPlayerContext {
    private final ClientActorBoundary owner;
    private final Minecraft minecraft;
    private final LocalPlayer player;
    private final ClientLevel level;
    private final MultiPlayerGameMode gameMode;
    private final ClientPacketListener connection;
    private final long bodyEpoch;
    private final long controlRevision;
    private final long tickRevision;
    private final boolean permitsNativeActions;

    DefaultLocalPlayerContext(
            ClientActorBoundary owner,
            Minecraft minecraft,
            LocalPlayer player,
            ClientLevel level,
            MultiPlayerGameMode gameMode,
            ClientPacketListener connection,
            long bodyEpoch,
            long controlRevision,
            long tickRevision,
            boolean permitsNativeActions) {
        this.owner = owner;
        this.minecraft = minecraft;
        this.player = player;
        this.level = level;
        this.gameMode = gameMode;
        this.connection = connection;
        this.bodyEpoch = bodyEpoch;
        this.controlRevision = controlRevision;
        this.tickRevision = tickRevision;
        this.permitsNativeActions = permitsNativeActions;
    }

    @Override public Minecraft minecraft() { return minecraft; }
    @Override public LocalPlayer player() { return player; }
    @Override public ClientLevel level() { return level; }
    @Override public MultiPlayerGameMode gameMode() { return gameMode; }
    @Override public ClientPacketListener connection() { return connection; }
    @Override public BodyControlPort body() { return owner.body(); }
    @Override public NativeActionPort actions() { return owner.actions(); }
    @Override public MenuPort menus() { return owner.menus(); }
    @Override public long bodyEpoch() { return bodyEpoch; }
    @Override public long controlRevision() { return controlRevision; }
    @Override public long tickRevision() { return tickRevision; }
    @Override public boolean permitsNativeActions() { return permitsNativeActions && isCurrent(); }
    @Override public boolean mutationAvailable() { return owner.mutationAvailable(this); }
    @Override public boolean isCurrent() { return owner.isCurrent(this); }

    void requireSubmissionAuthority() {
        // 入口还有效也不够，玩家必须仍由自动化控制；例如 F8 已归还输入时就不能继续发操作。
        requireCurrent();
        if (!permitsNativeActions || !owner.body().automationOwnsControls()) {
            throw new IllegalStateException("automation does not own the local player");
        }
    }

    void claimMutation() {
        // 检查控制权后占用本刻的一次操作机会，不能绕过外层计数直接多点一次。
        requireSubmissionAuthority();
        owner.claimMutation(this);
    }
}
