package org.maiwithu.maicraft.core.mixin;

import java.util.List;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.core.inventory.StockEvidence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 原版菜单完成整份内容同步后，再通知库存观察。客户端箱子对象默认是空的，不能据此断言服务器箱子没东西。 */
@Mixin(AbstractContainerMenu.class)
public abstract class ContainerStockSyncMixin {
    @Inject(method = "initializeContents", at = @At("RETURN"))
    private void maicraft$stockContentReceived(int stateId, List<ItemStack> items,
                                             ItemStack carried, CallbackInfo callback) {
        StockEvidence.containerSynchronized((AbstractContainerMenu) (Object) this);
    }
}
