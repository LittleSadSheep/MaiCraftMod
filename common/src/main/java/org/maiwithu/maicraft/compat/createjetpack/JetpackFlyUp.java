// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.createjetpack;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.behavior.navigation.spi.FlyUpGear;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 寻路时用喷气背包直接飞上高处，不再一块块垫圆石往上搭：穿着的喷气背包、背罐里的气够用才报能飞多高。
 * 往上飞时开着飞行和悬停——按着跳往上、松开就悬着不掉，挪到落脚处再往下落；落地后走路前那一关把它关上。
 * 只认穿在身上的：背包里的要换装备，那归"飞过去"那一段管。
 */
final class JetpackFlyUp implements FlyUpGear {

    /** 一次最多往上飞几格：再高的悬崖分几次飞，中途有落脚处歇一下。 */
    static final int MAX_BLOCKS = 24;
    /** 背罐里的气少于这么多秒就不飞：留着落地防护刹车用。 */
    static final double MIN_SECONDS = 10;
    /** 往上推比悬停费气，按悬停能撑的一半算。 */
    static final double THRUST_SHARE = 0.5;

    private final CreateJetpackCompat compat;

    JetpackFlyUp(CreateJetpackCompat compat) {
        this.compat = Objects.requireNonNull(compat, "compat");
    }

    @Override public Optional<Reach> reach(PlayerContext context) {
        LocalPlayer player = context.localPlayer();
        if (player == null) return Optional.empty();
        Optional<JetpackControls.Pack> pack = compat.pack(player);
        if (pack.isEmpty() || !pack.get().worn() || !pack.get().usable() || pack.get().verticalSpeed() <= 0) {
            return Optional.empty();
        }
        return reach(pack.get().secondsLeft(), pack.get().verticalSpeed());
    }

    /** 气还能撑几秒、往上飞多快，换成一次最多飞几格、每格几刻，纯函数。 */
    static Optional<Reach> reach(double secondsLeft, double verticalSpeed) {
        if (secondsLeft < MIN_SECONDS || verticalSpeed <= 0) return Optional.empty();
        int blocks = (int) Math.min(MAX_BLOCKS, (secondsLeft - MIN_SECONDS / 2) * THRUST_SHARE * 20 * verticalSpeed);
        if (blocks < 2) return Optional.empty();
        return Optional.of(new Reach(blocks, 1 / verticalSpeed));
    }

    @Override public boolean ready(PlayerContext context) {
        LocalPlayer player = context.localPlayer();
        if (player == null) return false;
        return compat.pack(player).map(pack -> pack.worn() && pack.active() && pack.hovering()).orElse(false);
    }

    // 开飞行、开悬停：按着跳往上，松开就悬着，挪到落脚处不会半路掉下去。
    @Override public Action prepare(PlayerContext context) {
        return new SwitchesTo(compat, true, true, "开喷气背包准备飞上去");
    }
}
