package org.maiwithu.maicraft.core.combat;

import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;

/** 看距离选远近武器 -> 等充能时拉开 -> 短暂接敌瞄准出手；生命不足仍由上层优先撤离。 */
public final class PvpTactics {
    public record Band(double inner, double outer) {}
    private PvpTactics() {}

    public static boolean ranged(double distance, boolean melee, boolean ranged, boolean alreadyRanged) {
        // 远处用弓，贴近再换近战；进入和退出阈值不同，避免对手在六格左右时反复换手。
        return ranged && (!melee || distance >= (alreadyRanged ? 4.5 : 7.0));
    }

    public static boolean attackReady(LocalPlayer self, Player other) {
        // 使用当前武器的真实攻击充能，并等待对手受击保护结束，不能按固定点击频率乱挥。
        return Swing.mayStrike(false, other.hurtTime > 0, self.getAttackStrengthScale(0.0F));
    }

    public static Band band(LocalPlayer self, Player other, boolean ranged) {
        double reach = Math.max(0, self.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE)) + other.getBbWidth() / 2.0;
        double danger = Menace.strikeRangeOf(other, self);
        if (ranged) {
            // 对手迎面逼近越快，拉弓前留的空间越多；仍保留可命中的中距离，不追求盲目最远射程。
            Vec3 towardSelf = self.position().subtract(other.position()).normalize();
            double closing = Mth.clamp(other.getDeltaMovement().dot(towardSelf), 0, .6);
            double inner = Mth.clamp(danger + closing * 20 + 1, 6, 10);
            return new Band(inner, inner + 4);
        }
        if (attackReady(self, other)) {
            // 双方射程可能相同；只在可出手时短暂进入互相能打到的范围，不能寻找不存在的绝对安全近战环。
            return new Band(Math.max(.25, reach - 1), Math.max(.5, reach - .15));
        }
        double inner = Math.max(danger + .35, reach + .25);
        return new Band(inner, inner + .85);
    }

    public static NavGoal stance(LocalPlayer self, Player other, boolean ranged, List<LivingEntity> threats) {
        // 目标玩家的避让内沿随出手窗口变化，其他敌人仍保持正常危险距离；这样冷却和移动不会互相否决。
        Band band = band(self, other, ranged);
        var avoidance = threats.stream().flatMap(entity -> Menace.field(self, List.of(entity)).stream()
                .map(threat -> entity == other ? threat.withClearance(band.inner()) : threat)).toList();
        NavGoal approach = NavGoal.distanceBand(other.position(), band.inner(), band.outer());
        return NavGoal.approachAvoiding(approach, Menace.AVOID_PENALTY, avoidance);
    }

    public static Vec3 aimPoint(Player other) {
        // 玩家横移更快，瞄准跟随短时速度，但始终夹在当前真实碰撞箱内；最终仍须准星射线命中本人。
        AABB box = other.getBoundingBox();
        Vec3 aim = box.getCenter().add(other.getDeltaMovement().scale(.8));
        double x = Math.min(.1, box.getXsize() / 4), y = Math.min(.12, box.getYsize() / 4), z = Math.min(.1, box.getZsize() / 4);
        return new Vec3(Mth.clamp(aim.x, box.minX + x, box.maxX - x),
                Mth.clamp(aim.y, box.minY + y, box.maxY - y), Mth.clamp(aim.z, box.minZ + z, box.maxZ - z));
    }
}
