package org.maiwithu.maicraft.core.blueprint;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Corrupt inputs must fail before allocation/expansion; valid sparse palettes and mod names survive. */
public final class BlueprintImportTest {
    public static void main(String[] args) {
        expectFailure(() -> BlueprintFormats.checkedVolume(0, 1, 1));
        expectFailure(() -> BlueprintFormats.checkedVolume(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
        expectFailure(() -> BlueprintFormats.checkedVolume(Integer.MIN_VALUE, 1, 1));

        CompoundTag sparse = schem(1, Integer.MAX_VALUE, "example:chair",
                new byte[]{-1, -1, -1, -1, 7});
        CompoundTag converted = BlueprintFormats.fromSchem(sparse);
        if (converted.getList("blocks", Tag.TAG_COMPOUND).size() != 1
                || !converted.getList("palette", Tag.TAG_COMPOUND).getCompound(0).getString("Name").equals("example:chair")) {
            throw new AssertionError("Sparse palette ids or a mod block ending in air were discarded");
        }
        BlueprintFiles.validate(converted);

        expectFailure(() -> BlueprintFormats.fromSchem(schem(1, 0, "minecraft:stone", new byte[]{-128})));
        expectFailure(() -> BlueprintFormats.fromSchem(schem(1, 0, "minecraft:stone", new byte[]{-128, -128, -128, -128, -128, 0})));
        expectFailure(() -> BlueprintFormats.fromSchem(schem(1, 0, "minecraft:stone", new byte[]{-1, -1, -1, -1, 15})));
        expectFailure(() -> BlueprintFormats.fromSchem(schem(2, 0, "minecraft:stone", new byte[]{0})));
        expectFailure(() -> BlueprintFormats.fromSchem(schem(1, 0, "minecraft:stone", new byte[]{1})));
        expectFailure(() -> BlueprintFormats.fromSchem(schem(1, -1, "minecraft:stone", new byte[]{0})));

        CompoundTag regions = new CompoundTag();
        regions.put("one", region(256, 256, 256));
        regions.put("two", region(256, 256, 256));
        CompoundTag root = new CompoundTag();
        root.put("Regions", regions);
        expectFailure(() -> BlueprintFormats.fromLitematic(root));
        regions.remove("two");
        regions.put("one", region(Integer.MIN_VALUE, 1, 1));
        expectFailure(() -> BlueprintFormats.fromLitematic(root));
        regions.put("one", region(1, 1, 1)); // Missing BlockStates must not silently become air.
        expectFailure(() -> BlueprintFormats.fromLitematic(root));

        CompoundTag backwards = region(-2, 1, 1);
        backwards.putLongArray("BlockStates", new long[]{0});
        regions.put("one", backwards);
        CompoundTag backwardsConverted = BlueprintFormats.fromLitematic(root);
        BlueprintFiles.validate(backwardsConverted);
        if (backwardsConverted.getList("blocks", Tag.TAG_COMPOUND).size() != 2
                || backwardsConverted.getList("size", Tag.TAG_INT).getInt(0) != 2) {
            throw new AssertionError("A valid negative-size litematic region was not normalized");
        }

        expectFailure(() -> BlueprintFiles.resolve(Path.of("blueprints"), "../outside.nbt"));
        try (var input = BlueprintFiles.limited(new ByteArrayInputStream(new byte[6]), 5)) {
            try {
                input.readAllBytes();
                throw new AssertionError("Growing input bypassed the byte budget");
            } catch (IOException expected) { }
        } catch (IOException failure) { throw new AssertionError(failure); }
        System.out.println("BlueprintImportTest: bounded import regressions passed");
    }

    private static CompoundTag schem(int width, int id, String block, byte[] data) {
        CompoundTag tag = new CompoundTag();
        tag.putShort("Width", (short) width);
        tag.putShort("Height", (short) 1);
        tag.putShort("Length", (short) 1);
        CompoundTag palette = new CompoundTag();
        palette.putInt(block, id);
        tag.put("Palette", palette);
        tag.putByteArray("BlockData", data);
        return tag;
    }

    private static CompoundTag region(int x, int y, int z) {
        CompoundTag region = new CompoundTag();
        CompoundTag size = new CompoundTag();
        size.putInt("x", x);
        size.putInt("y", y);
        size.putInt("z", z);
        region.put("Size", size);
        region.put("Position", new CompoundTag());
        ListTag palette = new ListTag();
        CompoundTag state = new CompoundTag();
        state.putString("Name", "example:chair");
        palette.add(state);
        region.put("BlockStatePalette", palette);
        return region;
    }

    private static void expectFailure(Runnable operation) {
        try { operation.run(); }
        catch (IllegalArgumentException | ArithmeticException expected) { return; }
        throw new AssertionError("Malformed blueprint was accepted");
    }
}
