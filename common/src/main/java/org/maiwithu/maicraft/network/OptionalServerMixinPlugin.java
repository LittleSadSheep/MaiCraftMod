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
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;

/** 在安装原生钩子前检查可选类资源；绝不定义、转换或初始化目标类。 */
public final class OptionalServerMixinPlugin implements IMixinConfigPlugin {
    private static volatile ClassNode worldTransformClass;
    private static volatile ClassNode assemblyRequestClass,assemblyTransformClass;
    private static volatile ClassNode wheelClass;
    private static final Set<String> MEKANISM_HOOKS = Set.of("MekTransportDeliveryMixin", "MekSorterSourceMixin",
            "MekItemExtractionMixin", "MekMonitorProductionMixin", "MekCachedProductionMixin",
            "MekOutputProductionMixin", "MekInputProductionMixin");
    private static final Set<String> CREATE_NEOFORGE_HOOKS = Set.of("CreateMillstoneProductionMixin", "CreateCrushingProductionMixin");
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        // 只有安装 Sable 时旁听船体物理子步；没有该模组的服务器照常启动。
        if (name.equals("SableForceObservationMixin")||name.equals("SableImpulseObservationMixin")) return present(targetClassName);
        // 只在原生 Simulated 已安装时旁听组装请求与返回值，不向缺少它的服务器引入类型依赖。
        if(name.equals("SimulatedAssemblyRequestMixin")||name.equals("SimulatedAssemblyTransformMixin")) return present(targetClassName);
        // 车轮旁听需要两个只读字段入口，先核对原生形状，避免版本不符的 Shadow 阻止游戏启动。
        if(name.equals("OffroadWheelObservationMixin"))return hasWheelShape(targetClassName);
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
    private static boolean hasWheelShape(String target) {
        try(var input=MixinService.getService().getResourceAsStream(target.replace('.','/')+".class")) {
            if(input==null)return false;
            var type=new ClassNode();new ClassReader(input).accept(type,ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
            return type.fields.stream().anyMatch(field->field.name.equals("touchingFriction")&&field.desc.equals("D"))
                    &&type.methods.stream().anyMatch(method->method.name.equals("getChasingYaw")&&method.desc.equals("()D"));
        } catch(IOException unavailable) {return false;}
    }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    /** 调用方先完成目标类定义，再读取最终调用链；单纯找到 AE2 或复制了包装方法不构成安装证明。 */
    public static boolean worldTransformEvents() {
        ClassNode transformed = worldTransformClass;
        return transformed != null && hasTransformCapture(transformed);
    }
    public static boolean assemblyEvents() { return hasAssemblyCapture(assemblyRequestClass,assemblyTransformClass); }
    public static boolean wheelEvents() {return hasWheelCapture(wheelClass);}
    static boolean hasWheelCapture(ClassNode type) {
        String observer="org/maiwithu/maicraft/server/physics/NativeWheelCapture";
        return calls(type,"sable$physicsTick",observer,"begin")&&calls(type,"sable$physicsTick",observer,"finish")
                &&calls(type,"sable$physicsTick",observer,"force")&&calls(type,"sable$physicsTick",observer,"terrain")
                &&calls(type,"applyBatchedForces",observer,"applied");
    }
    static boolean hasAssemblyCapture(ClassNode request,ClassNode transform) {
        String observer="org/maiwithu/maicraft/server/physics/NativeAssemblyCapture";
        return calls(request,"handle",observer,"begin")&&calls(request,"handle",observer,"finish")
                &&calls(transform,"assembleFromSingleBlock",observer,"assembled")&&calls(transform,"disassembleSubLevel",observer,"disassembled");
    }

    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // MixinExtras 在后续 extension.postApply 才真正安装 WrapOperation；这里保留同一棵树，不能提前锁死为 false。
        if (mixinClassName.endsWith(".Ae2TransformProductionMixin")) worldTransformClass = targetClass;
        if(mixinClassName.endsWith(".SimulatedAssemblyRequestMixin")) assemblyRequestClass=targetClass;
        if(mixinClassName.endsWith(".SimulatedAssemblyTransformMixin")) assemblyTransformClass=targetClass;
        if(mixinClassName.endsWith(".OffroadWheelObservationMixin"))wheelClass=targetClass;
    }

    static boolean hasTransformCapture(ClassNode type) {
        return calls(type,"tryTransform","org/maiwithu/maicraft/server/machine/ae2/TransformProductionCapture","spawned");
    }
    private static boolean calls(ClassNode type,String entry,String observer,String callback) {
        if(type==null) return false;
        // require=0 时未命中的包装方法也可能被复制进目标类；必须证明原生入口确实会走到只读捕获器。
        var pending = new ArrayDeque<MethodNode>(); var seen = new HashSet<String>();
        type.methods.stream().filter(method -> method.name.equals(entry)).forEach(pending::add);
        while (!pending.isEmpty()) {
            MethodNode method = pending.removeFirst(); if (!seen.add(method.name + method.desc)) continue;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    if (call.owner.equals(observer)&&call.name.equals(callback)) return true;
                    enqueue(type,call.owner,call.name,call.desc,pending);
                } else if(instruction instanceof InvokeDynamicInsnNode dynamic) {
                    // WrapMethod 用原生方法句柄构造 continuation；沿该句柄检查原方法内的地面和冲量观察，不能漏掉真实原生分支。
                    for(Object argument:dynamic.bsmArgs)if(argument instanceof Handle handle)
                        enqueue(type,handle.getOwner(),handle.getName(),handle.getDesc(),pending);
                }
            }
        }
        return false;
    }
    private static void enqueue(ClassNode type,String owner,String name,String descriptor,ArrayDeque<MethodNode> pending) {
        if(owner.equals(type.name))type.methods.stream().filter(method->method.name.equals(name)&&method.desc.equals(descriptor)).forEach(pending::add);
    }
}
