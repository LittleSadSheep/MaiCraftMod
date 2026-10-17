// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.create;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.compat.create.CreateKinetics;

/**
 * 读机械动力动力方块的读写端：方块实体给转速、网络编号与过载，应力网的容量、当前应力与成员数
 * 从方块实体同步给客户端的那份数据里读（Create 没有公开这三项的取值方法，但它们就写在同步包里）；
 * 方块自己回答哪几面接轴、要多快才干活，应力表回答每转吃多少、给多少。只翻译，不判断。
 */
public final class CreateKineticReads implements CreateKinetics {

    @Override public Optional<Reading> reading(ClientLevel level, BlockPos at) {
        if (!level.isLoaded(at) || !(level.getBlockEntity(at) instanceof KineticBlockEntity kinetic)) {
            return Optional.empty();
        }
        // 同步给客户端的数据里，接在网上的方块带一段 Network：编号、应力、容量、成员数，和服务端那张网一致。
        CompoundTag synced = kinetic.getUpdateTag(level.registryAccess());
        CompoundTag network = synced.getCompound("Network");
        Optional<Long> networkId = kinetic.hasNetwork() ? Optional.ofNullable(kinetic.network) : Optional.empty();
        return Optional.of(new Reading(kinetic.getSpeed(), kinetic.getTheoreticalSpeed(), networkId,
                kinetic.isOverStressed(), network.getFloat("Stress"), network.getFloat("Capacity"),
                network.getInt("Size"), kinetic.isSpeedRequirementFulfilled()));
    }

    @Override public boolean kinetic(BlockState state) {
        return state.getBlock() instanceof IRotate;
    }

    @Override public boolean generator(BlockState state) {
        return state.getBlock() instanceof IRotate && BlockStressValues.getCapacity(state.getBlock()) > 0;
    }

    @Override public Set<Direction> shaftFaces(ClientLevel level, BlockPos at, BlockState state) {
        if (!(state.getBlock() instanceof IRotate rotating)) return Set.of();
        Set<Direction> faces = EnumSet.noneOf(Direction.class);
        for (Direction side : Direction.values()) {
            if (rotating.hasShaftTowards(level, at, state, side)) faces.add(side);
        }
        return faces;
    }

    @Override public double stressImpact(Block block) {
        return BlockStressValues.getImpact(block);
    }

    @Override public double stressCapacity(Block block) {
        return BlockStressValues.getCapacity(block);
    }

    @Override public Optional<Integer> generatedSpeed(Block block) {
        BlockStressValues.GeneratedRpm rpm = BlockStressValues.RPM.get(block);
        return rpm == null ? Optional.empty() : Optional.of(rpm.value());
    }

    @Override public Optional<Double> minimumSpeed(BlockState state) {
        if (!(state.getBlock() instanceof IRotate rotating)) return Optional.empty();
        float needed = rotating.getMinimumRequiredSpeedLevel().getSpeedValue();
        return needed > 0 ? Optional.of((double) needed) : Optional.empty();
    }
}
