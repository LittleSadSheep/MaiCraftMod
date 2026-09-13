// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ultimine;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import org.lwjgl.glfw.GLFW;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;

/** 任务短时持有原生连锁键和菜单修饰键；不改变系统按键、模组配置或原生选区坐标。 */
public final class UltimineInputLease {
    private static Lease active;
    private static long nativePanelRevision = -1, renderedRevision = -1;
    private record Lease(Object owner, Minecraft minecraft, LocalPlayer player, ClientLevel level, BodyControlPort body,
                         long bodyEpoch, long controlRevision, KeyMapping key, long throughGameTick, long throughNanos, boolean menuModifier) {}
    private UltimineInputLease() {}
    public static boolean acquire(Object owner, LocalPlayerContext context) {
        expire();
        if (!eligible(context) || active != null && active.owner != owner) return false;
        KeyMapping key = UltimineNative.key();
        if (!supported(key)) return false;
        if (active == null && (key.isDown() || physicalDown(context.minecraft(), key))) return false;
        renew(owner, context, false); return true;
    }
    public static boolean renew(Object owner, LocalPlayerContext context, boolean menuModifier) {
        expire();
        if (!eligible(context) || active != null && (active.owner != owner || active.bodyEpoch != context.bodyEpoch()
                || active.controlRevision != context.controlRevision())) return false;
        KeyMapping key = UltimineNative.key();
        if (!supported(key)) return false;
        if (active == null && (key.isDown() || physicalDown(context.minecraft(), key))) return false;
        active = new Lease(owner, context.minecraft(), context.player(), context.level(), context.body(), context.bodyEpoch(),
                context.controlRevision(), key, context.level().getGameTime() + 2, System.nanoTime() + 250_000_000L, menuModifier);
        key.setDown(true); // 原生客户端 tick 读取此按键映射，再由 FTB 自己发送正常的按键状态。
        return true;
    }
    public static boolean heldBy(Object owner) { expire(); return active != null && active.owner == owner; }
    public static boolean humanHeld(LocalPlayerContext context) {
        KeyMapping key = UltimineNative.key(); return physicalDown(context.minecraft(), key) || active == null && key.isDown();
    }
    public static void release(Object owner) { if (active != null && active.owner == owner) release(); }
    private static void release() {
        Lease lease = active; active = null;
        // 任务结束只撤销自己的持键；玩家真实按住的键继续保留，避免自动操作抢走人手输入。
        if (lease != null) lease.key.setDown(physicalDown(lease.minecraft, lease.key));
    }
    /** 在 FTB 读取按键前清理过期持键，让原生按正常松键流程结束连锁。 */
    public static void beforeNativeTick() { expire(); }
    public static boolean menuModifier(boolean actual) {
        expire(); return actual || active != null && active.menuModifier;
    }
    public static void nativeTickFinished() {
        expire();
        if (active == null) return;
        try { var preview = UltimineNative.preview(active.player); nativePanelRevision = preview == null ? -1 : preview.revision(); }
        catch (RuntimeException unavailable) { release(); }
    }
    // 只有原生界面真正绘制且玩家未隐藏界面，才记为观众已经能看到本次预览。
    public static void hudRendered() { expire(); if (active != null && !active.minecraft.options.hideGui) renderedRevision = nativePanelRevision; }
    public static boolean previewRendered(long revision) {
        expire(); return active != null && !active.minecraft.options.hideGui && renderedRevision >= revision;
    }
    private static void expire() {
        // 切换身体、世界、打开界面或任务停止续租时及时松键，不能让后续普通挖掘意外保持连锁。
        Lease lease = active; if (lease == null) return;
        Minecraft minecraft = lease.minecraft;
        if (minecraft.player != lease.player || minecraft.level != lease.level || minecraft.screen != null
                || !lease.body.automationOwnsControls() || lease.level.getGameTime() > lease.throughGameTick
                || System.nanoTime() > lease.throughNanos) release();
    }
    private static boolean eligible(LocalPlayerContext context) {
        return context != null && context.isCurrent() && context.permitsNativeActions() && context.body().automationOwnsControls()
                && context.minecraft().screen == null && context.player() != null && context.level() != null;
    }
    private static boolean physicalDown(Minecraft minecraft, KeyMapping mapping) {
        if (minecraft.getWindow() == null) return false;
        InputConstants.Key key = InputConstants.getKey(mapping.saveString()); long window = minecraft.getWindow().getWindow();
        if (window == 0) return false;
        if (key.getType() == InputConstants.Type.KEYSYM && key.getValue() >= 0) return InputConstants.isKeyDown(window, key.getValue());
        if (key.getType() == InputConstants.Type.MOUSE && key.getValue() >= 0) return GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
        return false;
    }
    private static boolean supported(KeyMapping mapping) {
        InputConstants.Key key = InputConstants.getKey(mapping.saveString());
        return key.getValue() >= 0 && (key.getType() == InputConstants.Type.KEYSYM || key.getType() == InputConstants.Type.MOUSE);
    }
}
