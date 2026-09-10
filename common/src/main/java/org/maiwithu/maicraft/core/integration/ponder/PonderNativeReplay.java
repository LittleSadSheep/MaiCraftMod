// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ponder;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * 为结构提取建立独立的演示世界，放入教程自带结构，再推进真实 Ponder 日程并读取状态；需要客户端世界已经存在。
 */
final class PonderNativeReplay implements PonderReplaySession.Driver {
    private final Object scene;
    private final PonderSnapshotReader reader;
    private final java.lang.reflect.Method tick, skipping;
    private final Class<?> elementType;
    private int elapsed;

    static PonderReplaySession create(PonderAccess.Entry entry, ReflectivePonderAccess.Api api) throws ReflectiveOperationException {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) throw new IllegalStateException("Enter a client world to extract Ponder structures; narration is available without a world");
        if (!minecraft.isSameThread()) throw new IllegalStateException("Ponder replay must start on the client thread");
        StructureTemplate template = loadTemplate(minecraft, entry.schematic());
        var size = template.getSize();
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0 || (long) size.getX() * size.getY() * size.getZ() > 65536)
            throw new IllegalStateException("Missing, empty or oversized Ponder schematic (maximum 65536 cells)");
        Object nativeWorld = api.level().getConstructor(BlockPos.class, Level.class).newInstance(BlockPos.ZERO, minecraft.level);
        if (!(nativeWorld instanceof Level world) || world == minecraft.level) throw new IllegalStateException("Ponder did not create a separate Level");
        template.placeInWorld((net.minecraft.world.level.ServerLevelAccessor) world, BlockPos.ZERO, BlockPos.ZERO,
                new StructurePlaceSettings(), world.random, 2);
        api.level().getMethod("createBackup").invoke(world);
        Object localization = api.localization().getConstructor().newInstance();
        Object scene = api.registry().getMethod("compileScene", api.localization(), api.story(), api.level())
                .invoke(null, localization, entry.nativeEntry(), world);
        PonderTranscript transcript = PonderInstructionReader.read(scene, ReflectivePonderAccess.defaults(api, localization));
        scene.getClass().getMethod("begin").invoke(scene);
        return new PonderReplaySession(entry, transcript, new PonderNativeReplay(scene, world));
    }

    private static StructureTemplate loadTemplate(Minecraft minecraft, String schematic) {
        ResourceLocation id = ResourceLocation.parse(schematic);
        var location = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "ponder/" + id.getPath() + ".nbt");
        var resource = minecraft.getResourceManager().getResource(location)
                .orElseThrow(() -> new IllegalStateException("Missing Ponder schematic " + location));
        try (var stream = resource.open()) {
            byte[] compressed = stream.readNBytes(4 * 1024 * 1024 + 1);
            if (compressed.length > 4 * 1024 * 1024) throw new IllegalStateException("Compressed Ponder schematic exceeds 4 MiB");
            try (var input = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(compressed)))) {
                var tag = NbtIo.read(input, NbtAccounter.create(16 * 1024 * 1024));
                StructureTemplate template = new StructureTemplate(); template.load(BuiltInRegistries.BLOCK.asLookup(), tag); return template;
            }
        } catch (IOException failure) { throw new IllegalStateException("Cannot load Ponder schematic " + location, failure); }
    }

    private PonderNativeReplay(Object scene, Level world) throws ReflectiveOperationException {
        this.scene = scene; reader = new PonderSnapshotReader(scene, world);
        tick = scene.getClass().getMethod("tick");
        elementType = Class.forName("net.createmod.ponder.api.element.PonderElement", true, scene.getClass().getClassLoader());
        skipping = elementType.getMethod("whileSkipping", scene.getClass());
    }

    @Override public void tick() throws ReflectiveOperationException {
        // The same pre-tick hook Ponder seekToTime uses to refresh section block entities without a render pass.
        for (Object element : (Iterable<?>) scene.getClass().getMethod("getElements").invoke(scene)) skipping.invoke(element, scene);
        tick.invoke(scene); elapsed++;
    }
    @Override public boolean finished() throws ReflectiveOperationException {
        // markAsFinished can occur before later tutorial instructions. Drain the real schedule to retain every chapter.
        return ((List<?>) PonderInstructionReader.field(scene, "activeSchedule")).isEmpty();
    }
    @Override public int time() { return elapsed; }
    @Override public List<Integer> keyframes() throws ReflectiveOperationException {
        int count = (int) scene.getClass().getMethod("getKeyframeCount").invoke(scene); List<Integer> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add((int) scene.getClass().getMethod("getKeyframeTime", int.class).invoke(scene, i));
        return result;
    }
    @Override public PonderStructureSnapshot snapshot() throws ReflectiveOperationException { return reader.read(); }
}
