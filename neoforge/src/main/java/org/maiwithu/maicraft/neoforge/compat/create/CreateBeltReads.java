// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.create;

import java.util.Optional;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.simpleRelays.ShaftBlock;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.compat.create.CreateBelts;

/**
 * 读机械动力传送带的读写端：带的控制格、长度、序号在传送带方块实体的公开字段上，
 * 这一格是带头带尾还是带轮在方块状态的 part 上；手里传送带记着的第一根轴是物品上的一个数据组件；
 * 能不能连、最长几格问传送带物品自己的静态方法（读服务器配置）。只翻译，不判断。
 */
public final class CreateBeltReads implements CreateBelts {

    @Override public Optional<Segment> segment(ClientLevel level, BlockPos at) {
        if (!level.isLoaded(at) || !(level.getBlockEntity(at) instanceof BeltBlockEntity belt)) {
            return Optional.empty();
        }
        BlockState state = level.getBlockState(at);
        String part = state.hasProperty(BeltBlock.PART) ? state.getValue(BeltBlock.PART).getSerializedName() : "";
        BlockPos controller = belt.getController();
        if (controller == null) return Optional.empty();
        return Optional.of(new Segment(controller, belt.beltLength, belt.index, part));
    }

    @Override public Optional<BlockPos> firstShaft(ItemStack held) {
        if (held.isEmpty() || !(held.getItem() instanceof BeltConnectorItem)) return Optional.empty();
        return Optional.ofNullable(held.get(AllDataComponents.BELT_FIRST_SHAFT));
    }

    @Override public boolean canConnect(ClientLevel level, BlockPos first, BlockPos second) {
        return BeltConnectorItem.canConnect(level, first, second);
    }

    @Override public int maxLength() {
        return BeltConnectorItem.maxLength();
    }

    @Override public boolean shaft(ClientLevel level, BlockPos at) {
        return level.isLoaded(at) && ShaftBlock.isShaft(level.getBlockState(at));
    }
}
