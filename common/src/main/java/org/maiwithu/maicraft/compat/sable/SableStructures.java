// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.List;
import java.util.Objects;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Sable 物理结构的读写接缝（客户端）：飞艇、船、车这类会动的方块结构在 Sable 里是一个子世界，方块存在远处的地块里，
 * 按当刻的位姿换到世界里。这里读附近有哪些结构、各自的位姿与范围、角色是不是正站在或坐在上面，以及地块里某一格是什么方块。
 * 只翻译，不判断。
 */
public interface SableStructures {

    /**
     * 位姿：地块（结构局部）坐标到世界坐标的换算——世界 = 朝向 × ((局部 − 转动点) × 缩放) + 位置。
     *
     * @param position      位置
     * @param orientation   朝向（四元数 x、y、z、w）
     * @param rotationPoint 转动点（局部坐标）
     * @param scale         缩放
     */
    record Pose(Vec3 position, double[] orientation, Vec3 rotationPoint, Vec3 scale) {

        public Pose {
            Objects.requireNonNull(position, "position");
            orientation = orientation.clone();
            Objects.requireNonNull(rotationPoint, "rotationPoint");
            Objects.requireNonNull(scale, "scale");
        }

        @Override public double[] orientation() {
            return orientation.clone();
        }

        /** 局部坐标换到世界。 */
        public Vec3 toWorld(Vec3 local) {
            Vector3d point = new Vector3d(local.x - rotationPoint.x, local.y - rotationPoint.y, local.z - rotationPoint.z)
                    .mul(scale.x, scale.y, scale.z);
            quaternion().transform(point);
            return new Vec3(point.x + position.x, point.y + position.y, point.z + position.z);
        }

        /** 世界坐标换到局部。 */
        public Vec3 toLocal(Vec3 world) {
            Vector3d point = new Vector3d(world.x - position.x, world.y - position.y, world.z - position.z);
            quaternion().transformInverse(point);
            return new Vec3(point.x / scale.x + rotationPoint.x, point.y / scale.y + rotationPoint.y,
                    point.z / scale.z + rotationPoint.z);
        }

        private Quaterniond quaternion() {
            return new Quaterniond(orientation[0], orientation[1], orientation[2], orientation[3]);
        }
    }

    /**
     * 一个物理结构此刻的样子。
     *
     * @param id            结构的身份（Sable 子世界编号）
     * @param name          名字；没起名为空串
     * @param worldBounds   在世界里的范围
     * @param pose          这一刻的位姿
     * @param lastPose      上一刻的位姿（和这一刻比就知道在怎么动）
     * @param localMin      地块里方块范围的最小角（含）
     * @param localMax      地块里方块范围的最大角（含）
     * @param carriesPlayer 角色正站在或坐在它上面
     */
    record Structure(String id, String name, AABB worldBounds, Pose pose, Pose lastPose, BlockPos localMin,
                     BlockPos localMax, boolean carriesPlayer) {}

    /** 和这个区域沾边的物理结构。 */
    List<Structure> near(ClientLevel level, Player viewer, AABB region);

    /** 结构地块里一格的方块；那一块没加载、结构不在了为 null。 */
    BlockState block(ClientLevel level, String id, BlockPos local);

    /** 结构地块里的实体（装在船上的螺旋桨、风帆这类装置）此刻在世界里的范围。 */
    List<AABB> attachedEntities(ClientLevel level, String id);
}
