// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.core.BlockPos;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;

/** Installation receipts and generated physical matrix structure; no optional mod or world needed. */
public final class MachineAssemblyTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var furnace = MachineInstallation.block("minecraft:furnace", Direction.EAST, null);
        check(furnace.state().getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.EAST, "registry-facing request retained");
        check(furnace.exactProperties().equals(Set.of("facing")), "facing explicitly verified after placement");
        expectFailure(() -> MachineInstallation.block("minecraft:stone", Direction.EAST, null));
        expectFailure(() -> MachineInstallation.block("minecraft:furnace", Direction.UP, null));
        expectFailure(() -> MachineInstallation.aePart("minecraft:stick", null));
        check(!MachineCommissioning.rotating(Double.NaN, true, false), "NaN cannot prove running");
        check(!MachineCommissioning.rotating(64, true, true), "overstress rejects rotation");
        check(!MachineCommissioning.rotating(64, false, false), "speed without network is insufficient");
        check(MachineCommissioning.rotating(-64, true, false), "negative rotation is valid");
        check(AePartTask.receiptVerdict(true, 0) == NativeConfirmation.Verdict.PENDING, "client ghost lacks item receipt");
        check(AePartTask.receiptVerdict(false, 1) == NativeConfirmation.Verdict.PENDING, "consumption alone does not prove placement");
        check(AePartTask.receiptVerdict(true, 2) == NativeConfirmation.Verdict.DIVERGED, "multiple consumption diverges");
        check(AePartTask.receiptVerdict(true, 1) == NativeConfirmation.Verdict.APPLIED, "part and server-only decrement agree");
        check(AePartTask.receiptVerdict(true, 0, true, false) == NativeConfirmation.Verdict.PENDING, "creative prediction cannot confirm itself");
        check(AePartTask.receiptVerdict(true, 0, true, true) == NativeConfirmation.Verdict.APPLIED, "creative placement needs a processed server BE packet");
        check(AePartTask.receiptVerdict(true, 1, true, true) == NativeConfirmation.Verdict.DIVERGED, "creative inventory must remain unchanged");
        Object[] before = new Object[7]; before[1] = new Object();
        Object[] after = before.clone(); after[0] = new Object();
        check(MachineInstallation.otherPartsUnchanged(before, after, null), "center insertion preserves peripheral part");
        check(!MachineInstallation.otherPartsUnchanged(before, after, Direction.DOWN), "another part slot changed");
        var cycle = new MekanismNativeConfiguration.Observation("output", List.of("none", "input", "output"));
        check(cycle.next().equals("none"), "native mode cycle wraps");
        expectFailure(() -> new MekanismNativeConfiguration.Observation("missing", List.of("input")));
        check(MachineCommissioning.kinetics(new KineticsFixture()).get("rotation_verified").getAsBoolean(), "public kinetic evidence is read");
        check(MachineCommissioning.kinetics(new Object()).get("status").getAsString().equals("unavailable"), "missing optional API never fabricates evidence");
        for (Direction face : Direction.values()) {
            var samples = MekanismInteractionGeometry.faceSamples(BlockPos.ZERO, face);
            check(samples.size() == 9, "machine faces include positions around their occupied centre");
            for (var point : samples) {
                double axis = switch (face.getAxis()) { case X -> point.x; case Y -> point.y; case Z -> point.z; };
                int sign = face.getStepX() + face.getStepY() + face.getStepZ();
                check(Math.abs(axis - (.5 + sign * .499)) < 1e-8, "all aim points remain on the intended face");
            }
        }
        JsonObject menu = new JsonObject(); JsonArray entries = new JsonArray();
        entries.add(entry("player", true, false, 10));
        entries.add(entry("machine", false, false, 50));
        entries.add(entry("machine", true, false, 2));
        entries.add(entry("machine", true, true, 0));
        menu.add("menu_entries", entries);
        check(MachineContentsTask.installedCount(menu, "ae2:item_storage_cell_1k") == 2,
                "carried items and virtual entries cannot satisfy installed contents");
        check(!MachineContentsTask.eligible(entries.get(0).getAsJsonObject()), "player inventory is never a machine destination");
        check(!MachineContentsTask.eligible(entries.get(2).getAsJsonObject()), "existing contents are never replaced");
        check(MachineContentsTask.eligible(entries.get(3).getAsJsonObject()), "empty physical machine slots remain candidates");
        serverReceiptScopes();

        var plan = MekanismMatrixTemplate.compile("basic", ignored -> true, ignored -> true);
        var blocks = plan.getAsJsonArray("blocks");
        check(blocks.size() == 36, "3x3x4 matrix fills its declared volume");
        int internal = 0, ports = 0, casing = 0;
        Set<String> positions = new HashSet<>();
        for (var element : blocks) {
            var block = element.getAsJsonObject();
            var offset = block.getAsJsonArray("offset");
            int x = offset.get(0).getAsInt(), y = offset.get(1).getAsInt(), z = offset.get(2).getAsInt();
            check(positions.add(x + "," + y + "," + z), "no overlapping matrix cells");
            String id = block.get("block_id").getAsString();
            boolean interior = x == 1 && y == 1 && z > 0 && z < 3;
            if (interior) {
                check(id.endsWith("induction_cell") || id.endsWith("induction_provider"), "interior contains valid storage/provider");
                internal++;
            } else if (id.endsWith("induction_port")) {
                check(x == 1 && y == 1 && (z == 0 || z == 3), "ports never occupy frame edges");
                ports++;
            } else { check(id.endsWith("induction_casing"), "complete casing shell"); casing++; }
        }
        check(internal == 2 && ports == 2 && casing == 32, "complete matrix bill");
        check(plan.getAsJsonArray("configurations").get(1).getAsJsonObject().get("mode").getAsString().equals("output"), "output mode is a native configuration action");
        expectFailure(() -> MekanismMatrixTemplate.compile("creative", ignored -> true, ignored -> true));
        expectFailure(() -> MekanismMatrixTemplate.compile("basic", id -> !id.endsWith("induction_provider"), ignored -> true));
        expectFailure(() -> MekanismMatrixTemplate.compile("basic", ignored -> true, id -> !id.endsWith("configurator")));
        var largeOptions = new com.google.gson.JsonObject();
        largeOptions.addProperty("width", 18); largeOptions.addProperty("height", 18); largeOptions.addProperty("depth", 18);
        largeOptions.addProperty("cell_count", 4000); largeOptions.addProperty("provider_count", 96);
        var large = MekanismMatrixTemplate.compile("ultimate", largeOptions, ignored -> true, ignored -> true);
        check(large.getAsJsonArray("blocks").size() == 5832, "Mekanism's actual maximum size is available");
        check(large.getAsJsonArray("construction_clearance").isEmpty(), "packed matrices retain their requested capacity");
        largeOptions.addProperty("provider_count", 97);
        expectFailure(() -> MekanismMatrixTemplate.compile("ultimate", largeOptions, ignored -> true, ignored -> true));
        JsonObject accessibleOptions = new JsonObject();
        accessibleOptions.addProperty("width", 4); accessibleOptions.addProperty("height", 4); accessibleOptions.addProperty("depth", 4);
        accessibleOptions.addProperty("cell_count", 4); accessibleOptions.addProperty("provider_count", 2);
        var accessible = MekanismMatrixTemplate.compile("basic", accessibleOptions, ignored -> true, ignored -> true);
        int placedCells = 0, placedProviders = 0;
        for (var element : accessible.getAsJsonArray("blocks")) {
            JsonObject block = element.getAsJsonObject(); String id = block.get("block_id").getAsString();
            if (id.endsWith("induction_cell")) placedCells++;
            if (id.endsWith("induction_provider")) placedProviders++;
            String position = block.get("offset").toString();
            if (position.equals("[1,1,1]") || position.equals("[1,2,1]")) check(id.equals("minecraft:air"), "entrance has two empty body cells");
        }
        check(placedCells == 4 && placedProviders == 2, "access clearance relocates materials without reducing requested counts");
        largeOptions.addProperty("width", 19);
        expectFailure(() -> MekanismMatrixTemplate.compile("ultimate", largeOptions, ignored -> true, ignored -> true));
        MekanismFilterTest.main(args);
        System.out.println("MachineAssemblyTest: passed");
    }
    public static final class KineticsFixture {
        public float getSpeed() { return 32; }
        public boolean hasNetwork() { return true; }
        public boolean isOverStressed() { return false; }
    }
    private static JsonObject entry(String side, boolean real, boolean empty, int count) {
        JsonObject row = new JsonObject(), stack = new JsonObject();
        row.addProperty("side", side); row.addProperty("transfer_supported", real); row.addProperty("active", true);
        stack.addProperty("empty", empty); stack.addProperty("item_id", "ae2:item_storage_cell_1k"); stack.addProperty("count", count);
        row.add("stack", stack); return row;
    }
    private static void serverReceiptScopes() {
        try {
            net.minecraft.client.multiplayer.ClientPacketListener.class.getDeclaredMethod("handleBlockEntityData",
                    net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.class);
            net.minecraft.client.multiplayer.ClientPacketListener.class.getDeclaredMethod("handleContainerContent",
                    net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket.class);
            check(net.minecraft.client.multiplayer.ClientPacketListener.class.getDeclaredField("level").getType()
                    == net.minecraft.client.multiplayer.ClientLevel.class, "packet mixin matches the installed Minecraft client API");
            var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
            var memory = (sun.misc.Unsafe) field.get(null);
            var first = (net.minecraft.client.multiplayer.ClientLevel) memory.allocateInstance(net.minecraft.client.multiplayer.ClientLevel.class);
            var second = (net.minecraft.client.multiplayer.ClientLevel) memory.allocateInstance(net.minecraft.client.multiplayer.ClientLevel.class);
            check(ServerBlockEntityReceipts.revision(first, BlockPos.ZERO) == 0, "unsynchronized geometry has no receipt");
            ServerBlockEntityReceipts.received(first, BlockPos.ZERO);
            long before = ServerBlockEntityReceipts.revision(first, BlockPos.ZERO);
            check(before > 0 && ServerBlockEntityReceipts.revision(second, BlockPos.ZERO) == 0, "server revisions do not cross client worlds");
            ServerBlockEntityReceipts.received(first, BlockPos.ZERO);
            check(ServerBlockEntityReceipts.revision(first, BlockPos.ZERO) > before, "only a later processed server update passes a captured baseline");
            var watch = ServerBlockEntityReceipts.watch(first, BlockPos.ZERO);
            check(!watch.advanced(), "old packets do not satisfy a new pending action");
            ServerBlockEntityReceipts.received(first, BlockPos.ZERO);
            for (int i = 1; i <= 4100; i++) ServerBlockEntityReceipts.received(first, new BlockPos(i, 0, 0));
            check(watch.advanced(), "busy factory updates do not evict a pending action's evidence");
            watch.close(); watch.close();
            check(!watch.advanced(), "cancelled watches cannot be reused");
        } catch (ReflectiveOperationException failure) { throw new AssertionError("receipt scope fixture", failure); }
    }
    private static void expectFailure(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("unsupported matrix silently accepted");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
