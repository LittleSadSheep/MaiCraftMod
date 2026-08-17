// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;

/**
 * One coherent view of the local player for exactly one client tick.
 *
 * <p>Tasks must not retain the player, level, or connection obtained from this object. They retain
 * logical task state and ask the boundary for a fresh context on the next tick instead.</p>
 */
public interface LocalPlayerContext {
    Minecraft minecraft();

    LocalPlayer player();

    ClientLevel level();

    MultiPlayerGameMode gameMode();

    ClientPacketListener connection();

    BodyControlPort body();

    NativeActionPort actions();

    MenuPort menus();

    long bodyEpoch();

    long controlRevision();

    long tickRevision();

    /** True only while this is still the current context on the client thread. */
    boolean isCurrent();

    /** True while automation owns this current body/control epoch. */
    boolean permitsNativeActions();

    /**
     * True while this actor tick's single native-mutation slot has not been consumed.  Read-only
     * receipt polling still uses {@link #permitsNativeActions()} after a submission.
     */
    boolean mutationAvailable();

    default void requireCurrent() {
        if (!isCurrent()) {
            throw new IllegalStateException("the local-player context is stale");
        }
    }
}
