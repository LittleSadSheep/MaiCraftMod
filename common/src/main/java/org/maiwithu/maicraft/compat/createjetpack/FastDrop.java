// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.createjetpack;

import java.util.Objects;

import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.game.player.NetworkDelay;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 喷气背包的快速下降：这个模组开着慢慢往下飘反而会摔伤，还要飘很久（模组的毛病，所有者 09-07、09-26、10-12 原话），
 * 玩家的做法是到落点正上方关掉背包自由落，到了摔不伤的高度瞬间打开一下（服务端重新算摔落高度、悬停把下落压住），
 * 紧接着关掉落到地上。打开的时机按下落速度和来回延迟算：读回打开时正好离地一格左右；
 * 读回之前就落了地也不伤（服务端已经清零），停得比两格半还高就关掉再落一次、再开一下，不悬在空中等它慢慢飘。
 * 下面是水、或本来就不高，直接关掉落下去。
 */
final class FastDrop {

    /** 落差不超过这么多格直接关掉落下去：原版摔落三格以内不掉血。 */
    static final double SAFE_DROP = 2.5;
    /** 刹完速度要回到这么慢（格每刻，往下为负）才算真的刹住；挨打落地前刹车用。 */
    static final double SLOWED = -0.12;
    /** 打开那一下读回生效时，想让人停在离地多高（格）：低一点没关系，带着开着落地也不伤。 */
    static final double OPEN_AT = 1.0;

    /** 落到哪一步了：关掉、自由落、瞬间打开、关掉落地、落地了。 */
    enum Stage { CUT, FALL, OPEN, FINAL_CUT, DOWN }

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

    // 关 → 自由落 → 快到地面瞬间打开 → 读回开了马上关 → 落地；打开后停得还高就再关一次接着落。
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
                // 打开的时机：按下去到读回生效要一个来回，这期间照这个速度还会掉多少，读回时正好离地一格左右。
                double openAt = Math.max(SAFE_DROP, falling * (latencyTicks(context) + 1) + OPEN_AT);
                if (height <= openAt) {
                    switches.want(true, true);
                    stage = Stage.OPEN;
                }
            }
            case OPEN -> {
                // 读回开了（悬停已把下落压住、服务端清了摔落高度）就马上关：剩下不到两格半直接落地，
                // 停得还高就关掉再落一次，下次按慢下来的速度再开，不开着慢慢飘。
                if (settled(player, now)) {
                    switches.want(false, true);
                    stage = height <= SAFE_DROP ? Stage.FINAL_CUT : Stage.CUT;
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
