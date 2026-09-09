// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * 寻找 Mekanism 的可点击位置：机器面中心被管道遮住时也尝试周围八个点；管道自身则识别要配置的那段分支。
 */
final class MekanismInteractionGeometry {
    private MekanismInteractionGeometry() {}

    static Vec3 aimFrom(LocalPlayer player, BlockPos target, Direction face, String medium, Vec3 eye) {
        boolean transmitter = MekanismNativeConfiguration.isTransmitter(player.level(), target);
        boolean port = medium.equals("induction_port");
        List<Vec3> aims = new ArrayList<>();
        if (port) for (Direction side : Direction.values()) aims.addAll(faceSamples(target, side));
        else if (transmitter) aims.add(Vec3.atCenterOf(target).add(face.getStepX() * .4, face.getStepY() * .4, face.getStepZ() * .4));
        else aims.addAll(faceSamples(target, face));
        for (Vec3 aim : aims) {
            var hit = AssemblyInteractionGeometry.hit(player, eye, aim);
            if (hit == null || !hit.getBlockPos().equals(target)) continue;
            if (port || !transmitter && hit.getDirection() == face) return aim;
            // 当前只接受已经存在的管道分支，未采用 Mekanism 点击中央后按命中面配置的后备方式；关闭连接的一面可能因此无法重新开启。
            if (transmitter && selectedSegment(player, target, eye, aim) == face) return aim;
        }
        return null;
    }

    static List<Vec3> faceSamples(BlockPos target, Direction face) {
        List<Vec3> result = new ArrayList<>();
        double[] offsets = {0, -.32, .32};
        for (double a : offsets) for (double b : offsets) {
            Vec3 point = switch (face.getAxis()) {
                case X -> new Vec3(face.getStepX() * .499, a, b);
                case Y -> new Vec3(a, face.getStepY() * .499, b);
                case Z -> new Vec3(a, b, face.getStepZ() * .499);
            };
            result.add(Vec3.atCenterOf(target).add(point));
        }
        return result;
    }

    // 向 Mekanism 的多段碰撞盒发射视线，再把命中的小段对应回当前已连接的方向。读不到这些信息就放弃该瞄准点。
    private static Direction selectedSegment(LocalPlayer player, BlockPos target, Vec3 eye, Vec3 aim) {
        try {
            Object tile = player.level().getBlockEntity(target);
            Object boxes = MachineCommissioning.call(tile, "getCollisionBoxes");
            Vec3 end = eye.add(aim.subtract(eye).normalize().scale(Math.min(4.5, player.blockInteractionRange())));
            Object result = Class.forName("mekanism.common.util.MultipartUtils")
                    .getMethod("collisionRayTrace", BlockPos.class, Vec3.class, Vec3.class, Collection.class)
                    .invoke(null, target, eye, end, boxes);
            if (result == null || !(Boolean) MachineCommissioning.call(result, "valid")) return null;
            int index = result.getClass().getField("subHit").getInt(result) + 1;
            Object transmitter = MachineCommissioning.call(tile, "getTransmitter");
            int connections = ((Number) MachineCommissioning.call(transmitter, "getAllCurrentConnections")).intValue();
            List<Direction> connected = new ArrayList<>();
            for (Direction direction : Direction.values()) if ((connections & (1 << direction.ordinal())) != 0) connected.add(direction);
            return index >= 0 && index < connected.size() ? connected.get(index) : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return null; }
    }
}
