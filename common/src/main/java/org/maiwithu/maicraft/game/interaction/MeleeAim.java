// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 近战该瞄的那一点，全仓只有这一份：它身上离眼睛最近的那一点（按它的移动稍往前提、往里收一点），不瞄中心——
 * 对着中心那条射线到身上要走得更远，矮的羊、站在三格边上的怪够得着却打不着，一直等不到出手
 * （实机 10-14 点名杀羊，站在 3.1 格外三十秒没挥）。出手时镜头对的是它；跳劈起跳前、往上升时镜头也先压在这里，
 * 往下落那几刻不用再转。
 */
public final class MeleeAim {

    private MeleeAim() {}

    /** 此刻该瞄它身上的哪一点。 */
    public static Vec3 point(LocalPlayer player, Entity target) {
        return insetClamp(player.getEyePosition(), leadBox(player, target));
    }

    /** 它的身体框按它的移动稍往前提：离得越远提得越多，上下最多提四分之三刻。 */
    static AABB leadBox(LocalPlayer player, Entity target) {
        Vec3 motion = target.getDeltaMovement();
        double leadTicks = Mth.clamp(player.distanceTo(target) * 0.35, 0.6, 1.5);
        return target.getBoundingBox().move(
                motion.x * leadTicks,
                motion.y * Math.min(leadTicks, 0.75),
                motion.z * leadTicks);
    }

    /** 把一点夹进往里收了一点的身体框里：贴着框边的那一点射线容易擦过去。 */
    static Vec3 insetClamp(Vec3 point, AABB box) {
        double insetX = Math.min(0.12, box.getXsize() * 0.18);
        double insetY = Math.min(0.16, box.getYsize() * 0.18);
        double insetZ = Math.min(0.12, box.getZsize() * 0.18);
        return new Vec3(
                Mth.clamp(point.x, box.minX + insetX, box.maxX - insetX),
                Mth.clamp(point.y, box.minY + insetY, box.maxY - insetY),
                Mth.clamp(point.z, box.minZ + insetZ, box.maxZ - insetZ));
    }
}
