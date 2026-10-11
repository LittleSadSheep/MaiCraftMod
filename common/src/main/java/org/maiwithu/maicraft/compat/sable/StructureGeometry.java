// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * 物理结构的几何，纯函数：地块里的方块按位姿换到世界里——逐格碰撞、甲板上能站的格、在怎么动。
 * 读一格方块交进来（地块没加载的为 null）；读格有上限，超了就说没读全，调用方按整体范围处理。
 */
final class StructureGeometry {

    /** 一个结构最多逐格读这么多格；再多就按整体范围。 */
    static final int MAX_CELLS = 32_768;
    /** 每刻挪不到这么远（格）、转不到这么多（弧度）当它停着。 */
    static final double STILL_BLOCKS = 1e-3;
    static final double STILL_RADIANS = 1e-3;
    /** Simulated 的物理组装器。 */
    static final String ASSEMBLER = "simulated:physics_assembler";
    /** Simulated 的无线红石打字机：接管后按键就往绑的频率发信号。 */
    static final String TYPEWRITER = "simulated:linked_typewriter";

    private StructureGeometry() {}

    /**
     * 一次逐格读的结果（地块坐标，和位姿无关，结构不改方块就一直对）：读全没有、碰撞盒、方块数、座位、认得的部件。
     *
     * @param complete 读全了：格数没超、没有没加载的格
     * @param boxes    逐格碰撞盒（地块坐标）
     * @param blocks   不是空气的方块数
     * @param seats    座位所在的格
     * @param parts    认得的部件（控制件、收端、执行件、浮力源）：所在的格与方块 ID
     * @param assemblers 物理组装器所在的格（拆回时拉它）
     */
    record Scan(boolean complete, List<AABB> boxes, int blocks, List<BlockPos> seats, List<Part> parts,
                List<BlockPos> assemblers) {

        /** 控制件（无线红石打字机、舵轮、油门拉杆）。 */
        List<Part> controls() {
            return parts.stream().filter(part -> VehicleParts.CONTROLS.contains(part.id())).toList();
        }

        /** 某一种部件所在的格。 */
        List<BlockPos> partsOf(String id) {
            return parts.stream().filter(part -> part.id().equals(id)).map(Part::local).toList();
        }
    }

    /** 结构上的一个部件：地块里的格与方块 ID。 */
    record Part(BlockPos local, String id) {}

    /** 地块里逐格读一遍：每格自己的碰撞形状、座位、认得的部件；格数超了或有没加载的格就是没读全。 */
    static Scan scan(SableStructures.Structure structure, Function<BlockPos, BlockState> read) {
        List<AABB> boxes = new ArrayList<>();
        List<BlockPos> seats = new ArrayList<>();
        List<Part> parts = new ArrayList<>();
        List<BlockPos> assemblers = new ArrayList<>();
        if (cells(structure) > MAX_CELLS) return new Scan(false, boxes, 0, seats, parts, assemblers);
        boolean complete = true;
        int blocks = 0;
        for (BlockPos local : BlockPos.betweenClosed(structure.localMin(), structure.localMax())) {
            BlockState state = read.apply(local);
            if (state == null) {
                complete = false;
                continue;
            }
            if (state.isAir()) continue;
            blocks++;
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (id.endsWith("_seat")) seats.add(local.immutable());
            if (VehicleParts.known(id)) parts.add(new Part(local.immutable(), id));
            if (ASSEMBLER.equals(id)) assemblers.add(local.immutable());
            VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, local);
            for (AABB part : shape.toAabbs()) boxes.add(part.move(local));
        }
        return new Scan(complete, boxes, blocks, seats, parts, assemblers);
    }

    /**
     * 甲板上能站人的格：脚下是有上表面的方块，脚和头两格都没有碰撞；给的是此刻人站上去时脚底中心在世界里的位置，
     * 和对应的地块格。
     */
    static List<Deck> deck(SableStructures.Structure structure, Function<BlockPos, BlockState> read) {
        List<Deck> cells = new ArrayList<>();
        if (cells(structure) > MAX_CELLS) return cells;
        BlockPos min = structure.localMin();
        BlockPos max = structure.localMax().above(2);
        for (BlockPos local : BlockPos.betweenClosed(min, max)) {
            BlockState floor = read.apply(local.below());
            BlockState feet = read.apply(local);
            BlockState head = read.apply(local.above());
            if (floor == null || feet == null || head == null) continue;
            if (!solidTop(floor, local.below()) || blocks(feet, local) || blocks(head, local.above())) continue;
            Vec3 standing = structure.pose().toWorld(new Vec3(local.getX() + 0.5, local.getY(), local.getZ() + 0.5));
            cells.add(new Deck(local.immutable(), standing));
        }
        return cells;
    }

    /** 甲板上的一格：地块里的格，和此刻脚底中心在世界里的位置。 */
    record Deck(BlockPos local, Vec3 world) {}

    /** 这一刻比上一刻挪了多少（格每刻）。 */
    static Vec3 velocity(SableStructures.Structure structure) {
        return structure.pose().position().subtract(structure.lastPose().position());
    }

    /** 这一刻比上一刻转了多少（弧度）。 */
    static double turned(SableStructures.Structure structure) {
        double[] a = structure.pose().orientation();
        double[] b = structure.lastPose().orientation();
        double dot = Math.abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]);
        return 2 * Math.acos(Math.min(1, dot));
    }

    /**
     * 两个位姿之间的变化：世界里挪了多少；换到开始那一刻结构自己的轴上挪了多少；绕结构自己的 x、y、z 轴各转了多少度。
     * 结构自己的轴就是组装那一刻的世界轴（组装不转方块），所以"沿 +z"就是造的时候朝南的那头。
     */
    record Change(Vec3 moved, Vec3 alongOwnAxes, Vec3 turnDegrees) {}

    /** 从 from 到 to 的变化。 */
    static Change change(SableStructures.Pose from, SableStructures.Pose to) {
        Vec3 moved = to.position().subtract(from.position());
        Quaterniond start = quaternion(from);
        Vector3d own = start.transformInverse(new Vector3d(moved.x, moved.y, moved.z));
        // 相对转动 = 开始的逆 × 现在：转轴落在开始那一刻结构自己的轴上；取短的那一圈。
        Quaterniond relative = new Quaterniond(start).conjugate().mul(quaternion(to));
        double sign = relative.w < 0 ? -1 : 1;
        double x = relative.x * sign, y = relative.y * sign, z = relative.z * sign, w = relative.w * sign;
        double sin = Math.sqrt(x * x + y * y + z * z);
        Vec3 turn = Vec3.ZERO;
        if (sin > 1e-9) {
            double degrees = Math.toDegrees(2 * Math.atan2(sin, w));
            turn = new Vec3(x / sin * degrees, y / sin * degrees, z / sin * degrees);
        }
        return new Change(moved, new Vec3(own.x, own.y, own.z), turn);
    }

    private static Quaterniond quaternion(SableStructures.Pose pose) {
        double[] q = pose.orientation();
        return new Quaterniond(q[0], q[1], q[2], q[3]);
    }

    /** 在动（挪或转）。 */
    static boolean moving(SableStructures.Structure structure) {
        return velocity(structure).length() > STILL_BLOCKS || turned(structure) > STILL_RADIANS;
    }

    /** 局部的一个盒子换到世界：八个角都换过去取包围盒。 */
    static AABB toWorld(SableStructures.Pose pose, AABB local) {
        Vec3 first = pose.toWorld(new Vec3(local.minX, local.minY, local.minZ));
        double minX = first.x, minY = first.y, minZ = first.z, maxX = first.x, maxY = first.y, maxZ = first.z;
        for (int bits = 1; bits < 8; bits++) {
            Vec3 corner = pose.toWorld(new Vec3((bits & 1) == 0 ? local.minX : local.maxX,
                    (bits & 2) == 0 ? local.minY : local.maxY, (bits & 4) == 0 ? local.minZ : local.maxZ));
            minX = Math.min(minX, corner.x);
            minY = Math.min(minY, corner.y);
            minZ = Math.min(minZ, corner.z);
            maxX = Math.max(maxX, corner.x);
            maxY = Math.max(maxY, corner.y);
            maxZ = Math.max(maxZ, corner.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static long cells(SableStructures.Structure structure) {
        BlockPos min = structure.localMin();
        BlockPos max = structure.localMax();
        return (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
    }

    private static boolean solidTop(BlockState state, BlockPos at) {
        VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, at);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) >= 1.0;
    }

    private static boolean blocks(BlockState state, BlockPos at) {
        return !state.getCollisionShape(EmptyBlockGetter.INSTANCE, at).isEmpty();
    }
}
