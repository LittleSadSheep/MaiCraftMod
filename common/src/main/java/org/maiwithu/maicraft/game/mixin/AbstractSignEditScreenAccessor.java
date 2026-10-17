// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 告示牌编辑界面的读端：行缓冲、正反面与方块实体从界面里读出来。 */
@Mixin(AbstractSignEditScreen.class)
public interface AbstractSignEditScreenAccessor {

    @Accessor("messages")
    String[] maicraft$getMessages();

    @Accessor("isFrontText")
    boolean maicraft$getIsFrontText();

    @Accessor("sign")
    SignBlockEntity maicraft$getSign();
}
