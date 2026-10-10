// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

import org.maiwithu.maicraft.game.ModIdentity;

/**
 * JEI 插件：JEI 只在它自己创建的插件对象上交出运行时（进世界、配方加载完时 onRuntimeAvailable，离开世界时收回），
 * 这里接住，交给 JEI 的读写端读配方。
 *
 * <p>JEI 按注解扫描、自己 new 这个类，我们拿不到它的实例，只能经一个静态字段交接：这是所有者批准的例外，
 * 全仓只此一处，别处仍然不加可变的静态字段。没装 JEI 时这个类不会被加载。
 */
@JeiPlugin
public final class JeiRuntimeHandoff implements IModPlugin {

    /** JEI 交出来的运行时；没进世界或 JEI 收回了时为 null。客户端线程写、客户端线程读，volatile 让别处也立刻看到。 */
    private static volatile IJeiRuntime runtime;

    /** 此刻 JEI 交出来的运行时；还没有时为 null。 */
    static IJeiRuntime current() {
        return runtime;
    }

    @Override public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(ModIdentity.MOD_ID, "recipe_lookup");
    }

    @Override public void onRuntimeAvailable(IJeiRuntime available) {
        runtime = available;
    }

    @Override public void onRuntimeUnavailable() {
        runtime = null;
    }
}
