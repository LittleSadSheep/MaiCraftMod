package org.maiwithu.maicraft.core.mixin;

import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 读取原版收到 DATA_BITING 后更新的客户端咬钩状态；不依赖服务端专用的 nibble 计时器。 */
@Mixin(FishingHook.class)
public interface FishingHookAccessor {

    @Accessor("biting")
    boolean maicraft$isBiting();
}
