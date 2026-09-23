// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.mixin;

import java.util.List;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 只读访问菜单原版同步整数数据通道。 */
// 暴露菜单同步的整数数据列表，供炉子读取燃烧时间和加工进度；具体每个下标的含义仍由对应菜单定义。
@Mixin(AbstractContainerMenu.class)
public interface MenuDataSlotsAccessor {
    @Accessor("dataSlots")
    List<DataSlot> maicraft$dataSlots();
}
