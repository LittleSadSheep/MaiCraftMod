// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** 只有原生入口真正调用到观察钩子时才公布服务端证据能力；复制进类但未被调用的方法不能冒充安装成功。 */
public final class OptionalWorldTransformHookTest {
    public static void main(String[] args) {
        var type = new ClassNode(); type.name = "appeng/recipes/transform/TransformLogic";
        var entry = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "tryTransform", "()Z", null, null);
        var wrapper = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "generatedWrapper", "()Z", null, null);
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "org/maiwithu/maicraft/server/machine/ae2/TransformProductionCapture", "spawned", "()V", false));
        type.methods.add(entry); type.methods.add(wrapper);
        check(!OptionalServerMixinPlugin.hasTransformCapture(type), "孤立的包装方法不能声明原生事件可用");
        // 复现 MixinExtras 的真实顺序：插件回调时包装方法已合并，但晚期注入还没接到原生入口。
        var plugin = new OptionalServerMixinPlugin();
        plugin.postApply("appeng.recipes.transform.TransformLogic", type,
                "org.maiwithu.maicraft.server.machine.mixin.Ae2TransformProductionMixin", null);
        check(!OptionalServerMixinPlugin.worldTransformEvents(), "晚期注入尚未发生时不能提前宣布可用");
        // MixinExtras 可生成中间桥接方法，能力检查须沿实际调用链找到最终只读捕获器。
        var bridge = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "generatedBridge", "()Z", null, null);
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, type.name, wrapper.name, wrapper.desc, false));
        type.methods.add(bridge);
        entry.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, type.name, bridge.name, bridge.desc, false));
        check(OptionalServerMixinPlugin.hasTransformCapture(type), "可达的生成桥接调用必须识别为已安装");
        check(OptionalServerMixinPlugin.worldTransformEvents(), "类定义完成后必须读取晚期改写的最终调用链，不能沿用插件回调时的 false");
        wrapper.instructions.clear(); wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, type.name, bridge.name, bridge.desc, false));
        check(!OptionalServerMixinPlugin.hasTransformCapture(type), "无捕获器的递归调用链不能误报或无限遍历");
        check(!OptionalServerMixinPlugin.worldTransformEvents(), "最终没有捕获路径时仍不得声称原生事件可用");
        System.out.println("OptionalWorldTransformHookTest: passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
