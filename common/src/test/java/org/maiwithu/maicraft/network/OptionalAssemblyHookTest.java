package org.maiwithu.maicraft.network;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** 组装观察须同时接入玩家请求、创建及拆回路径；复制了未调用的方法不构成服务器确认能力。 */
public final class OptionalAssemblyHookTest {
    public static void main(String[] args) {
        var request=new ClassNode();request.name="fixture/AssemblePacket";
        var transform=new ClassNode();transform.name="fixture/SimAssemblyHelper";
        MethodNode entry=method("handle"),wrapper=method("wrappedHandle");
        MethodNode assemble=method("assembleFromSingleBlock"),disassemble=method("disassembleSubLevel");
        request.methods.add(entry);request.methods.add(wrapper);transform.methods.add(assemble);transform.methods.add(disassemble);
        callback(wrapper,"begin");callback(wrapper,"finish");callback(assemble,"assembled");callback(disassemble,"disassembled");
        check(!OptionalServerMixinPlugin.hasAssemblyCapture(request,transform),"不可达包装方法被当成已经安装");
        entry.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,request.name,wrapper.name,wrapper.desc,false));
        check(OptionalServerMixinPlugin.hasAssemblyCapture(request,transform),"完整原生调用链未被识别");
        disassemble.instructions.clear();
        check(!OptionalServerMixinPlugin.hasAssemblyCapture(request,transform),"只观察创建不能冒称也能确认拆回");
        callback(disassemble,"disassembled");wrapper.instructions.clear();callback(wrapper,"begin");
        check(!OptionalServerMixinPlugin.hasAssemblyCapture(request,transform),"缺少请求收尾时不能公布完整确认能力");
        check(!OptionalServerMixinPlugin.hasAssemblyCapture(null,transform),"缺少原生请求类不能假定可用");
        System.out.println("OptionalAssemblyHookTest: passed");
    }
    private static MethodNode method(String name) { return new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,name,"()V",null,null); }
    private static void callback(MethodNode method,String name) {
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"org/maiwithu/maicraft/server/physics/NativeAssemblyCapture",name,"()V",false));
    }
    private static void check(boolean value,String why) { if(!value)throw new AssertionError(why); }
}
