// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 读取 Mekanism 管道、机器接口和感应端口当前的模式，以及再点一次会切到哪个模式。
 * 也读取配置器的工作模式，并提供与原生滚轮切换相同的发包动作；机器设置本身仍由手持配置器右键完成。
 */
public final class MekanismNativeConfiguration {
    private MekanismNativeConfiguration() {}
    public record Observation(String current, List<String> cycle) {
        public Observation {
            cycle = List.copyOf(cycle);
            if (cycle.isEmpty() || !cycle.contains(current)) throw new IllegalArgumentException("current mode is outside installed mode cycle");
        }
        public String next() { return cycle.get((cycle.indexOf(current) + 1) % cycle.size()); }
    }

    // 管道按世界方向找接口；普通机器先把东南西北换算成相对机器正面的方向，再读取该面支持的模式。
    public static Observation inspect(Level level, BlockPos position, Direction face, String medium) {
        if (!level.isLoaded(position)) throw new IllegalArgumentException("configuration target is unloaded");
        Object entity = level.getBlockEntity(position);
        try {
            if (isTransmitter(level, position)) {
                Object transmitter = MachineCommissioning.call(entity, "getTransmitter");
                boolean supported = false;
                for (Object type : (Iterable<?>) MachineCommissioning.call(transmitter, "getSupportedTransmissionTypes")) {
                    supported |= ((Enum<?>) type).name().equalsIgnoreCase(normalizeMedium(medium));
                }
                if (!supported) throw new IllegalArgumentException("transmitter does not carry " + medium);
                Enum<?> current = (Enum<?>) transmitter.getClass().getMethod("getConnectionTypeRaw", Direction.class).invoke(transmitter, face);
                List<String> cycle = java.util.Arrays.stream(current.getDeclaringClass().getEnumConstants())
                        .map(value -> ((Enum<?>) value).name().toLowerCase(Locale.ROOT)).toList();
                return new Observation(current.name().toLowerCase(Locale.ROOT), cycle);
            }
            if (medium.equals("induction_port")) {
                Boolean output = MachineCommissioning.inductionOutput(entity);
                if (output == null) throw new IllegalArgumentException("target is not an observed induction port");
                return new Observation(output ? "output" : "input", List.of("input", "output"));
            }
            if (!Class.forName("mekanism.common.tile.interfaces.ISideConfiguration").isInstance(entity)) {
                throw new IllegalArgumentException("target has no supported Mekanism side configuration");
            }
            Object config = MachineCommissioning.call(entity, "getConfig");
            Object transmission = null;
            for (Object candidate : (Iterable<?>) MachineCommissioning.call(config, "getTransmissions")) {
                if (((Enum<?>) candidate).name().equalsIgnoreCase(normalizeMedium(medium))) transmission = candidate;
            }
            if (transmission == null) throw new IllegalArgumentException("target does not expose " + medium);
            Class<?> relative = Class.forName("mekanism.api.RelativeSide");
            Object facing = MachineCommissioning.call(entity, "getDirection");
            Object side = relative.getMethod("fromDirections", Direction.class, Direction.class).invoke(null, facing, face);
            Object info = config.getClass().getMethod("getConfig", transmission.getClass()).invoke(config, transmission);
            if (!(Boolean) info.getClass().getMethod("isSideEnabled", relative).invoke(info, side)) {
                throw new IllegalArgumentException("the installed machine disables this side");
            }
            Enum<?> current = (Enum<?>) info.getClass().getMethod("getDataType", relative).invoke(info, side);
            List<Enum<?>> supported = new ArrayList<>();
            for (Object value : (Iterable<?>) MachineCommissioning.call(info, "getSupportedDataTypes")) supported.add((Enum<?>) value);
            supported.sort(Comparator.comparingInt(Enum::ordinal));
            return new Observation(current.name().toLowerCase(Locale.ROOT), supported.stream()
                    .map(value -> value.name().toLowerCase(Locale.ROOT)).toList());
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalArgumentException("installed Mekanism configuration API unavailable", failure);
        }
    }

    // 必须手持配置器并处于配置模式；配能量口、流体口等还要选对介质，感应端口只要求是配置模式。
    public static boolean toolReady(ItemStack stack, String medium) {
        try {
            Class<?> item = Class.forName("mekanism.common.item.ItemConfigurator");
            if (!item.isInstance(stack.getItem())) return false;
            Object mode = item.getMethod("getMode", ItemStack.class).invoke(stack.getItem(), stack);
            if (!(Boolean) MachineCommissioning.call(mode, "isConfigurating")) return false;
            if (medium.equals("induction_port")) return true;
            Object transmission = MachineCommissioning.call(mode, "getTransmission");
            return transmission instanceof Enum<?> value && value.name().equalsIgnoreCase(normalizeMedium(medium));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) { return false; }
    }

    public static boolean isTransmitter(Level level, BlockPos position) {
        if (!level.isLoaded(position)) return false;
        try { return Class.forName("mekanism.common.tile.transmitter.TileEntityTransmitter").isInstance(level.getBlockEntity(position)); }
        catch (ClassNotFoundException | LinkageError failure) { return false; }
    }

    /**
     * 普通机器核对点中了指定面；管道还要让 Mekanism 读取实际准星选中的分支，外侧碰撞面的方向不足以确定是哪段管道。
     */
    public static boolean hitMatches(LocalPlayer player, BlockPos target, Direction desiredFace, BlockHitResult hit) {
        if (!hit.getBlockPos().equals(target)) return false;
        if (!isTransmitter(player.level(), target)) return hit.getDirection() == desiredFace;
        try {
            Object entity = player.level().getBlockEntity(target);
            Object segment = entity.getClass().getMethod("getSideLookingAt", net.minecraft.world.entity.player.Player.class, Direction.class)
                    .invoke(entity, player, hit.getDirection());
            return segment == desiredFace;
        } catch (ReflectiveOperationException | RuntimeException failure) { return false; }
    }

    public static String toolMode(ItemStack stack) {
        try {
            Class<?> item = Class.forName("mekanism.common.item.ItemConfigurator");
            if (!item.isInstance(stack.getItem())) throw new IllegalArgumentException("held item is not a configurator");
            Enum<?> mode = (Enum<?>) item.getMethod("getMode", ItemStack.class).invoke(stack.getItem(), stack);
            return mode.name();
        } catch (ReflectiveOperationException | LinkageError failure) { throw new IllegalArgumentException("configurator mode unavailable", failure); }
    }

    // 从已安装版本的枚举顺序预测工具的下一模式，不直接修改手持物上的设置。
    public static String nextToolMode(ItemStack stack) {
        try {
            Class<?> item = Class.forName("mekanism.common.item.ItemConfigurator");
            Enum<?> mode = (Enum<?>) item.getMethod("getMode", ItemStack.class).invoke(stack.getItem(), stack);
            Object[] modes = mode.getDeclaringClass().getEnumConstants();
            return ((Enum<?>) modes[(mode.ordinal() + 1) % modes.length]).name();
        } catch (ReflectiveOperationException | LinkageError failure) { throw new IllegalArgumentException("configurator mode cycle unavailable", failure); }
    }

    /**
     * 发送 Mekanism 原生的“主手工具模式前进一步”请求。调用者必须先取得动作额度，发送后等待服务器同步，不能直接认定切换成功。
     */
    public static void advanceToolMode(LocalPlayer player) {
        toolMode(player.getMainHandItem()); // Bind the native mode operation to a real held configurator.
        try {
            Object packet = Class.forName("mekanism.common.network.to_server.PacketModeChange")
                    .getConstructor(EquipmentSlot.class, int.class).newInstance(EquipmentSlot.MAINHAND, 1);
            Class.forName("mekanism.common.network.PacketUtils").getMethod("sendToServer", CustomPacketPayload.class)
                    .invoke(null, packet);
        } catch (ReflectiveOperationException | LinkageError failure) { throw new IllegalArgumentException("native configurator mode protocol unavailable", failure); }
    }

    private static String normalizeMedium(String medium) {
        return switch (medium) { case "items" -> "item"; case "fluids" -> "fluid"; case "chemicals" -> "chemical"; default -> medium; };
    }
}
