// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;
import java.util.ArrayDeque;
import java.util.HashSet;
import org.objectweb.asm.ClassReader;

/** 在安装原生钩子前检查可选类资源；绝不定义、转换或初始化目标类。 */
public final class OptionalServerMixinPlugin implements IMixinConfigPlugin {
    private static volatile ClassNode worldTransformClass;
    private static final Set<String> MEKANISM_HOOKS = Set.of("MekTransportDeliveryMixin", "MekSorterSourceMixin",
            "MekItemExtractionMixin", "MekMonitorProductionMixin", "MekCachedProductionMixin",
            "MekOutputProductionMixin", "MekInputProductionMixin");
    private static final Set<String> CREATE_NEOFORGE_HOOKS = Set.of("CreateMillstoneProductionMixin", "CreateCrushingProductionMixin");
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        // 只有安装 Sable 时旁听船体物理子步；没有该模组的服务器照常启动。
        if (name.equals("SableForceObservationMixin")||name.equals("SableImpulseObservationMixin")) return present(targetClassName);
        if (name.equals("CreateStressObservationMixin")) return hasStressFields(targetClassName);
        // 机械手只旁听原生同步；可选模组不存在时不加载适配器，版本未命中 read 时保持观察未知。
        if (name.equals("CreateDeployerHandObservationMixin")) return present(targetClassName);
        if (name.equals("Ae2CraftingLifecycleMixin")) return present("appeng.core.AppEng") && present(targetClassName);
        // 本次转化钩子按 AE2 19.2 的 NeoForge 原生调用形状接入；缺少模组或加载器时不加载适配类。
        if (name.equals("Ae2TransformProductionMixin")) return present("net.neoforged.neoforge.common.NeoForge")
                && present("appeng.core.AppEng") && present(targetClassName);
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
        // ModLauncher 会拒绝未经转换的字节码请求。ModLauncher 和 Knot 都可通过当前游戏加载器直接读取类资源，不会调用转换器。
        try (var resource = MixinService.getService().getResourceAsStream(name.replace('.', '/') + ".class")) {
            return resource != null;
        } catch (IOException unreadable) { throw new IllegalStateException("Cannot inspect optional native hook " + name, unreadable); }
    }

    private static boolean hasStressFields(String target) {
        // 可选版本的字段形状不匹配时明确保留应力未知，不能为了检查机器而让缺少 Create 的客户端无法启动。
        try (var input = MixinService.getService().getResourceAsStream(target.replace('.', '/') + ".class")) {
            if (input == null) return false;
            var node = new ClassNode(); new ClassReader(input).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node.fields.stream().anyMatch(field -> field.name.equals("capacity") && field.desc.equals("F"))
                    && node.fields.stream().anyMatch(field -> field.name.equals("stress") && field.desc.equals("F"))
                    && node.fields.stream().anyMatch(field -> field.name.equals("networkSize") && field.desc.equals("I"));
        } catch (IOException unavailable) { return false; }
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    /** 调用方先完成目标类定义，再读取最终调用链；单纯找到 AE2 或复制了包装方法不构成安装证明。 */
    public static boolean worldTransformEvents() {
        ClassNode transformed = worldTransformClass;
        return transformed != null && hasTransformCapture(transformed);
    }

    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // MixinExtras 在后续 extension.postApply 才真正安装 WrapOperation；这里保留同一棵树，不能提前锁死为 false。
        if (mixinClassName.endsWith(".Ae2TransformProductionMixin")) worldTransformClass = targetClass;
    }

    static boolean hasTransformCapture(ClassNode type) {
        // require=0 时未命中的包装方法也可能被复制进目标类；必须证明原生入口确实会走到只读捕获器。
        var pending = new ArrayDeque<MethodNode>(); var seen = new HashSet<String>();
        type.methods.stream().filter(method -> method.name.equals("tryTransform")).forEach(pending::add);
        while (!pending.isEmpty()) {
            MethodNode method = pending.removeFirst(); if (!seen.add(method.name + method.desc)) continue;
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals("org/maiwithu/maicraft/server/machine/ae2/TransformProductionCapture")
                        && call.name.equals("spawned")) return true;
                if (call.owner.equals(type.name)) type.methods.stream().filter(next -> next.name.equals(call.name) && next.desc.equals(call.desc))
                        .forEach(pending::add);
            }
        }
        return false;
    }
}
