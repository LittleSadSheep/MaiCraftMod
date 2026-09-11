// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.connectivity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerProductionEvents;
import org.maiwithu.maicraft.server.machine.ProductionEventJournal.OrderingMarker;

/** Records only returned, real forward insertions at a logistical transporter's final hop. */
public final class MekTransportCapture {
    private static final String TRANSMITTER = "mekanism.common.content.network.transmitter.Transmitter";
    private static final String STACK = "mekanism.common.content.transporter.TransporterStack";
    private static final String RESPONSE = "mekanism.common.lib.inventory.TransitRequest$TransitResponse";
    private static final ThreadLocal<Frame> PENDING = new ThreadLocal<>();

    public record Frame(Object transmitter, Object transported, ServerLevel level, BlockPos source, BlockPos destination,
                        BlockPos lastTransmitter, ItemStack before, OrderingMarker extraction) {}

    private MekTransportCapture() {}

    /** Called at the itemStack read immediately before the native delivery request is constructed. */
    public static void prepare(Object transmitter, Object transported, ItemStack input) {
        PENDING.remove();
        try {
            Object rawLevel = NativeApi.call(transmitter, TRANSMITTER, "getLevel");
            if (!(rawLevel instanceof ServerLevel level) || !level.getServer().isSameThread() || input.isEmpty()) return;
            Object path = NativeApi.call(transported, STACK, "getPathType");
            if (!(path instanceof Enum<?> type) || !type.name().equals("DEST")) return;
            long destination = NativeApi.number(NativeApi.call(transported, STACK, "getDest"));
            MekTransportOrigins.Origin origin = MekTransportOrigins.origin(transported, level);
            if (origin == null || origin.extraction() == null || destination == Long.MAX_VALUE) return;
            BlockPos from = origin.source(), to = BlockPos.of(destination);
            BlockPos last = (BlockPos) NativeApi.call(transmitter, TRANSMITTER, "getBlockPos");
            if (from.equals(to) || ServerConnectionInspection.direction(last, to) == null) return;
            // homeLocation can be the sorter instead of the inventory. Only observed extraction establishes source.
            if (input.getCount() > origin.remaining() || !ItemStack.isSameItemSameComponents(input, origin.identity())) return;
            PENDING.set(new Frame(transmitter, transported, level, from, to, last, input.copy(), origin.extraction()));
        } catch (RuntimeException | LinkageError unsupported) {
            // Instrumentation must never prevent or change the native inventory operation.
        }
    }

    /** Consume before invoking native code, so exceptions and reentrant updates cannot reuse a frame. */
    public static Frame take(Object transmitter) {
        Frame frame = PENDING.get();
        PENDING.remove();
        return frame != null && frame.transmitter() == transmitter ? frame : null;
    }

    public static void finish(Frame frame, Level rawLevel, BlockPos destination, boolean forceHome, Object response) {
        if (frame == null || forceHome || frame.level() != rawLevel || !frame.destination().equals(destination)) return;
        try {
            if (!frame.level().getServer().isSameThread() || response == null
                    || NativeApi.truth(NativeApi.call(response, RESPONSE, "isEmpty"))) return;
            ItemStack inserted = ((ItemStack) NativeApi.call(response, RESPONSE, "getStack")).copy();
            long amount = NativeApi.number(NativeApi.call(response, RESPONSE, "getSendingAmount"));
            if (inserted.isEmpty() || amount <= 0 || amount != inserted.getCount() || amount > frame.before().getCount()
                    || !ItemStack.isSameItemSameComponents(frame.before(), inserted)) return;
            if (!MekTransportOrigins.consume(frame.transported(), frame.level(), inserted)) return;
            // The response comes from insertItem(..., false), after successful native return.
            // This proves accepted source-attributed delivery, not the full route taken or later production.
            ServerProductionEvents.recordTransfer(frame.level(), frame.source(), destination,
                    ResourceIdentity.item(inserted, frame.level().registryAccess()), amount,
                    "mekanism.transporter.native_forward_delivery", frame.extraction());
        } catch (RuntimeException | LinkageError unsupported) {
            // A failed observer may lose evidence; it must not cancel a completed native delivery.
        }
    }
}
