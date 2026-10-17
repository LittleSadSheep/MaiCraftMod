// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import com.mojang.blaze3d.platform.Window;
import java.io.IOException;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.maiwithu.maicraft.behavior.navigation.debug.NavigationPathSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * F9 调试面板：每刻读按键、切档，取一份现状快照交给排版，留下排好的行给渲染。
 *
 * <p>只读：手里只有现状快照的读口和路线的读口，拿不到任何能改状态的对象；取快照或排版出错时只显示一行
 * "面板读取出错"，同一种错只记一次日志，不往外抛，不影响这一刻角色的其他工作。
 *
 * <p>按键：单按 F9 在关、简要、详细三档间循环（松开 F9 时才切，按住 F9 再按字母只算组合键）；
 * F9+H 在"此刻"和"最近的目标"之间切换，面板关着或在简要档时直接打开详细档的"最近的目标"；
 * F9+N 开关世界里的导航路线；F9+B 开关施工预览（design 投影的蓝图）。和 F8 一样直接读窗口按键，容器界面打开时也能切。
 */
public final class DebugPanel {
    private static final Logger LOG = LoggerFactory.getLogger(DebugPanel.class);

    private final StatusReader reader;
    private final Supplier<Optional<NavigationPathSnapshot>> currentPath;
    private final BlueprintOverlay overlay;
    private final PanelSettings settings;
    private final PanelLayout layout;
    private final FirstSeenTimes times = new FirstSeenTimes();
    private PanelPage page = PanelPage.NOW;
    private List<PanelLine> lines = List.of();
    private int width;
    private NavigationPathSnapshot path;
    private final PanelKeys keys = new PanelKeys();
    /** 上次出错的异常类型；同一种错只记一次日志。 */
    private Class<?> lastFailure;

    /**
     * @param reader      取现状快照
     * @param currentPath 角色正在走的路线；没在走时为空
     * @param overlay     施工预览：design 投来的蓝图
     * @param settings    档位、路线开关与预览开关
     */
    public DebugPanel(StatusReader reader, Supplier<Optional<NavigationPathSnapshot>> currentPath,
                      BlueprintOverlay overlay, PanelSettings settings) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.currentPath = Objects.requireNonNull(currentPath, "currentPath");
        this.overlay = Objects.requireNonNull(overlay, "overlay");
        this.settings = Objects.requireNonNull(settings, "settings");
        // 量字宽用游戏的字体；排版只在客户端刻里做，那时字体早已加载好。
        this.layout = new PanelLayout(text -> Minecraft.getInstance().font.width(text), ZoneId.systemDefault());
    }

    /** 每个客户端刻结束时调用：读按键，取现状（面板关着也取，"已等多久"才从状态出现那一刻算起），排版。 */
    public void tick(Minecraft minecraft) {
        pollKeys(minecraft);
        Window window = minecraft.getWindow();
        PanelLevel level = settings.level();
        width = width(level, window.getGuiScaledWidth());
        int maxLines = Math.max(4, window.getGuiScaledHeight() * 60 / 100 / PanelRenderer.lineHeight());
        try {
            StatusSnapshot snapshot = reader.read(minecraft);
            long now = System.currentTimeMillis();
            times.observe(snapshot, now);
            lines = layout.lay(snapshot, times, now, level, page, width, maxLines);
            path = settings.pathLines() ? currentPath.get().orElse(null) : null;
            lastFailure = null;
        } catch (RuntimeException failure) {
            if (failure.getClass() != lastFailure) LOG.warn("调试面板读取现状出错", failure);
            lastFailure = failure.getClass();
            lines = level == PanelLevel.OFF ? List.of() : layout.failure(failure, width);
            path = null;
        }
    }

    /** 原版 HUD 层画完后调用；容器界面打开时再由界面层调用一次，调试界面流程时也看得到。 */
    public void renderHud(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        // 按 F1 隐藏界面时不画；原版 F3 调试屏也占左上角，打开时让位。
        if (minecraft.player == null || minecraft.options.hideGui || minecraft.getDebugOverlay().showDebugScreen()) return;
        PanelRenderer.draw(graphics, minecraft.font, lines, width);
    }

    /** 世界画完后调用：路线开关开着时画正在走的路线；预览开关开着时画投到这个世界的蓝图。 */
    public void renderWorld(Camera camera, Matrix4f view, Matrix4f projection) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.options.hideGui) return;
        if (path != null) NavigationPathRenderer.render(camera, view, projection, path);
        if (!settings.blueprintOverlay()) return;
        // 蓝图带着维度：投在主世界的不画到下界里。
        String dimension = minecraft.level.dimension().location().toString();
        overlay.current().filter(blueprint -> blueprint.dimension().equals(dimension))
                .ifPresent(blueprint -> BlueprintRenderer.render(camera, view, projection, blueprint));
    }

    // 面板宽度只随窗口与界面缩放变，不随内容变：简要档取三成宽，详细档取四成半，都有上下限。
    static int width(PanelLevel level, int guiWidth) {
        int wanted = level == PanelLevel.FULL
                ? Math.clamp(guiWidth * 45L / 100, 300, 420)
                : Math.clamp(guiWidth * 30L / 100, 200, 280);
        return Math.max(120, Math.min(wanted, guiWidth - 12));
    }

    // 直接读窗口按键：界面打开时键盘事件进不了按键绑定，而调试容器界面的流程正需要这时候切面板。
    private void pollKeys(Minecraft minecraft) {
        long window = minecraft.getWindow().getWindow();
        List<PanelKeys.Press> presses = keys.poll(
                GLFW.glfwGetKey(window, GLFW.GLFW_KEY_F9) == GLFW.GLFW_PRESS,
                GLFW.glfwGetKey(window, GLFW.GLFW_KEY_H) == GLFW.GLFW_PRESS,
                GLFW.glfwGetKey(window, GLFW.GLFW_KEY_N) == GLFW.GLFW_PRESS,
                GLFW.glfwGetKey(window, GLFW.GLFW_KEY_B) == GLFW.GLFW_PRESS);
        for (PanelKeys.Press press : presses) {
            switch (press) {
                case NEXT_LEVEL -> cycleLevel(minecraft);
                case SWITCH_PAGE -> switchPage(minecraft);
                case TOGGLE_PATH_LINES -> togglePathLines(minecraft);
                case TOGGLE_BLUEPRINT -> toggleBlueprint(minecraft);
            }
        }
    }

    private void cycleLevel(Minecraft minecraft) {
        PanelLevel next = settings.level().next();
        page = PanelPage.NOW;
        String hint = switch (next) {
            case OFF -> "调试面板：关";
            case BRIEF -> "调试面板：简要（再按 F9 看详细）";
            case FULL -> "调试面板：详细（再按 F9 关闭，F9+H 看最近的目标）";
        };
        saveLevel(minecraft, next, hint);
    }

    // F9+H：在详细档里来回切页；面板关着或在简要档时一步打开详细档的"最近的目标"。
    private void switchPage(Minecraft minecraft) {
        if (settings.level() != PanelLevel.FULL) {
            page = PanelPage.RECENT_GOALS;
            saveLevel(minecraft, PanelLevel.FULL, "调试面板：最近的目标（F9+H 返回）");
            return;
        }
        page = page.other();
        say(minecraft, page == PanelPage.NOW ? "调试面板：此刻" : "调试面板：最近的目标（F9+H 返回）", ChatFormatting.GREEN);
    }

    private void togglePathLines(Minecraft minecraft) {
        boolean show = !settings.pathLines();
        try {
            settings.pathLines(show);
            say(minecraft, show ? "导航路线：显示（再按 F9+N 隐藏）" : "导航路线：隐藏", ChatFormatting.GREEN);
        } catch (IOException failure) {
            say(minecraft, "导航路线已" + (show ? "显示" : "隐藏") + "，但设置没存下：" + failure.getMessage(),
                    ChatFormatting.YELLOW);
        }
    }

    private void toggleBlueprint(Minecraft minecraft) {
        boolean show = !settings.blueprintOverlay();
        try {
            settings.blueprintOverlay(show);
            say(minecraft, show ? "施工预览：显示（再按 F9+B 隐藏）" : "施工预览：隐藏", ChatFormatting.GREEN);
        } catch (IOException failure) {
            say(minecraft, "施工预览已" + (show ? "显示" : "隐藏") + "，但设置没存下：" + failure.getMessage(), ChatFormatting.YELLOW);
        }
    }

    private void saveLevel(Minecraft minecraft, PanelLevel level, String hint) {
        try {
            settings.level(level);
            say(minecraft, hint, ChatFormatting.GREEN);
        } catch (IOException failure) {
            say(minecraft, hint + "，但设置没存下：" + failure.getMessage(), ChatFormatting.YELLOW);
        }
    }

    // 切换结果写在动作栏，不进聊天栏，也不进给 LLM 的聊天事件。
    private static void say(Minecraft minecraft, String text, ChatFormatting color) {
        if (minecraft.player != null) minecraft.player.displayClientMessage(Component.literal(text).withStyle(color), true);
    }
}
