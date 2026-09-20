// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ultimine;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.server.machine.NativeApi;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/** 读取 FTB Ultimine 2101.1.15 的原生状态并复用原生滚轮入口；启用键仍由 FTB 自己的 tick 处理。 */
public final class UltimineNative {
    private static final String CLIENT = "dev.ftb.mods.ftbultimine.client.FTBUltimineClient";
    private static final String SHAPES = "dev.ftb.mods.ftbultimine.shape.ShapeRegistry";
    private static final String SHAPE = "dev.ftb.mods.ftbultimine.api.shape.Shape";
    private static Object observedClient, observedSelection;
    private static long revision;
    private UltimineNative() {}
    public static boolean available() {
        if (!NativeApi.present(CLIENT)) return false;
        try {
            Class<?> type = NativeApi.type(CLIENT);
            if (type.getDeclaredField("pressed").getType() != boolean.class || type.getDeclaredField("actualBlocks").getType() != int.class
                    || type.getDeclaredField("shapeIdx").getType() != int.class) return false;
            type.getDeclaredField("canUltimineStatus");
            return NativeApi.call(null, CLIENT, "getInstance") != null && key() != null;
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
    }
    public static KeyMapping key() { return (KeyMapping) NativeApi.constant(CLIENT, "keyBindUltimine"); }
    public static boolean correctTool(LocalPlayer player, BlockPos position, BlockState state) {
        return NativeApi.truth(NativeApi.call(null, "dev.ftb.mods.ftbultimine.utils.PlatformUtil", "playerHasCorrectTool", player, position, state));
    }
    public static boolean pressed() {
        Object client = NativeApi.call(null, CLIENT, "getInstance"); return client != null && (Boolean) read(client, "pressed");
    }
    public static boolean serverAvailable() {
        // 客户端能找到模组不代表服务器支持连锁，须以原生通信通道是否可用决定能否启用。
        try {
            return NativeApi.truth(NativeApi.call(null, "dev.architectury.networking.NetworkManager", "canServerReceive",
                    ResourceLocation.parse("ftbultimine:key_pressed_packet")))
                    && NativeApi.truth(NativeApi.call(null, "dev.architectury.networking.NetworkManager", "canServerReceive",
                    ResourceLocation.parse("ftbultimine:mode_changed_packet")));
        } catch (RuntimeException unavailable) { return false; }
    }
    public static int shapeCount() { return ((Number) NativeApi.call(NativeApi.constant(SHAPES, "INSTANCE"), SHAPES, "shapeCount")).intValue(); }
    public static int squareIndex() {
        int count = shapeCount(); if (count < 1 || count > 64) return -1;
        Object registry = NativeApi.constant(SHAPES, "INSTANCE");
        for (int i = 0; i < count; i++) {
            Object shape = NativeApi.call(registry, SHAPES, "getShape", i);
            if (shape.getClass().getName().equals(UltimineSelectionPolicy.SQUARE_CLASS)
                    && NativeApi.call(shape, SHAPE, "getName").toString().equals(UltimineSelectionPolicy.SQUARE)) return i;
        }
        return -1;
    }
    public static void scrollShape(Minecraft minecraft, boolean next) {
        Object client = NativeApi.call(null, CLIENT, "getInstance");
        // 调用原本注册的滚轮处理：菜单条件、形状变化和正常消息都由 FTB 执行，不直接改选区。
        NativeApi.call(client, CLIENT, "onMouseScrolled", minecraft, 0d, next ? -1d : 1d);
    }
    public static UltimineSelectionPolicy.Preview preview(LocalPlayer player) {
        // 同时读取实际选中数量和用于绘制的坐标，后续才能识别被显示上限截短的不完整预览。
        Object client = NativeApi.call(null, CLIENT, "getInstance");
        if (client == null) return null;
        Object collection = NativeApi.call(client, CLIENT, "getSelectedBlocks");
        if (client != observedClient || collection != observedSelection) {
            observedClient = client; observedSelection = collection; revision++;
        }
        int count = ((Number) read(client, "actualBlocks")).intValue(), index = ((Number) read(client, "shapeIdx")).intValue();
        if (count < 0 || count > 32768) throw new IllegalArgumentException("ultimine_native_selection_budget_exceeded");
        List<BlockPos> blocks;
        if (collection == null) blocks = List.of();
        else if (collection instanceof Collection<?> values && values.size() <= 32768) {
            if (values.stream().anyMatch(value -> !(value instanceof BlockPos))) throw new IllegalArgumentException("ultimine_invalid_native_positions");
            blocks = values.stream().map(value -> ((BlockPos) value).immutable()).toList();
        } else throw new IllegalArgumentException("ultimine_invalid_native_selection");
        Object registry = NativeApi.constant(SHAPES, "INSTANCE"), shape = NativeApi.call(registry, SHAPES, "getShape", index);
        Object status = read(client, "canUltimineStatus");
        boolean allowed = status != null && NativeApi.truth(NativeApi.call(status, "dev.ftb.mods.ftbultimine.api.util.CanUltimineResult", "isAllowed"));
        String reason = status == null ? "native_status_pending" : NativeApi.call(status, "dev.ftb.mods.ftbultimine.api.util.CanUltimineResult", "getTranslationKey").toString();
        return new UltimineSelectionPolicy.Preview(NativeApi.call(shape, SHAPE, "getName").toString(), shape.getClass().getName(), index,
                count, blocks, NativeApi.truth(read(client, "pressed")), allowed, reason, revision);
    }
    private static Object read(Object object, String name) {
        try { Field field = NativeApi.type(CLIENT).getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (ReflectiveOperationException | RuntimeException unavailable) { throw new IllegalArgumentException("ultimine_native_read_unavailable: " + name, unavailable); }
    }
}
