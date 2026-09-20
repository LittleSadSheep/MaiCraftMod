// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.emi;

import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.Objects;

/** EMI是可选知识来源：缺失、尚未完成加载和展示索引无匹配分别报告，不启动插件重载或任何游戏动作。 */
public final class ReflectiveEmiRecipeAccess implements EmiRecipeAccess {
    private final ClassLoader loader;
    public ReflectiveEmiRecipeAccess() { this(ReflectiveEmiRecipeAccess.class.getClassLoader()); }
    public ReflectiveEmiRecipeAccess(ClassLoader loader) { this.loader = Objects.requireNonNull(loader); }

    @Override public Query query(LocalPlayer player, ResourceLocation itemId, boolean uses) {
        Class<?> entry;
        try { entry = Class.forName(EmiPublicApi.API, false, loader); }
        catch (ClassNotFoundException absent) { return Query.unavailable("not_installed", "EMI is not installed"); }
        catch (LinkageError broken) { return Query.unavailable("api_unavailable", "EMI classes could not be linked"); }
        if (player == null || player.connection == null)
            return Query.unavailable("not_loaded", "EMI recipe knowledge requires the current client world and synchronized connection");
        if (!BuiltInRegistries.ITEM.containsKey(itemId) || BuiltInRegistries.ITEM.get(itemId) == Items.AIR)
            return Query.unavailable("query_item_unavailable", "The requested item is not registered in this client");
        try {
            EmiPublicApi api = EmiPublicApi.load(loader, entry);
            if (!api.loaded()) return Query.unavailable("not_loaded", "EMI recipe loading has not completed");
            Object manager = api.recipeManager();
            if (manager == null) return Query.unavailable("not_loaded", "EMI has no current recipe manager");
            // 只查询EMI已经建立的物品索引；不遍历全部配方，不试运行配方，也不按ID猜测缺失的组件变体。
            Object key = api.call(api.stack(), null, "of", new Class<?>[]{ItemStack.class}, new ItemStack(BuiltInRegistries.ITEM.get(itemId)));
            Object found = api.call(api.manager(), manager, uses ? "getRecipesByInput" : "getRecipesByOutput", new Class<?>[]{api.stack()}, key);
            if (!(found instanceof List<?> recipes)) throw new IllegalStateException("EMI recipe index is not a list");
            var world = player.level(); var connection = player.connection;
            return new Query("available", "Installed EMI display metadata; native backing references do not establish execution support",
                    Integer.toUnsignedString(System.identityHashCode(manager), 16), recipes.size(),
                    index -> new EmiRecipeReader(api, manager, player.registryAccess()).read(recipes.get(index)),
                    () -> player.level() == world && player.connection == connection && api.loaded() && api.recipeManager() == manager);
        } catch (ClassNotFoundException | RuntimeException | LinkageError unavailable) {
            return Query.unavailable("api_unavailable", "The installed EMI read API is unavailable or incompatible");
        }
    }
}
