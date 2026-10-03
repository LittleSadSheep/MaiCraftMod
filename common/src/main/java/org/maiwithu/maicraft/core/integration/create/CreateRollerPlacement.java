package org.maiwithu.maicraft.core.integration.create;

import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 只读复现压路机物品的抬高落点规则；Offroad 轮座共用此物品，不能只按普通方块上下文等待原格变化。 */
public final class CreateRollerPlacement {
    private CreateRollerPlacement() {}
    public static BlockPlaceContext context(BlockItem item,BlockPlaceContext original) {
        return NativeApi.is(item,"com.simibubi.create.content.contraptions.actors.roller.RollerBlockItem")?raisedContext(original):original;
    }
    static BlockPlaceContext raisedContext(BlockPlaceContext original) {
        var below=original.getClickedPos().below();var level=original.getLevel();
        // 原生先检查目标下方完整碰撞顶面，再重新构造上移一格的上下文；上下文仍自行决定可替换格，绝不写入世界。
        return Block.isFaceFull(level.getBlockState(below).getCollisionShape(level,below),Direction.UP)
                ?BlockPlaceContext.at(original,original.getClickedPos().above(),original.getClickedFace()):original;
    }
}
