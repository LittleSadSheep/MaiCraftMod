// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.mekanism;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Checks native injection sites and executed resource operations; does not simulate machine production. */
public final class MekProductionHookShapeTest {
    private static final String ROOT = "mekanism/api/recipes/";
    private static final String CACHE = ROOT + "cache/CachedRecipe";
    private static final String INPUT = ROOT + "inputs/", OUTPUT = ROOT + "outputs/";
    private static final String ACTION = "Lmekanism/api/Action;", AUTOMATION = "Lmekanism/api/AutomationType;";
    private static final String SLOT = "mekanism/api/inventory/IInventorySlot", ITEM = "Lnet/minecraft/world/item/ItemStack;";
    private static final String TANK = "mekanism/api/fluid/IExtendedFluidTank", FLUID = "Lnet/neoforged/neoforge/fluids/FluidStack;";
    private static final String CHEMICAL_TANK = "mekanism/api/chemical/IChemicalTank", CHEMICAL = "Lmekanism/api/chemical/ChemicalStack;";

    private MekProductionHookShapeTest() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Usage: MekProductionHookShapeTest <Mekanism.jar>");
        try (JarFile jar = new JarFile(Path.of(args[0]).toFile())) {
            monitorAndCompletionBoundaries(jar);
            completeInputsAreInsideFinish(jar, "OneInputCachedRecipe", 1);
            completeInputsAreInsideFinish(jar, "TwoInputCachedRecipe", 2);
            completeInputsAreInsideFinish(jar, "ChemicalChemicalToChemicalCachedRecipe", 2);
            rotaryHasOneInputPerExclusiveDirection(jar);
            actualOutput(jar, SLOT, ITEM, "insertItem");
            actualOutput(jar, TANK, FLUID, "insert");
            actualOutput(jar, CHEMICAL_TANK, CHEMICAL, "insert");
            actualInput(jar, INPUT + "InputHelper$1", ITEM, "I", SLOT);
            actualInput(jar, INPUT + "InputHelper$3", FLUID, "I", TANK);
            actualInput(jar, INPUT + "InputHelper$ChemicalInputHandler", CHEMICAL, "J", CHEMICAL_TANK);
            immediateMachineDurationsStayOneTick(jar);
        }
        System.out.println("MekProductionHookShapeTest: 12 bytecode groups passed; game production is not exercised");
    }

    private static void monitorAndCompletionBoundaries(JarFile jar) throws IOException {
        String monitorName = "mekanism/common/recipe/lookup/monitor/RecipeCacheLookupMonitor";
        String lookup = "mekanism/common/recipe/lookup/IRecipeLookupHandler";
        ClassNode monitor = read(jar, monitorName);
        MethodNode constructor = method(monitor, "<init>", "(L" + lookup + ";I)V");
        int assignments = 0;
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals(monitorName) && field.name.equals("handler") && field.desc.equals("L" + lookup + ";")) {
                check(previous(field) instanceof org.objectweb.asm.tree.VarInsnNode load
                        && load.getOpcode() == Opcodes.ALOAD && load.var == 1, "monitor must retain the actual constructor handler");
                assignments++;
            }
        }
        check(assignments == 1, "monitor ownership constructor hook changed");
        check(calls(method(monitor, "updateAndProcess", "()Z"), CACHE, "process", "()V").size() == 1,
                "monitor must expose exactly one native recipe process call");
        check(calls(method(read(jar, CACHE), "process", "()V"), CACHE, "finishProcessing", "(I)V").size() == 1,
                "cached recipe must expose exactly one completion call site");
        method(read(jar, "mekanism/common/recipe/IMekanismRecipeTypeProvider"), "getRecipes",
                "(Lnet/minecraft/world/level/Level;)Ljava/util/List;");
    }

    private static void completeInputsAreInsideFinish(JarFile jar, String simpleName, int count) throws IOException {
        ClassNode type = read(jar, ROOT + "cache/" + simpleName);
        check(type.superName.equals(CACHE), "supported cache must directly inherit the audited native CachedRecipe");
        check(type.methods.stream().noneMatch(value -> value.name.equals("useResources")),
                "per-tick resource consumption requires an explicit accounting adapter");
        MethodNode finish = method(type, "finishProcessing", "(I)V");
        List<MethodInsnNode> inputs = calls(finish, INPUT + "IInputHandler", "use", "(Ljava/lang/Object;I)V");
        List<MethodInsnNode> outputs = calls(finish, OUTPUT + "IOutputHandler", "handleOutput", "(Ljava/lang/Object;I)V");
        check(inputs.size() == count && outputs.size() == 1, "supported cache input/output shape changed: " + simpleName);
        for (MethodInsnNode input : inputs) check(finish.instructions.indexOf(input) < finish.instructions.indexOf(outputs.getFirst()),
                "inputs must be consumed before native output handling");
    }

    private static void actualOutput(JarFile jar, String handler, String stack, String insertion) throws IOException {
        MethodNode output = method(read(jar, OUTPUT + "OutputHelper"), "handleOutput", "(L" + handler + ";" + stack + "I)V");
        List<MethodInsnNode> inserts = calls(output, handler, insertion, "(" + stack + ACTION + AUTOMATION + ")" + stack);
        check(inserts.size() == 1, "output helper must expose exactly one insertion site for " + stack);
        MethodInsnNode insert = inserts.getFirst();
        check(insert.getOpcode() == Opcodes.INVOKEINTERFACE
                        && constant(previous(insert), "mekanism/api/AutomationType", "INTERNAL")
                        && constant(previous(previous(insert)), "mekanism/api/Action", "EXECUTE"),
                "output hook must observe EXECUTE/INTERNAL, not capacity simulation");
        check(next(insert).getOpcode() == Opcodes.POP, "native output must discard its actual remainder after the wrapper observes it");
    }

    private static void rotaryHasOneInputPerExclusiveDirection(JarFile jar) throws IOException {
        ClassNode rotary = read(jar, ROOT + "cache/RotaryCachedRecipe");
        check(rotary.superName.equals(CACHE) && rotary.methods.stream().noneMatch(value -> value.name.equals("useResources")),
                "rotary must consume inputs entirely within its completion method");
        MethodNode finish = method(rotary, "finishProcessing", "(I)V");
        List<MethodInsnNode> inputs = calls(finish, INPUT + "IInputHandler", "use", "(Ljava/lang/Object;I)V");
        List<MethodInsnNode> outputs = calls(finish, OUTPUT + "IOutputHandler", "handleOutput", "(Ljava/lang/Object;I)V");
        List<MethodInsnNode> modes = calls(finish, "java/util/function/BooleanSupplier", "getAsBoolean", "()Z");
        check(inputs.size() == 2 && outputs.size() == 2 && modes.size() == 1, "rotary must have two direction-specific effect sites");
        check(next(modes.getFirst()) instanceof org.objectweb.asm.tree.JumpInsnNode split
                        && split.getOpcode() == Opcodes.IFEQ, "rotary direction must branch on its native mode");
        var split = (org.objectweb.asm.tree.JumpInsnNode) next(modes.getFirst());
        check(next(outputs.getFirst()) instanceof org.objectweb.asm.tree.JumpInsnNode done
                        && done.getOpcode() == Opcodes.GOTO, "first rotary direction must jump past the other direction");
        var done = (org.objectweb.asm.tree.JumpInsnNode) next(outputs.getFirst());
        int alternative = finish.instructions.indexOf(split.label), end = finish.instructions.indexOf(done.label);
        check(finish.instructions.indexOf(inputs.get(0)) < finish.instructions.indexOf(outputs.get(0))
                        && finish.instructions.indexOf(outputs.get(0)) < alternative
                        && alternative < finish.instructions.indexOf(inputs.get(1))
                        && finish.instructions.indexOf(inputs.get(1)) < finish.instructions.indexOf(outputs.get(1))
                        && finish.instructions.indexOf(outputs.get(1)) < end,
                "each exclusive rotary direction must consume exactly one input before exactly one output");
        for (AbstractInsnNode instruction = next(split); instruction != split.label; instruction = instruction.getNext()) {
            if (instruction instanceof org.objectweb.asm.tree.JumpInsnNode jump) {
                check(finish.instructions.indexOf(jump.label) >= end, "first rotary direction must not enter the second direction");
            }
        }
    }

    private static void actualInput(JarFile jar, String owner, String stack, String amount, String handler) throws IOException {
        MethodNode use = method(read(jar, owner), "use", "(" + stack + amount + ")V");
        List<MethodInsnNode> shrinks = calls(use, handler, "shrinkStack", "(" + amount + ACTION + ")" + amount);
        check(shrinks.size() == 1, "input helper must expose one actual shrink call: " + owner);
        check(shrinks.getFirst().getOpcode() == Opcodes.INVOKEINTERFACE
                        && constant(previous(shrinks.getFirst()), "mekanism/api/Action", "EXECUTE"),
                "input shrink must use EXECUTE and preserve the actual removed amount");
    }

    private static void immediateMachineDurationsStayOneTick(JarFile jar) throws IOException {
        ClassNode cache = read(jar, CACHE);
        MethodNode constructor = method(cache, "<init>", "(Lmekanism/api/recipes/MekanismRecipe;Ljava/util/function/BooleanSupplier;)V");
        int assignments = 0;
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
                    || !field.owner.equals(CACHE) || !field.name.equals("requiredTicks")
                    || !field.desc.equals("Ljava/util/function/IntSupplier;")) continue;
            check(previous(field) instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode,
                    "default duration must come from the constructor's IntSupplier binding");
            var binding = (org.objectweb.asm.tree.InvokeDynamicInsnNode) previous(field);
            org.objectweb.asm.Handle implementation = null;
            for (Object argument : binding.bsmArgs) {
                if (argument instanceof org.objectweb.asm.Handle handle) {
                    check(implementation == null, "duration binding must have one implementation handle");
                    implementation = handle;
                }
            }
            check(implementation != null && implementation.getOwner().equals(CACHE)
                            && implementation.getTag() == Opcodes.H_INVOKESTATIC && implementation.getDesc().equals("()I"),
                    "duration supplier implementation must be a static native integer supplier");
            MethodNode supplier = method(cache, implementation.getName(), implementation.getDesc());
            List<Integer> opcodes = new ArrayList<>();
            for (AbstractInsnNode operation : supplier.instructions) if (operation.getOpcode() >= 0) opcodes.add(operation.getOpcode());
            check(opcodes.equals(List.of(Opcodes.ICONST_1, Opcodes.IRETURN)), "default requiredTicks must return exactly one tick");
            assignments++;
        }
        check(assignments == 1, "CachedRecipe constructor duration initialization changed");
        for (String name : new String[]{"TileEntityChemicalInfuser", "TileEntityChemicalWasher", "TileEntityElectrolyticSeparator",
                "TileEntityIsotopicCentrifuge", "TileEntityRotaryCondensentrator"}) {
            int creators = 0;
            for (MethodNode creator : read(jar, "mekanism/common/tile/machine/" + name).methods) {
                if (!creator.name.equals("createNewCachedRecipe")) continue;
                creators++;
                for (AbstractInsnNode operation : creator.instructions) {
                    check(!(operation instanceof MethodInsnNode call && call.name.equals("setRequiredTicks")),
                            "instantaneous machine overrides requiredTicks: " + name);
                }
            }
            check(creators > 0, "missing native cached-recipe constructor for " + name);
        }
    }

    private static boolean constant(AbstractInsnNode instruction, String owner, String name) {
        return instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                && field.owner.equals(owner) && field.name.equals(name);
    }
    private static ClassNode read(JarFile jar, String name) throws IOException {
        var entry = jar.getJarEntry(name + ".class");
        check(entry != null, "missing native class: " + name);
        ClassNode result = new ClassNode();
        try (var stream = jar.getInputStream(entry)) { new ClassReader(stream).accept(result, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES); }
        return result;
    }
    private static MethodNode method(ClassNode type, String name, String descriptor) {
        return type.methods.stream().filter(value -> value.name.equals(name) && value.desc.equals(descriptor)).findFirst()
                .orElseThrow(() -> new AssertionError("missing native target: " + type.name + "." + name + descriptor));
    }
    private static List<MethodInsnNode> calls(MethodNode method, String owner, String name, String descriptor) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)
                    && call.desc.equals(descriptor)) result.add(call);
        }
        return result;
    }
    private static AbstractInsnNode previous(AbstractInsnNode instruction) {
        do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
    private static AbstractInsnNode next(AbstractInsnNode instruction) {
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
