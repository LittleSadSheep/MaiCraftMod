package org.maiwithu.maicraft.client.actor;

import java.util.function.Consumer;
import net.minecraft.client.player.LocalPlayer;

/** 显式启动选项只在首个可用身体上请求一次控制；后续人工撤回不会因该选项又被自动抢回。 */
public final class StartupAutomation {
    private boolean pending;
    public StartupAutomation(boolean enabled) { pending = enabled; }
    public boolean tick(LocalPlayer player, Consumer<LocalPlayer> request) {
        if (!pending || player == null || player.input == null || player.isDeadOrDying()) return false;
        request.accept(player); pending = false; return true;
    }
}
