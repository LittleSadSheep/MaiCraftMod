// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat.sable;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * 在服务端读 Sable 物理结构的读写接缝：质量、重心、惯性、速度，最近一个物理子步里各类力（按秒折算，结构局部坐标）、
 * 这个世界的重力；能放配重的空格与放一块某方块有多重；局部坐标换到世界。只读：唯一会动的是打开"逐个记下各处的力"
 * 这个记账开关（不施力、不改结构，开了之后下一个子步才记得到）。只用 Minecraft 与 Java 的类型。
 */
public interface StructureForceReads {

    /**
     * 一类力（气球升力、推进、浮空……）。
     *
     * @param group  力的类别（Sable 的力组名，例如 sable:balloon_lift）
     * @param force  合力，结构局部坐标，按秒折算
     * @param torque 对重心的力矩，结构局部坐标，按秒折算
     * @param points 由几处力合成
     */
    record Push(String group, Vec3 force, Vec3 torque, int points) {}

    /**
     * 一个物理结构此刻的受力底子。
     *
     * @param mass     质量
     * @param center   重心（结构局部坐标）
     * @param inertia  惯性张量的对角三项（结构局部坐标）
     * @param gravity  重力加速度（结构局部坐标）
     * @param velocity 速度（世界坐标，每秒）
     * @param spin     角速度（世界坐标，弧度每秒）
     * @param tracking 各处的力在记着（刚打开记账时为假，下一刻再问）
     * @param pushes   各类力
     */
    record Forces(double mass, Vec3 center, Vec3 inertia, Vec3 gravity, Vec3 velocity, Vec3 spin, boolean tracking,
                List<Push> pushes) {
        public Forces {
            pushes = List.copyOf(pushes);
        }
    }

    /** 这个结构的受力底子；不在这个世界、身份写错为空。track 为真时没在记账就打开记账。 */
    Optional<Forces> forces(ServerLevel level, String id, boolean track);

    /** 结构里能放配重的空格：地块范围内的空气、下面一格不是空气，最多给这么多格（结构局部坐标）。 */
    List<BlockPos> freeCells(ServerLevel level, String id, int limit);

    /** 在结构里这一格放一块这种方块有多重；方块 ID 写错为 0。 */
    double blockMass(ServerLevel level, String id, BlockPos local, String blockId);

    /** 结构局部坐标换到此刻的世界坐标；结构不在为空。 */
    Optional<Vec3> toWorld(ServerLevel level, String id, Vec3 local);
}
