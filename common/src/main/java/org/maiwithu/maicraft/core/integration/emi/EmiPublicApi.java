// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.emi;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

/** 只解析EMI公开读取方法；通过公开接口调用私有实现，避免依赖某个插件配方类的可见性。 */
record EmiPublicApi(Class<?> api, Class<?> manager, Class<?> recipe, Class<?> category,
                    Class<?> ingredient, Class<?> stack, Class<?> itemStack, Class<?> fluidStack,
                    Class<?> emptyStack, Class<?> reload) {
    static final String API = "dev.emi.emi.api.EmiApi";

    static EmiPublicApi load(ClassLoader loader, Class<?> api) throws ClassNotFoundException {
        return new EmiPublicApi(api, type(loader, "api.recipe.EmiRecipeManager"), type(loader, "api.recipe.EmiRecipe"),
                type(loader, "api.recipe.EmiRecipeCategory"), type(loader, "api.stack.EmiIngredient"),
                type(loader, "api.stack.EmiStack"), type(loader, "api.stack.ItemEmiStack"),
                type(loader, "api.stack.FluidEmiStack"), type(loader, "api.stack.EmptyEmiStack"),
                type(loader, "runtime.EmiReloadManager"));
    }
    private static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName("dev.emi.emi." + name, false, loader);
    }
    Object call(Class<?> owner, Object target, String name) { return call(owner, target, name, new Class<?>[0]); }
    Object call(Class<?> owner, Object target, String name, Class<?>[] parameters, Object... arguments) {
        try { return owner.getMethod(name, parameters).invoke(target, arguments); }
        catch (ReflectiveOperationException failure) {
            Throwable cause = failure instanceof InvocationTargetException wrapped ? wrapped.getCause() : failure;
            throw new IllegalStateException("EMI read method unavailable: " + name, cause);
        }
    }
    List<?> list(Class<?> owner, Object target, String name) {
        Object value = call(owner, target, name);
        if (!(value instanceof List<?> values)) throw new IllegalStateException("EMI metadata is not a list: " + name);
        return values;
    }
    boolean loaded() { return Boolean.TRUE.equals(call(reload, null, "isLoaded")); }
    Object recipeManager() { return call(api, null, "getRecipeManager"); }
}
