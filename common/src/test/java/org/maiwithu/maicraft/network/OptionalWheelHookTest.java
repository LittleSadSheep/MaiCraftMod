package org.maiwithu.maicraft.network;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** 轮胎参数、选中地面、计算冲量和真正施力四条链均须可达，避免可选钩子未命中时伪报完整观察。 */
public final class OptionalWheelHookTest {
    public static void main(String[] args) {
        var type=new ClassNode();type.name="fixture/WheelMount";
        var tick=method("sable$physicsTick");var wrapped=method("wrappedTick");
        var terrain=method("computeMaxExtensionToTerrain");var apply=method("applyBatchedForces");var original=method("originalTick");
        type.methods.add(tick);type.methods.add(wrapped);type.methods.add(terrain);type.methods.add(apply);
        type.methods.add(original);
        callback(wrapped,"begin");callback(wrapped,"finish");callback(original,"force");callback(terrain,"terrain");callback(apply,"applied");
        check(!OptionalServerMixinPlugin.hasWheelCapture(type),"不可达包装器被当成车轮观察证据");
        call(tick,type,wrapped);call(original,type,terrain);
        // 模拟 MixinExtras 将被包装原方法放入 continuation，必须沿真实句柄才能看到原生轮胎内部的观察点。
        wrapped.instructions.add(new InvokeDynamicInsnNode("run","()Ljava/lang/Runnable;",
                new Handle(Opcodes.H_INVOKESTATIC,"java/lang/invoke/LambdaMetafactory","metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",false),
                Type.getMethodType("()V"),new Handle(Opcodes.H_INVOKESTATIC,type.name,original.name,original.desc,false),Type.getMethodType("()V")));
        check(OptionalServerMixinPlugin.hasWheelCapture(type),"完整原生车轮调用链没有被识别");
        apply.instructions.clear();check(!OptionalServerMixinPlugin.hasWheelCapture(type),"只计算冲量不等于已经施力");
        callback(apply,"applied");terrain.instructions.clear();
        check(!OptionalServerMixinPlugin.hasWheelCapture(type),"缺少实际接地来源却声称完整观察");
        check(!OptionalServerMixinPlugin.hasWheelCapture(null),"缺少原生轮胎类被当成已经安装");
        System.out.println("OptionalWheelHookTest: passed");
    }
    private static MethodNode method(String name) {return new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,name,"()V",null,null);}
    private static void call(MethodNode entry,ClassNode type,MethodNode target) {entry.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,type.name,target.name,target.desc,false));}
    private static void callback(MethodNode method,String name) {method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"org/maiwithu/maicraft/server/physics/NativeWheelCapture",name,"()V",false));}
    private static void check(boolean okay,String why) {if(!okay)throw new AssertionError(why);}
}
