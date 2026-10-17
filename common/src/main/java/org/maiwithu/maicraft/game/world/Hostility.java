// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;

/**
 * 这只活物此刻会不会主动打人：玩家看一眼就知道的那种。
 * 敌对生物见人就打；中立的（僵尸猪灵、末影人，以及模组里同样按中立写的）没惹它就不打人，
 * 惹毛了追着人打时原版会把"在进攻"同步过来。实机 10-14 下界自卫追着路过的僵尸猪灵打，招来一群被打死。
 */
public final class Hostility {

    private Hostility() {
    }

    /** 此刻会主动打人：敌对生物；中立的只在惹毛了、正在进攻时算。 */
    public static boolean hostileNow(Entity entity) {
        if (!(entity instanceof Enemy)) return false;
        if (entity instanceof NeutralMob && entity instanceof Mob mob) return mob.isAggressive();
        return true;
    }

    /** 中立生物：没惹它不打人，打了一只附近同类会一起上（僵尸猪灵）。 */
    public static boolean neutral(Entity entity) {
        return entity instanceof NeutralMob;
    }
}
