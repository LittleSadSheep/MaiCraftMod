// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.List;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Bytecode compatibility checks for the installed optional Create JAR; not a gameplay acceptance test. */
public final class ChainConveyorNativeApiShapeTest {
    private static final String BASE = "com/simibubi/create/content/kinetics/chainConveyor/";
    private static final String ENTITY = BASE + "ChainConveyorBlockEntity";
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: ChainConveyorNativeApiShapeTest <Create.jar>");
        try (var jar = new JarFile(args[0])) {
            ClassNode entity = read(jar, ENTITY), handler = read(jar, BASE + "ChainConveyorConnectionHandler");
            var cost = method(entity, "getChainCost");
            check(constant(cost, 2.5d) && call(cost, "java/lang/Math", "round") && call(cost, "java/lang/Math", "max"),
                    "native chain cost must retain round(distance / 2.5), minimum one");
            var inventory = method(entity, "getChainsFromInventory");
            check(field(inventory, "items") && field(inventory, "offhand") && call(inventory, "net/minecraft/world/item/ItemStack", "is"),
                    "native consumption is item-type based across main inventory and offhand; component safety belongs in admission");
            var client = method(handler, "validateAndConnect");
            check(constant(client, 2.5d) && constant(client, 1.5d) && field(client, "maxChainConveyorLength") && field(client, "maxChainConveyorConnections"),
                    "native preview must retain configured span/link limits and slope geometry");
            check(calls(client, ENTITY, "getChainsFromInventory").size() == 1
                            && calls(client, ENTITY, "getChainsFromInventory").getFirst().getPrevious().getOpcode() == Opcodes.ICONST_1,
                    "client material check must be simulation-only");
            check(!call(client, ENTITY, "addConnectionTo") && call(client, "net/createmod/catnip/platform/services/NetworkHelper", "sendToServer"),
                    "native client sends a request without predicting either connection set");
            var click = method(handler, "onItemUsedOnBlock");
            check(call(click, "net/neoforged/neoforge/event/entity/player/PlayerInteractEvent$RightClickBlock", "setCanceled")
                    && call(click, "net/minecraft/world/level/Level", "isClientSide") && call(click, BASE + "ChainConveyorConnectionHandler", "validateAndConnect"),
                    "ordinary block-use event must still route chain selection and the second click");
            var packet = method(read(jar, BASE + "ChainConveyorConnectionPacket"), "applySettings", ENTITY);
            var consumes = calls(packet, ENTITY, "getChainsFromInventory");
            var links = calls(packet, ENTITY, "addConnectionTo");
            check(consumes.size() == 2 && links.size() == 2 && consumes.get(0).getPrevious().getOpcode() == Opcodes.ICONST_1
                    && consumes.get(1).getPrevious().getOpcode() == Opcodes.ICONST_0, "server must simulate then actually consume ordinary chains");
            check(packet.instructions.indexOf(consumes.get(1)) < packet.instructions.indexOf(links.getFirst()),
                    "duplicate-link prevention remains necessary because native consumption precedes link insertion");
            var tangent = method(entity, "calculateConnectionStats");
            check(constant(tangent, 1.25d) && constant(tangent, .375d) && constant(tangent, 35f), "clearance must match the installed native tangent geometry");
        }
        System.out.println("ChainConveyorNativeApiShapeTest: native click routing, limits, simulation, consumption order and tangent geometry passed");
    }
    private static ClassNode read(JarFile jar, String name) throws Exception {
        var entry = jar.getJarEntry(name + ".class"); check(entry != null, "missing native class " + name);
        var node = new ClassNode(); try (var stream = jar.getInputStream(entry)) { new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES); }
        return node;
    }
    private static MethodNode method(ClassNode owner, String name) { return method(owner, name, ""); }
    private static MethodNode method(ClassNode owner, String name, String descriptorPart) {
        return owner.methods.stream().filter(value -> value.name.equals(name) && value.desc.contains(descriptorPart)).findFirst()
                .orElseThrow(() -> new AssertionError("missing method " + owner.name + "." + name));
    }
    private static List<MethodInsnNode> calls(MethodNode method, String owner, String name) {
        var result = new java.util.ArrayList<MethodInsnNode>();
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) result.add(call);
        return result;
    }
    private static boolean call(MethodNode method, String owner, String name) { return !calls(method, owner, name).isEmpty(); }
    private static boolean field(MethodNode method, String name) {
        for (var instruction : method.instructions) if (instruction instanceof FieldInsnNode field && field.name.equals(name)) return true;
        return false;
    }
    private static boolean constant(MethodNode method, Object value) {
        for (var instruction : method.instructions) if (instruction instanceof LdcInsnNode constant && value.equals(constant.cst)) return true;
        return false;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
