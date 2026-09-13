// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

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
        else if (transmitter) {
            try {
                Object tile = player.level().getBlockEntity(target);
                var boxes = collisionBoxes(tile);
                int connections = ((Number) MachineCommissioning.call(MachineCommissioning.call(tile, "getTransmitter"), "getAllCurrentConnections")).intValue();
                aims.addAll(transmitterSamples(target, face, eye, boxes, connections));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return null; }
        }
        else aims.addAll(faceSamples(target, face));
        for (Vec3 aim : aims) {
            var hit = AssemblyInteractionGeometry.hit(player, eye, aim);
            if (hit == null || !hit.getBlockPos().equals(target)) continue;
            if (port || !transmitter && hit.getDirection() == face) return aim;
            if (transmitter && selectedSegment(player, target, eye, aim, hit.getDirection()) == face) return aim;
        }
        return null;
    }

    /** The native list contains connected arm shapes in Direction order, followed by the center. */
    static List<Vec3> transmitterSamples(BlockPos target, Direction desired, Vec3 eye, List<VoxelShape> boxes, int connections) {
        List<Direction> sides = connectedSides(connections);
        if (boxes.size() != sides.size() + 1) return List.of();
        List<Vec3> samples = new ArrayList<>();
        int selected = sides.indexOf(desired);
        if (selected >= 0) surfaceSamples(target, eye, boxes.get(selected), samples);
        // An absent/disabled arm is configured by hitting its side of the actual center shape.
        surfaceSamples(target, eye, boxes.getLast(), samples);
        return samples;
    }

    private static void surfaceSamples(BlockPos target, Vec3 eye, VoxelShape shape, List<Vec3> samples) {
        for (AABB local : shape.toAabbs()) {
            if (samples.size() >= 216) return;
            AABB box = local.move(target); samples.add(box.getCenter());
            for (Direction side : Direction.values()) {
                double eyeAxis = side.getAxis().choose(eye.x, eye.y, eye.z);
                double edge = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? box.max(side.getAxis()) : box.min(side.getAxis());
                if ((eyeAxis - edge) * side.getAxisDirection().getStep() <= 0) continue;
                for (double a : new double[]{.15, .5, .85}) for (double b : new double[]{.15, .5, .85}) {
                    double inset = edge - side.getAxisDirection().getStep() * .0001;
                    samples.add(switch (side.getAxis()) {
                        case X -> new Vec3(inset, between(box.minY, box.maxY, a), between(box.minZ, box.maxZ, b));
                        case Y -> new Vec3(between(box.minX, box.maxX, a), inset, between(box.minZ, box.maxZ, b));
                        case Z -> new Vec3(between(box.minX, box.maxX, a), between(box.minY, box.maxY, b), inset);
                    });
                }
            }
        }
    }

    private static double between(double minimum, double maximum, double fraction) { return minimum + (maximum - minimum) * fraction; }
    private static List<Direction> connectedSides(int connections) {
        List<Direction> result = new ArrayList<>();
        for (Direction side : Direction.values()) if ((connections & (1 << side.ordinal())) != 0) result.add(side);
        return result;
    }
    static Direction selectedFace(int subHit, int connections, Direction hitFace) {
        List<Direction> sides = connectedSides(connections);
        int index = subHit + 1;
        return index < 0 || index > sides.size() ? null : index == sides.size() ? hitFace : sides.get(index);
    }
    private static List<VoxelShape> collisionBoxes(Object tile) throws ReflectiveOperationException {
        List<?> boxes = (List<?>) MachineCommissioning.call(tile, "getCollisionBoxes");
        return boxes.stream().map(VoxelShape.class::cast).toList();
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
    private static Direction selectedSegment(LocalPlayer player, BlockPos target, Vec3 eye, Vec3 aim, Direction hitFace) {
        try {
            Object tile = player.level().getBlockEntity(target);
            var boxes = collisionBoxes(tile);
            Vec3 end = eye.add(aim.subtract(eye).normalize().scale(Math.min(4.5, player.blockInteractionRange())));
            Object result = Class.forName("mekanism.common.util.MultipartUtils")
                    .getMethod("collisionRayTrace", BlockPos.class, Vec3.class, Vec3.class, Collection.class)
                    .invoke(null, target, eye, end, boxes);
            if (result == null || !(Boolean) MachineCommissioning.call(result, "valid")) return null;
            int subHit = result.getClass().getField("subHit").getInt(result);
            Object transmitter = MachineCommissioning.call(tile, "getTransmitter");
            int connections = ((Number) MachineCommissioning.call(transmitter, "getAllCurrentConnections")).intValue();
            if (boxes.size() != connectedSides(connections).size() + 1) return null;
            return selectedFace(subHit, connections, hitFace);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return null; }
    }
}
