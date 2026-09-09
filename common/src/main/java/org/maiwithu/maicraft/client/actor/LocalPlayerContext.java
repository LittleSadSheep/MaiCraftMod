// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;

/**
 * 当前一刻的玩家、世界、网络与动作入口。每刻重新取得，用来保证这次操作面对的是同一个有效身体。
 * 不能把这个入口留到下一刻直接使用；任务本身可以保存进度，执行时重新核对当前身体。
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

    /** 这份入口是否仍属于当前客户端刻和当前玩家。 */
    boolean isCurrent();

    /** 自动化是否仍允许在这份当前入口上处理游戏动作。 */
    boolean permitsNativeActions();

    /** 本刻是否还可提交一次操作；已经提交后仍可读结果，但不能再提交第二次。 */
    boolean mutationAvailable();

    default void requireCurrent() {
        if (!isCurrent()) {
            throw new IllegalStateException("the local-player context is stale");
        }
    }
}
