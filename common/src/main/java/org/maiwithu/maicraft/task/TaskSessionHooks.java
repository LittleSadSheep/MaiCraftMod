package org.maiwithu.maicraft.task;

import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 保留的工作结束通知列表。调度器在空闲、取消或失去身体时会触发它。
 * 当前仓库没有调用 onSessionEnd 登记监听，因此这些触发在项目自身的运行链里没有收尾动作。
 */
public final class TaskSessionHooks {

    private static final List<Consumer<LocalPlayer>> HOOKS = new ArrayList<>();

    private TaskSessionHooks() {}

    /** 启动时登记一项收尾动作，之后每次工作结束都会调用它。 */
    public static synchronized void onSessionEnd(Consumer<LocalPlayer> hook) {
        HOOKS.add(hook);
    }

    static void fireSessionEnd(LocalPlayer companion) {
        // 按登记顺序逐项执行；当前没有在这里捕获异常，某项抛异常会阻止后面的项运行。
        for (Consumer<LocalPlayer> h : HOOKS) {
            h.accept(companion);
        }
    }
}
