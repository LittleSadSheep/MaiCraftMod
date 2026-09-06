package org.maiwithu.maicraft.core.mixin;

import java.util.List;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.core.inventory.StockEvidence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A client block entity's default empty inventory is not server-synchronized stock evidence. */
@Mixin(AbstractContainerMenu.class)
public abstract class ContainerStockSyncMixin {
    @Inject(method = "initializeContents", at = @At("RETURN"))
    private void maicraft$stockContentReceived(int stateId, List<ItemStack> items,
                                             ItemStack carried, CallbackInfo callback) {
        StockEvidence.containerSynchronized((AbstractContainerMenu) (Object) this);
    }
}
