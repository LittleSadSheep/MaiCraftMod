// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing.spi;

import java.util.Optional;

import org.maiwithu.maicraft.behavior.landing.CatchesFall;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 不用放东西就能刹住的一掉：身上的喷气背包打开悬停这类。意外往下掉时先问它，刹得住就不放水；
 * 刹不住（没穿、没气）答空，再照原来的办法找落点放东西接住。
 */
public interface FallBrake {

    /** 此刻刹得住这一掉吗：刹得住给一份接住自己的做法，刹不住为空。 */
    Optional<CatchesFall> brake(PlayerContext context);
}
