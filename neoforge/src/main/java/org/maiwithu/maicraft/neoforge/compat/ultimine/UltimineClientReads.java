// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.ultimine;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.networking.NetworkManager;
import dev.ftb.mods.ftbultimine.FTBUltimine;
import dev.ftb.mods.ftbultimine.api.util.CanUltimineResult;
import dev.ftb.mods.ftbultimine.client.FTBUltimineClient;
import dev.ftb.mods.ftbultimine.config.FTBUltimineClientConfig;
import dev.ftb.mods.ftbultimine.net.KeyPressedPacket;
import dev.ftb.mods.ftbultimine.net.ModeChangedPacket;
import dev.ftb.mods.ftbultimine.shape.ShapeRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import org.lwjgl.glfw.GLFW;

import org.maiwithu.maicraft.compat.ultimine.UltimineControls;

/**
 * FTB Ultimine 2101.1.15 的读写端：连锁键是 FTB 客户端登记的那个按键，按下去 FTB 每刻开头自己发按键包；
 * 切形状发 FTB 自己的切形状包（和按住 Shift 滚轮发的是同一个）；选区读 FTB 客户端收到、画轮廓用的那一份。只翻译，不判断。
 *
 * <p>当前形状只记在 FTB 客户端的非公开字段 shapeIdx 里，FTB 没有公开读法：这是"不用反射"的例外（所有者 2026-10-13 批），
 * 字段在创建读写端时取一次，名字登记在事实清单，版本范围锁在 [2101.1.15, 2101.2)；取不到时创建出错，这个联动不登记。
 */
public final class UltimineClientReads implements UltimineControls {

    private final Field shapeIndex;
    /** 上一次看到的选区：FTB 每收到一次新选区换一个新对象，换了就算新到的一份。 */
    private Collection<BlockPos> lastSelection;
    private long revision;

    public UltimineClientReads() {
        try {
            shapeIndex = FTBUltimineClient.class.getDeclaredField("shapeIdx");
            shapeIndex.setAccessible(true);
        } catch (NoSuchFieldException missing) {
            throw new IllegalStateException("FTB Ultimine 客户端没有 shapeIdx 字段，装的版本和验证过的不一样", missing);
        }
    }

    @Override public boolean serverListens() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(KeyPressedPacket.TYPE)
                && connection.hasChannel(ModeChangedPacket.TYPE);
    }

    @Override public boolean heldByHand() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getWindow() == null) return false;
        long window = minecraft.getWindow().getWindow();
        InputConstants.Key key = InputConstants.getKey(FTBUltimineClient.keyBindUltimine.saveString());
        if (window == 0 || key.getValue() < 0) return false;
        if (key.getType() == InputConstants.Type.KEYSYM) return InputConstants.isKeyDown(window, key.getValue());
        if (key.getType() == InputConstants.Type.MOUSE) return GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
        return false;
    }

    @Override public void holdKey(boolean down) {
        // FTB 每刻开头看这个键：按下就发按键包、画面板；松开时人亲手按着的照旧按着，不抢人手。
        KeyMapping key = FTBUltimineClient.keyBindUltimine;
        key.setDown(down || heldByHand());
    }

    @Override public List<String> shapes() {
        List<String> names = new ArrayList<>();
        for (int index = 0; index < ShapeRegistry.INSTANCE.shapeCount(); index++) {
            names.add(ShapeRegistry.INSTANCE.getShape(index).getName().toString());
        }
        return names;
    }

    @Override public int currentShape() {
        try {
            return shapeIndex.getInt(FTBUltimineClient.getInstance());
        } catch (IllegalAccessException denied) {
            throw new IllegalStateException("读不到 FTB 客户端当前的形状", denied);
        }
    }

    @Override public void cycleShape(boolean next) {
        // FTB 的包登记在 Architectury 的网络层里、发出去要包一层：和 FTB 自己滚轮切形状一样经它的 NetworkManager 发。
        // 原先用 NeoForge 直接发，编码时类型对不上，客户端当场断线（实机 10-13 长程挖矿切"采矿通道"时掉线）。
        NetworkManager.sendToServer(new ModeChangedPacket(next));
    }

    @Override public Selection selection() {
        Collection<BlockPos> now = FTBUltimineClient.getInstance().getSelectedBlocks();
        if (now != lastSelection) {
            lastSelection = now;
            revision++;
        }
        return new Selection(revision, now == null ? List.of() : now.stream().map(BlockPos::immutable).toList());
    }

    @Override public int outlineLimit() {
        return FTBUltimineClientConfig.RENDER_OUTLINE.get();
    }

    @Override public Optional<String> refusal(LocalPlayer player, BlockPos pos) {
        if (player == null) return Optional.of("角色不在世界里");
        CanUltimineResult result = FTBUltimine.getInstance().canUltimine(player, pos, player.level().getBlockState(pos));
        return result.isAllowed() ? Optional.empty() : Optional.of(I18n.get(result.getTranslationKey()));
    }

    @Override public boolean hudHidden() {
        return Minecraft.getInstance().options.hideGui;
    }
}
