// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.create;

import java.util.Optional;
import java.util.Set;

import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.compat.create.CreateChainConveyors;

/**
 * 读机械动力链条输送机的读写端：已连着的那几台在方块实体的公开字段 connections 上（相对位置，客户端同步过来的），
 * 一条链要几根锁链按方块实体自己的静态算法，最长与最多几条读 Create 的服务器配置。只翻译，不判断。
 */
public final class CreateChainConveyorReads implements CreateChainConveyors {

    @Override public Optional<Set<BlockPos>> connections(ClientLevel level, BlockPos at) {
        if (!level.isLoaded(at) || !(level.getBlockEntity(at) instanceof ChainConveyorBlockEntity conveyor)) {
            return Optional.empty();
        }
        return Optional.of(Set.copyOf(conveyor.connections));
    }

    @Override public int chainCost(BlockPos delta) {
        return ChainConveyorBlockEntity.getChainCost(delta);
    }

    @Override public int maxLength() {
        return AllConfigs.server().kinetics.maxChainConveyorLength.get();
    }

    @Override public int maxConnections() {
        return AllConfigs.server().kinetics.maxChainConveyorConnections.get();
    }
}
