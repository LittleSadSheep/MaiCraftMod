// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.createjetpack;

import java.util.Objects;

import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.game.player.NetworkDelay;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 喷气背包的快速下降：这个模组开着慢慢往下飘反而会摔伤（模组的毛病，所有者 09-07、09-26 原话），玩家的做法是
 * 到落点正上方关掉背包自由落，算好速度和网络延迟，快到地面前重新打开刹一下，确认真的慢下来了，
 * 离地不到两格半再关掉落下去（这点高度摔不伤）。下面是水、或本来就不高，直接关掉落下去。
 */
final class FastDrop {

    /** 落差不超过这么多格直接关掉落下去：原版摔落三格以内不掉血。 */
    static final double SAFE_DROP = 2.5;
    /** 刹完速度要回到这么慢（格每刻，往下为负）才算真的刹住。 */
    static final double SLOWED = -0.12;
    /** 刹车点多留的格数与刻数。 */
    static final double MARGIN_BLOCKS = 2.0;
    static final int MARGIN_TICKS = 4;

    /** 落到哪一步了。 */
    enum Stage { CUT, FALL, BRAKE, FINAL_CUT, DOWN }

    private final JetpackSwitches switches;
    private final double landingY;
    private Stage stage;
    private String unclear;

    /**
     * @param landingY 落点的高度（人站上去时脚所在那一格）
     * @param soft     下面是水，或落差本来就伤不了人：直接关掉落下去
     */
    FastDrop(JetpackSwitches switches, double landingY, boolean soft) {
        this.switches = Objects.requireNonNull(switches, "switches");
        this.landingY = landingY;
        this.stage = soft ? Stage.FINAL_CUT : Stage.CUT;
        if (soft) switches.want(false, true);
    }

    // 关 → 自由落 → 快到时重新打开刹住 → 离地不到两格半再关 → 落地；刹完还高就再关一次接着落。
    Stage tick(PlayerContext context, long now) {
        LocalPlayer player = context.localPlayer();
        if (player.onGround() || player.isInWater()) {
            stage = Stage.DOWN;
            return stage;
        }
        double height = player.getY() - landingY;
        double falling = Math.max(0, -player.getDeltaMovement().y);
        switch (stage) {
            case CUT -> {
                switches.want(false, true);
                if (settled(player, now)) stage = Stage.FALL;
            }
            case FALL -> {
                // 刹车点：照这个速度，网络延迟加几刻里还会掉多少，再多留两格。
                double brakeAt = falling * (latencyTicks(context) + MARGIN_TICKS) + MARGIN_BLOCKS;
                if (height <= brakeAt) {
                    switches.want(true, true);
                    stage = Stage.BRAKE;
                }
            }
            case BRAKE -> {
                if (settled(player, now) && player.getDeltaMovement().y > SLOWED) {
                    if (height <= SAFE_DROP) {
                        switches.want(false, true);
                        stage = Stage.FINAL_CUT;
                    } else {
                        stage = Stage.CUT;
                    }
                }
            }
            case FINAL_CUT -> settled(player, now);
            case DOWN -> { }
        }
        return stage;
    }

    private boolean settled(LocalPlayer player, long now) {
        JetpackSwitches.State state = switches.tick(player, now);
        if (state == JetpackSwitches.State.UNCONFIRMED) unclear = switches.unclear();
        return state == JetpackSwitches.State.DONE;
    }

    /** 开关拨不动时说的那句；拨得动为 null。 */
    String unclear() {
        return unclear;
    }

    // 到服务器一个来回要几刻；读不到按 4 刻算。
    private static int latencyTicks(PlayerContext context) {
        int ticks = NetworkDelay.roundTripTicks(context);
        return ticks < 0 ? 4 : Math.max(1, ticks);
    }
}
