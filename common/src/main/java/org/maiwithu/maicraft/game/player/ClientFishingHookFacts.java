// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.projectile.FishingHook;

import org.maiwithu.maicraft.game.mixin.FishingHookAccessor;

/**
 * 鱼钩事实的生产读端：每刻从角色上下文读角色关联的那只浮标。
 *
 * <p>咬钩状态在原版 {@code FishingHook} 上没有公开读取方法，经访问器 Mixin 读客户端
 * 收到 DATA_BITING 后更新的同步字段；这里的几个读数都只读现场，不发任何请求。
 */
public final class ClientFishingHookFacts implements FishingHookFacts {

    private final Supplier<PlayerContext> contexts;

    public ClientFishingHookFacts(Supplier<PlayerContext> contexts) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    @Override public boolean hasHook() {
        return hook() != null;
    }

    @Override public boolean isBiting() {
        FishingHook hook = hook();
        return hook != null && ((FishingHookAccessor) (Object) hook).maicraft$isBiting();
    }

    @Override public boolean hookedEntity() {
        FishingHook hook = hook();
        return hook != null && hook.getHookedIn() != null;
    }

    @Override public boolean hookInWater() {
        FishingHook hook = hook();
        return hook != null && hook.level().getFluidState(hook.blockPosition()).is(FluidTags.WATER);
    }

    private FishingHook hook() {
        PlayerContext context = contexts.get();
        return context == null || context.localPlayer() == null ? null : context.localPlayer().fishing;
    }
}
