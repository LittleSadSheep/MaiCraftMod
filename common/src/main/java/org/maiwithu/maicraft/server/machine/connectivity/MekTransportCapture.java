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

/** 仅记录物流运输器最终一跳中成功返回的真实正向插入。 */
public final class MekTransportCapture {
    private static final String TRANSMITTER = "mekanism.common.content.network.transmitter.Transmitter";
    private static final String STACK = "mekanism.common.content.transporter.TransporterStack";
    private static final String RESPONSE = "mekanism.common.lib.inventory.TransitRequest$TransitResponse";
    private static final ThreadLocal<Frame> PENDING = new ThreadLocal<>();

    public record Frame(Object transmitter, Object transported, ServerLevel level, BlockPos source, BlockPos destination,
                        BlockPos lastTransmitter, ItemStack before, OrderingMarker extraction) {}

    private MekTransportCapture() {}

    /** 在读取 itemStack 后、构建原生交付请求前立即调用。 */
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
            // homeLocation 可能指向分拣器而非库存；只有观察到实际提取才能确定来源。
            if (input.getCount() > origin.remaining() || !ItemStack.isSameItemSameComponents(input, origin.identity())) return;
            PENDING.set(new Frame(transmitter, transported, level, from, to, last, input.copy(), origin.extraction()));
        } catch (RuntimeException | LinkageError unsupported) {
            // 监测逻辑绝不能阻止或改变原生库存操作。
        }
    }

    /** 调用原生代码前先消费帧，避免异常或重入更新重复使用同一帧。 */
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
            // 响应来自成功返回后的 insertItem(..., false)。这只能证明来源可归属的物品已被接收，不能证明完整路线或后续生产结果。
            ServerProductionEvents.recordTransfer(frame.level(), frame.source(), destination,
                    ResourceIdentity.item(inserted, frame.level().registryAccess()), amount,
                    "mekanism.transporter.native_forward_delivery", frame.extraction());
        } catch (RuntimeException | LinkageError unsupported) {
            // 观察器失败可能丢失证据，但不能取消已经完成的原生交付。
        }
    }
}
