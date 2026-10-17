// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.game.mixin.AbstractArrowAccessor;

/**
 * 自己射出去、插在地上墙上的箭：像玩家一样打完走过去捡回来。只认自己射的、原版允许捡的（创造模式射的、
 * 怪射的不算）、已经插住不动的，而且离脚下站得住的地方不超过两格多——插在高墙上的人也够不着，不去白跑。
 * 实机 10-14 一晚上自卫射掉二十多支箭，一支没捡，箭从 32 支用到 4 支。
 */
public final class ArrowPickups {

    /** 箭离脚下地面最多这么高还碰得到：玩家拾取范围比身体上下各宽半格。 */
    private static final double TOUCH_HEIGHT = 2.2;

    private ArrowPickups() {
    }

    /** 这支箭能不能走过去捡回来。 */
    public static boolean retrievable(Entity entity, LocalPlayer player) {
        if (!(entity instanceof AbstractArrow arrow) || player == null) return false;
        if (arrow.pickup != AbstractArrow.Pickup.ALLOWED || arrow.getOwner() != player) return false;
        if (!((AbstractArrowAccessor) arrow).maicraft$inGround()) return false;
        // 往下找脚下站得住的那一格：箭插在地上、矮墙上都在两格多以内；悬在高墙上的够不着。
        for (int depth = 0; depth <= 3; depth++) {
            BlockPos below = BlockPos.containing(arrow.getX(), arrow.getY() - depth, arrow.getZ());
            BlockState state = arrow.level().getBlockState(below);
            if (!state.getCollisionShape(arrow.level(), below).isEmpty()) {
                return arrow.getY() - (below.getY() + 1) <= TOUCH_HEIGHT;
            }
        }
        return false;
    }

    /** 捡起来是哪件东西：插着的箭是哪种箭；不是箭为空。 */
    public static ItemStack item(Entity entity) {
        return entity instanceof AbstractArrow arrow ? arrow.getPickupItemStackOrigin() : ItemStack.EMPTY;
    }
}
