// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 读机械动力（Create）动力方块的模组读写接缝：一格动力方块此刻转多快、在哪张应力网上、过载没有，
 * 这张网的应力容量与当前应力，以及方块哪几面接轴、每转吃多少应力。
 *
 * <p>Create 把一张应力网的容量、当前应力与成员数同步到每个成员的方块实体上，所以全在客户端读。
 * 只用 Minecraft 与 Java 的类型描述；直接看 Create 方块实体的读写端在 NeoForge 模块里，测试里用替身。
 */
public interface CreateKinetics {

    /** 这一格的动力读数：不是动力方块、格子没加载给空。只读客户端已经同步过来的方块实体。 */
    Optional<Reading> reading(ClientLevel level, BlockPos at);

    /** 这种方块是不是动力方块（会转、会把转动传给邻居）：Create 自己与它的附属模组的都算。 */
    boolean kinetic(BlockState state);

    /** 这种方块是不是自己产生转动的动力源（水车、风车轴承、蒸汽机、手摇曲柄……）。 */
    boolean generator(BlockState state);

    /**
     * 这一格朝哪几面伸着轴，能和那一面的邻居接上转动：轴沿轴向两头、齿轮箱四个侧面、压机朝上那一面……
     * 齿轮咬合不算"轴"，靠齿轮相邻传动的另算。读的是方块自己的说法，所以 level 只用来给方块问现场。
     */
    Set<Direction> shaftFaces(ClientLevel level, BlockPos at, BlockState state);

    /** 这种方块每转吃多少应力（Create 的应力影响，乘上转速才是实际应力）；不吃应力的为 0。 */
    double stressImpact(Block block);

    /** 这种动力源每转给多少应力容量（乘上转速才是实际容量）；不是动力源的为 0。 */
    double stressCapacity(Block block);

    /** 这种动力源自己转多快（转/分），审阅时估容量用；不是动力源、或转速看现场（风车看帆数）的为空。 */
    Optional<Integer> generatedSpeed(Block block);

    /** 这种机器至少要多快才干活（转/分）；没有门槛的为空。门槛来自 Create 的服务器配置（中速、高速）。 */
    Optional<Double> minimumSpeed(BlockState state);

    /**
     * 一格动力方块的读数。
     *
     * @param speed             此刻的实际转速（转/分），过载时 Create 按 0 算；正负是转向
     * @param theoreticalSpeed  不算过载时的转速：过载的机器靠它看出"本来该转多快"
     * @param networkId         所在应力网的编号；没接进任何网为空
     * @param overStressed      这张网过载了没有
     * @param stress            这张网此刻的应力（所有成员吃的应力之和）
     * @param capacity          这张网此刻的应力容量（所有动力源给的之和）
     * @param networkSize       这张网有几个成员
     * @param speedRequirementMet 转速够不够这台机器干活（没有门槛的机器总是够）
     */
    record Reading(double speed, double theoreticalSpeed, Optional<Long> networkId, boolean overStressed,
                   double stress, double capacity, int networkSize, boolean speedRequirementMet) {

        public Reading {
            networkId = networkId == null ? Optional.empty() : networkId;
        }

        /** 实际在转：接在网上、没过载、转速不为零；和 Create 自己画转动的条件一致。 */
        public boolean turning() {
            return networkId.isPresent() && !overStressed && Math.abs(speed) > 1.0e-4;
        }
    }
}
