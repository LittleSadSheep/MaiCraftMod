// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.createjetpack;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.player.LocalPlayer;

/**
 * 把喷气背包的两个开关（飞行、悬停）拨到想要的样子：差哪个按哪个，按完等客户端读回一致才算拨好；
 * 读不回就隔几刻再按，每个开关最多按三次，还不对就是"开关状态说不清"，如实交给调用方。
 */
final class JetpackSwitches {

    /** 按完等这么多刻再看读回（模组自己有 150 毫秒的防连按）。 */
    static final int SETTLE_TICKS = 5;
    static final int MAX_PRESSES = 3;

    /** 拨到哪一步了。 */
    enum State { DONE, WORKING, UNCONFIRMED }

    private final CreateJetpackCompat compat;
    private boolean wantActive;
    private boolean wantHover;
    private int activePresses;
    private int hoverPresses;
    private long lastPress = Long.MIN_VALUE;
    private String unclear = "";

    JetpackSwitches(CreateJetpackCompat compat) {
        this.compat = Objects.requireNonNull(compat, "compat");
    }

    /** 要拨成这样：和上回要的不一样才重新计按了几次。 */
    void want(boolean active, boolean hover) {
        if (active == wantActive && hover == wantHover) return;
        wantActive = active;
        wantHover = hover;
        activePresses = 0;
        hoverPresses = 0;
        unclear = "";
    }

    // 每刻：读回一致就拨好了；不一致且离上次按过了几刻，就把差的那几个各按一下；按满三次还不对就说不清。
    State tick(LocalPlayer player, long now) {
        Optional<JetpackControls.Pack> pack = compat.pack(player);
        if (pack.isEmpty()) {
            unclear = "身上读不到喷气背包了";
            return State.UNCONFIRMED;
        }
        boolean activeRight = pack.get().active() == wantActive;
        boolean hoverRight = pack.get().hovering() == wantHover;
        if (activeRight && hoverRight) return State.DONE;
        if (lastPress != Long.MIN_VALUE && now - lastPress < SETTLE_TICKS) return State.WORKING;
        if ((!activeRight && activePresses >= MAX_PRESSES) || (!hoverRight && hoverPresses >= MAX_PRESSES)) {
            unclear = (!activeRight ? "飞行开关想" + (wantActive ? "开" : "关") + "，" : "")
                    + (!hoverRight ? "悬停开关想" + (wantHover ? "开" : "关") + "，" : "")
                    + "按了 " + MAX_PRESSES + " 次读回还不对";
            return State.UNCONFIRMED;
        }
        if (!activeRight) {
            compat.press(player, JetpackControls.Toggle.ACTIVE);
            activePresses++;
        }
        if (!hoverRight) {
            compat.press(player, JetpackControls.Toggle.HOVER);
            hoverPresses++;
        }
        lastPress = now;
        return State.WORKING;
    }

    /** 说不清时哪个开关怎么了。 */
    String unclear() {
        return unclear;
    }
}
