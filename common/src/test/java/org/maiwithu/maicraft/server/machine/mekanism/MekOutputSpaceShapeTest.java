// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.mekanism;

import java.io.IOException;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Validates that named storage fields are actually wired to native output handlers, not merely present. */
public final class MekOutputSpaceShapeTest {
    private static final String MACHINE = "mekanism/common/tile/machine/";
    private static final String CHEMICAL = "Lmekanism/api/chemical/IChemicalTank;";
    private static final String OUTPUT = "mekanism/api/recipes/outputs/OutputHelper";
    private static final String OUTPUT_SLOT = "mekanism/common/inventory/slot/OutputInventorySlot";

    private MekOutputSpaceShapeTest() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Usage: MekOutputSpaceShapeTest <Mekanism.jar>");
        try (JarFile jar = new JarFile(Path.of(args[0]).toFile())) {
            outputField(jar, MACHINE + "TileEntityChemicalOxidizer", "gasTank", CHEMICAL, true);
            outputField(jar, MACHINE + "TileEntityChemicalInfuser", "centerTank", CHEMICAL, true);
            outputField(jar, MACHINE + "TileEntityChemicalWasher", "outputTank", CHEMICAL, true);
            outputField(jar, MACHINE + "TileEntityIsotopicCentrifuge", "outputTank", CHEMICAL, true);
            outputField(jar, MACHINE + "TileEntityElectrolyticSeparator", "leftTank", CHEMICAL, true);
            outputField(jar, MACHINE + "TileEntityElectrolyticSeparator", "rightTank", CHEMICAL, true);
            outputField(jar, MACHINE + "TileEntityRotaryCondensentrator", "gasTank", CHEMICAL, true);
            outputField(jar, MACHINE + "TileEntityRotaryCondensentrator", "fluidTank",
                    "Lmekanism/common/capabilities/fluid/BasicFluidTank;", true);
            for (String type : new String[]{"mekanism/common/tile/prefab/TileEntityElectricMachine",
                    MACHINE + "TileEntityCombiner", MACHINE + "TileEntityChemicalCrystallizer", MACHINE + "TileEntityMetallurgicInfuser"}) {
                outputField(jar, type, "outputSlot", "L" + OUTPUT_SLOT + ";", false);
            }
            factoryCreatesRealOutputSlots(jar, "mekanism/common/tile/factory/TileEntityItemStackToItemStackFactory");
            factoryCreatesRealOutputSlots(jar, "mekanism/common/tile/factory/TileEntityItemStackChemicalToItemStackFactory");
        }
        System.out.println("MekOutputSpaceShapeTest: 14 native output storage mappings passed; capacity simulations are not exercised");
    }

    private static void outputField(JarFile jar, String owner, String name, String descriptor, boolean publicField) throws IOException {
        ClassNode type = read(jar, owner);
        check(type.fields.stream().anyMatch(field -> field.name.equals(name) && field.desc.equals(descriptor)
                        && (!publicField || (field.access & Opcodes.ACC_PUBLIC) != 0)),
                "missing native output field: " + owner + "." + name);
        boolean connected = false;
        for (var method : type.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
                        || !field.owner.equals(owner) || !field.name.equals(name) || !field.desc.equals(descriptor)) continue;
                int steps = 0;
                for (AbstractInsnNode next = field.getNext(); next != null && steps < 12; next = next.getNext()) {
                    if (next.getOpcode() >= 0) steps++;
                    if (next instanceof MethodInsnNode call) {
                        if (call.owner.equals(OUTPUT) && call.name.equals("getOutputHandler")) connected = true;
                        break;
                    }
                }
            }
        }
        check(connected, "storage field must feed a native OutputHelper handler: " + owner + "." + name);
    }

    private static void factoryCreatesRealOutputSlots(JarFile jar, String owner) throws IOException {
        boolean creates = false;
        ClassNode type = read(jar, owner);
        for (int depth = 0; depth < 4 && !creates; depth++) {
            boolean overrides = false, delegates = false;
            for (var method : type.methods) {
                if (!method.name.equals("addSlots")) continue;
                overrides = true;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call) {
                        if (call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(OUTPUT_SLOT) && call.name.equals("at")) creates = true;
                        if (call.getOpcode() == Opcodes.INVOKESPECIAL && call.owner.equals(type.superName) && call.name.equals("addSlots")) delegates = true;
                    }
                }
            }
            if (!creates && overrides && !delegates) break;
            if (!creates) type = read(jar, type.superName);
        }
        check(creates, "factory output capacity must use actual OutputInventorySlot instances: " + owner);
    }

    private static ClassNode read(JarFile jar, String name) throws IOException {
        var entry = jar.getJarEntry(name + ".class");
        check(entry != null, "missing native class: " + name);
        ClassNode result = new ClassNode();
        try (var stream = jar.getInputStream(entry)) { new ClassReader(stream).accept(result, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES); }
        return result;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
