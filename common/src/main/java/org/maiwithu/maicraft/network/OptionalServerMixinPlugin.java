// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

/** Inspect optional class resources before native hooks; never define, transform or initialize target classes. */
public final class OptionalServerMixinPlugin implements IMixinConfigPlugin {
    private static final Set<String> MEKANISM_HOOKS = Set.of("MekTransportDeliveryMixin", "MekSorterSourceMixin",
            "MekItemExtractionMixin", "MekMonitorProductionMixin", "MekCachedProductionMixin",
            "MekOutputProductionMixin", "MekInputProductionMixin");
    private static final Set<String> CREATE_NEOFORGE_HOOKS = Set.of("CreateMillstoneProductionMixin", "CreateCrushingProductionMixin");
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        if (name.equals("Ae2CraftingLifecycleMixin")) return present("appeng.core.AppEng") && present(targetClassName);
        if (CREATE_NEOFORGE_HOOKS.contains(name)) return present("net.neoforged.neoforge.common.NeoForge")
                && present("net.neoforged.neoforge.items.IItemHandler") && present(targetClassName);
        if (MEKANISM_HOOKS.contains(name)) {
            return present("net.neoforged.neoforge.common.NeoForge")
                    && present("net.neoforged.neoforge.items.IItemHandler")
                    && present("mekanism.common.Mekanism") && present(targetClassName);
        }
        return mixinClassName.endsWith(".CreatePressProductionMixin") && present(targetClassName);
    }

    private static boolean present(String name) {
        // ModLauncher rejects untransformed bytecode requests. Both ModLauncher and Knot expose
        // class resources directly through the active game loader without invoking transformers.
        try (var resource = MixinService.getService().getResourceAsStream(name.replace('.', '/') + ".class")) {
            return resource != null;
        } catch (IOException unreadable) { throw new IllegalStateException("Cannot inspect optional native hook " + name, unreadable); }
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
