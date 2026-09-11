// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.connectivity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Checks the installed optional mixin target, not actual game acceptance or resource delivery. */
public final class MekDeliveryHookShapeTest {
    private static final String PIPE = "mekanism/common/content/network/transmitter/LogisticalTransporterBase";
    private static final String STACK = "mekanism/common/content/transporter/TransporterStack";
    private static final String REQUEST = "mekanism/common/lib/inventory/TransitRequest";
    private static final String SIMPLE = REQUEST + "$SimpleTransitRequest";
    private static final String RESPONSE = "L" + REQUEST + "$TransitResponse;";
    private static final String HANDLER = "net/neoforged/neoforge/items/IItemHandler";
    private static final String SORTER = "mekanism/common/tile/TileEntityLogisticalSorter";
    private static final String ITEM = "Lnet/minecraft/world/item/ItemStack;";
    private static final String UNCHECKED = "(L" + HANDLER + ";I)" + RESPONSE;
    private static final String DELIVERY = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;L"
            + HANDLER + ";IZ)" + RESPONSE;

    private MekDeliveryHookShapeTest() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Usage: MekDeliveryHookShapeTest <Mekanism.jar>");
        try (JarFile jar = new JarFile(Path.of(args[0]).toFile())) {
            ClassNode pipe = read(jar, PIPE), request = read(jar, REQUEST), simple = read(jar, SIMPLE);
            deliverySliceRetainsExactlyOneStackCapture(pipe);
            normalDeliveryForwardsToRealInsertion(jar, request, simple);
            uncheckedInsertionUsesExecuteFlag(request);
            homeLocationRetainsTransportOriginArgument(pipe);
            sorterSourceAndExtractionHooksRemainPaired(read(jar, SORTER));
            pullSourceAndExtractionHooksRemainPaired(pipe);
            sourceExtractionUsesExecuteFlag(read(jar, "mekanism/common/lib/inventory/HandlerTransitRequest$HandlerItemData"));
        }
        System.out.println("MekDeliveryHookShapeTest: 7 bytecode groups passed; game delivery is not exercised");
    }

    private static void deliverySliceRetainsExactlyOneStackCapture(ClassNode pipe) {
        MethodNode tick = method(pipe, "onUpdateServer", "()V");
        List<MethodInsnNode> deliveries = calls(tick, SIMPLE, "addToInventory", DELIVERY);
        check(deliveries.size() == 1, "tick must have exactly one SimpleTransitRequest delivery call");
        List<MethodInsnNode> finals = calls(tick, STACK, "isFinal", "(L" + PIPE + ";)Z");
        check(!finals.isEmpty(), "first isFinal slice anchor is missing");
        int start = tick.instructions.indexOf(finals.getFirst());
        int end = tick.instructions.indexOf(deliveries.getFirst());
        check(start < end, "first isFinal must precede the unique delivery call");
        int captures = 0;
        for (int index = start; index < end; index++) {
            AbstractInsnNode instruction = tick.instructions.get(index);
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(STACK) && field.name.equals("itemStack") && field.desc.equals(ITEM)) captures++;
        }
        check(captures == 1, "delivery slice must capture exactly one TransporterStack.itemStack");
        check(isCall(previous(deliveries.getFirst()), STACK + "$Path", "isHome", "()Z"),
                "delivery boolean must come from Path.isHome, never a simulation flag");
    }

    private static void normalDeliveryForwardsToRealInsertion(JarFile jar, ClassNode request, ClassNode simple) throws IOException {
        ClassNode inherited = simple;
        for (int depth = 0; !inherited.name.equals(REQUEST) && depth < 8; depth++) {
            check(inherited.methods.stream().noneMatch(value -> value.name.equals("addToInventory")),
                    "SimpleTransitRequest hierarchy must not override delivery behavior");
            inherited = read(jar, inherited.superName);
        }
        check(inherited.name.equals(REQUEST), "SimpleTransitRequest must inherit TransitRequest");
        check(request.methods.stream().filter(value -> value.name.equals("addToInventory")).count() == 1,
                "delivery overload set changed; review boolean semantics");
        MethodNode add = method(request, "addToInventory", DELIVERY);
        List<MethodInsnNode> writes = calls(add, REQUEST, "addToInventoryUnchecked", UNCHECKED);
        check(writes.size() == 1, "delivery must forward once to unchecked insertion");
        boolean forceFalseForwards = false;
        for (AbstractInsnNode instruction : add.instructions) {
            if (!(instruction instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ILOAD || load.var != 5) continue;
            AbstractInsnNode next = next(instruction);
            if (!(next instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFEQ) continue;
            AbstractInsnNode target = next(jump.label);
            forceFalseForwards = isVariable(target, Opcodes.ALOAD, 0)
                    && isVariable(next(target), Opcodes.ALOAD, 3)
                    && isVariable(next(next(target)), Opcodes.ILOAD, 4)
                    && next(next(next(target))) == writes.getFirst()
                    && next(writes.getFirst()).getOpcode() == Opcodes.ARETURN;
            if (forceFalseForwards) break;
        }
        check(forceFalseForwards, "force=false must branch directly to real insertion and return its response");
    }

    private static void uncheckedInsertionUsesExecuteFlag(ClassNode request) {
        MethodNode add = method(request, "addToInventoryUnchecked", UNCHECKED);
        List<MethodInsnNode> inserts = calls(add, HANDLER, "insertItem", "(I" + ITEM + "Z)" + ITEM);
        check(inserts.size() == 1, "unchecked insertion must have one native item-handler call site");
        check(inserts.getFirst().getOpcode() == Opcodes.INVOKEINTERFACE
                        && previous(inserts.getFirst()).getOpcode() == Opcodes.ICONST_0,
                "native insertItem must receive simulate=false");
    }

    private static void homeLocationRetainsTransportOriginArgument(ClassNode pipe) {
        MethodNode create = method(pipe, "createInsertStack", "(JLmekanism/api/text/EnumColor;)L" + STACK + ";");
        int assignments = 0;
        for (AbstractInsnNode instruction : create.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals(STACK) && field.name.equals("homeLocation") && field.desc.equals("J")) {
                check(isVariable(previous(field), Opcodes.LLOAD, 1), "homeLocation must retain the transport origin argument");
                assignments++;
            }
        }
        check(assignments == 1, "createInsertStack must initialize homeLocation exactly once");
    }

    private static void sorterSourceAndExtractionHooksRemainPaired(ClassNode sorter) {
        MethodNode home = method(sorter, "getHomeInventory", "()L" + HANDLER + ";");
        List<MethodInsnNode> capabilities = calls(home, "net/neoforged/neoforge/capabilities/BlockCapabilityCache",
                "getCapability", "()Ljava/lang/Object;");
        check(capabilities.size() == 1, "sorter home inventory must read exactly one native capability cache");
        AbstractInsnNode cast = next(capabilities.getFirst());
        check(cast instanceof org.objectweb.asm.tree.TypeInsnNode type && type.getOpcode() == Opcodes.CHECKCAST
                        && type.desc.equals(HANDLER) && next(cast).getOpcode() == Opcodes.ARETURN,
                "sorter must return the captured home handler without substitution");
        MethodNode tick = method(sorter, "onUpdateServer", "()Z");
        List<MethodInsnNode> emissions = calls(tick, SORTER, "emitItemToTransporter", "(L" + HANDLER
                + ";L" + REQUEST + ";Lmekanism/api/text/EnumColor;I)" + RESPONSE);
        List<MethodInsnNode> uses = calls(tick, REQUEST + "$TransitResponse", "useAll", "()" + ITEM);
        check(emissions.size() == 2 && uses.size() == 2, "both filtered and automatic sorter emission paths need source hooks");
        check(tick.instructions.indexOf(emissions.get(0)) < tick.instructions.indexOf(uses.get(0))
                        && tick.instructions.indexOf(uses.get(0)) < tick.instructions.indexOf(emissions.get(1))
                        && tick.instructions.indexOf(emissions.get(1)) < tick.instructions.indexOf(uses.get(1)),
                "sorter extraction hooks must follow their corresponding emission paths");
    }

    private static void pullSourceAndExtractionHooksRemainPaired(ClassNode pipe) {
        MethodNode tick = method(pipe, "onUpdateServer", "()V");
        List<MethodInsnNode> sources = calls(tick, PIPE, "getCapForSide", "(Lnet/minecraft/core/Direction;)L" + HANDLER + ";");
        List<MethodInsnNode> emissions = calls(tick, PIPE, "insert", "(Lnet/minecraft/world/level/block/entity/BlockEntity;"
                + "Lnet/minecraft/core/BlockPos;L" + REQUEST + ";Lmekanism/api/text/EnumColor;ZI)" + RESPONSE);
        List<MethodInsnNode> uses = calls(tick, REQUEST + "$TransitResponse", "useAll", "()" + ITEM);
        check(sources.size() == 1 && emissions.size() == 1 && uses.size() == 1,
                "PULL must expose one source handler, one direct emission and one extraction call site");
        check(tick.instructions.indexOf(sources.getFirst()) < tick.instructions.indexOf(emissions.getFirst())
                        && tick.instructions.indexOf(emissions.getFirst()) < tick.instructions.indexOf(uses.getFirst()),
                "PULL source observation must precede emission and actual extraction");
    }

    private static void sourceExtractionUsesExecuteFlag(ClassNode itemData) {
        MethodNode use = method(itemData, "use", "(I)" + ITEM);
        List<MethodInsnNode> extracts = calls(use, HANDLER, "extractItem", "(IIZ)" + ITEM);
        check(extracts.size() == 1, "HandlerItemData.use must expose one actual extraction call site");
        check(extracts.getFirst().getOpcode() == Opcodes.INVOKEINTERFACE
                        && previous(extracts.getFirst()).getOpcode() == Opcodes.ICONST_0,
                "source extraction must receive simulate=false");
    }

    private static ClassNode read(JarFile jar, String name) throws IOException {
        var entry = jar.getJarEntry(name + ".class");
        check(entry != null, "missing class: " + name);
        ClassNode node = new ClassNode();
        try (var stream = jar.getInputStream(entry)) {
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return node;
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream().filter(value -> value.name.equals(name) && value.desc.equals(descriptor)).findFirst()
                .orElseThrow(() -> new AssertionError("missing target: " + owner.name + "." + name + descriptor));
    }

    private static List<MethodInsnNode> calls(MethodNode method, String owner, String name, String descriptor) {
        var found = new java.util.ArrayList<MethodInsnNode>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (isCall(instruction, owner, name, descriptor)) found.add((MethodInsnNode) instruction);
        }
        return found;
    }

    private static boolean isCall(AbstractInsnNode instruction, String owner, String name, String descriptor) {
        return instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                && call.name.equals(name) && call.desc.equals(descriptor);
    }

    private static boolean isVariable(AbstractInsnNode instruction, int opcode, int index) {
        return instruction instanceof VarInsnNode value && value.getOpcode() == opcode && value.var == index;
    }

    private static AbstractInsnNode next(AbstractInsnNode instruction) {
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static AbstractInsnNode previous(AbstractInsnNode instruction) {
        do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
