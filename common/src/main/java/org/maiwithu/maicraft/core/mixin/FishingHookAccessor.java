package org.maiwithu.maicraft.core.mixin;

import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to vanilla's private successful-bite countdown. */
// 让代码能读到鱼钩的 nibble 私有字段；这个访问器不会把服务端数据同步到客户端。
// nibble 由服务端计时，客户端咬钩应看同步的 DATA_BITING／biting，当前调用误用见 A62。
@Mixin(FishingHook.class)
public interface FishingHookAccessor {

    @Accessor("nibble")
    int maicraft$getNibble();
}
