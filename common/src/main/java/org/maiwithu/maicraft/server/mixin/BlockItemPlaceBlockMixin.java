// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.server.BlockOwnershipRecord;
import org.maiwithu.maicraft.server.ServerConfirmations;
import org.maiwithu.maicraft.server.ServerLinkServices;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 服务端记录方块归属与放置确认的钩子：原版在方块物品放置成功后调用 placeBlock，
 * 这里在它成功返回时把"谁、在哪、放了什么"交给服务端登记，并推送给该玩家的 MaiCraft 客户端。
 * 客户端侧的预测也会走进这个方法，只在服务端一侧（非客户端世界）记录。
 */
@Mixin(BlockItem.class)
public abstract class BlockItemPlaceBlockMixin {

    @Inject(method = "placeBlock", at = @At("RETURN"))
    private void maicraft$recordPlacement(BlockPlaceContext context, BlockState state,
                                          CallbackInfoReturnable<Boolean> cir) {
        // 只在放置真正成功时记录；失败的放置什么都没改变，不能当作归属。
        if (!cir.getReturnValueZ() || context.getPlayer() == null) return;
        ServerPlayer player = (ServerPlayer) context.getPlayer();
        if (player.level().isClientSide()) return;
        MinecraftServer server = player.serverLevel().getServer();
        BlockOwnershipRecord ownership = ServerLinkServices.ownership(server);
        if (ownership == null) return;
        ownership.recordPlacement(player.serverLevel().dimension().location().toString(),
                context.getClickedPos(), player.getUUID(),
                Integer.toUnsignedLong(server.getTickCount()));
        ServerConfirmations confirmations = ServerLinkServices.confirmations(server);
        if (confirmations != null) confirmations.placed(player, context.getClickedPos(), state);
    }
}
