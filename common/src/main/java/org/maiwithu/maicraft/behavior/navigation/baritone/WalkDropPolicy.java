// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import baritone.api.pathing.movement.ActionCosts;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.game.player.FallDamage;

/**
 * 走路往下跳的取舍：摔完还剩一半以上的血就跳，不够就绕；再高才轮到水桶、干草这类落地办法。
 *
 * <p>三格以内原版不摔伤，身体再差也照走。会摔伤的一跳把扣的血折成路程代价加进去，每点血约四格平路：
 * 跳五格（扣一颗心）约等于多走九格，跳八格（扣两颗半心）约等于多走二十格，附近有台阶、斜坡能下去就走下去，
 * 绕得远了才跳，不会因为下落本身只要十几刻就宁可摔一下也不多走几步。
 * 每次算路按当下的身体（血、黄心、护甲附魔、药水）重算，卡坑判断按同一条线认"跳得下去"。纯计算，可离线测试。
 */
public final class WalkDropPolicy {

    /** 落差至少认到这么高：三格以内落地不扣血。 */
    public static final int MIN_DROP = 3;
    /** 摔掉一点血（半颗心）折成的路程代价（刻）：约四格平路。 */
    public static final double COST_PER_HEALTH = 4 * ActionCosts.WALK_ONE_BLOCK_COST;

    private WalkDropPolicy() {
    }

    /**
     * 这一跳摔伤折成的代价：不扣血为 0；摔完剩不到一半的血为不可走（交给落地办法或绕路）；
     * 其余按扣的血乘每点血的代价。
     */
    public static double injuryCost(FallDamage body, float maxHealth, double distance, FallDamage.Landing landing,
            boolean includeAccumulated) {
        float damage = body.damage(distance, landing, includeAccumulated);
        if (damage == 0) return 0;
        if (!body.keepsHalf(maxHealth, distance, landing, includeAccumulated)) return ActionCosts.COST_INF;
        return damage * COST_PER_HEALTH;
    }

    /** 按此刻的身体算卡坑判断认的往下跳的高度：和算路同一条线，查到卡坑判断的上下范围为止。 */
    public static int maxDrop(LocalPlayer self) {
        return maxDrop(FallDamage.capture(self), self.getMaxHealth(), TrapCheck.HEIGHT);
    }

    /** 只走不挖时往下跳最多几格：摔完还剩一半的血的最大落差，至少三格，最多 limit 格。 */
    public static int maxDrop(FallDamage body, float maxHealth, int limit) {
        if (!Float.isFinite(maxHealth) || maxHealth <= 0) return MIN_DROP;
        return Math.max(MIN_DROP, Math.min(limit, body.maxDropKeeping(maxHealth / 2, limit)));
    }
}
