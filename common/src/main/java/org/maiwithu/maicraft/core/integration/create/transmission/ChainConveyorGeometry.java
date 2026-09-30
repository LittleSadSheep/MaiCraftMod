// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import net.minecraft.core.BlockPos;
import java.util.List;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;

/** 读取原生端点约束与显示几何；挂链不占据中间方块格，不能另加沿途净空门槛。 */
public final class ChainConveyorGeometry {
    public record Strand(Vec3 start, Vec3 end) {}
    private ChainConveyorGeometry() {}
    public static void validate(BlockPos first, BlockPos second, ChainConveyorBridge.Limits limits) {
        Vec3 delta = Vec3.atLowerCornerOf(second.subtract(first)); double horizontal = Math.hypot(delta.x, delta.z);
        if (delta.length() < 2.5) throw new IllegalArgumentException("chain_conveyor_too_close");
        if (delta.length() >= limits.maximumLength()) throw new IllegalArgumentException("chain_conveyor_too_long");
        if (horizontal <= 1.5 || Math.abs(delta.y) > horizontal - 1.5) throw new IllegalArgumentException("chain_conveyor_too_steep");
    }
    public static void clearEnvelope(Level world, BlockPos first, BlockPos second) {
        // 原生挂链只使用两端传动轮；沿途墙面、水流和轮缘旁的方块既不被修改，也不应提前阻止点击。
        for (BlockPos pulley : List.of(first, second)) {
            if (!world.isLoaded(pulley)) throw new IllegalArgumentException("chain_conveyor_endpoint_unloaded");
            if (NavigationSafetyContext.protectsUse(pulley)) throw new IllegalArgumentException("chain_conveyor_endpoint_protected");
        }
    }
    /** 复现原生两条 ConnectionStats 切线：半径 1.25 格，角度 ±35 度，高度 0.375 格。 */
    public static List<Strand> strands(BlockPos first, BlockPos second) {
        BlockPos delta = second.subtract(first); double theta = Math.atan2(delta.getX(), delta.getZ());
        Vec3 a = Vec3.atBottomCenterOf(first).add(0, .375, 0), b = Vec3.atBottomCenterOf(second).add(0, .375, 0);
        return List.of(strand(a, b, theta, 1), strand(a, b, theta, -1));
    }
    private static Strand strand(Vec3 first, Vec3 second, double theta, int sign) {
        double offset = Math.toRadians(35) * sign;
        return new Strand(first.add(new Vec3(0, 0, 1.25).yRot((float) (theta - offset))),
                second.add(new Vec3(0, 0, 1.25).yRot((float) (theta + Math.PI + offset))));
    }
}
