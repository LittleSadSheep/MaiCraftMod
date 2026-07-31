// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;

/** Default immutable per-tick context created by {@link ClientActorBoundary}. */
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
    @Override public boolean isCurrent() { return owner.isCurrent(this); }

    void requireSubmissionAuthority() {
        requireCurrent();
        if (!permitsNativeActions || !owner.body().automationOwnsControls()) {
            throw new IllegalStateException("automation does not own the local player");
        }
    }

    void claimMutation() {
        requireSubmissionAuthority();
        owner.claimMutation(this);
    }
}
