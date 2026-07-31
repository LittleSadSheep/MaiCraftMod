// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.mixin;

import java.util.List;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to the menu's vanilla synchronized integer data channel. */
@Mixin(AbstractContainerMenu.class)
public interface MenuDataSlotsAccessor {
    @Accessor("dataSlots")
    List<DataSlot> maicraft$dataSlots();
}
