package org.maiwithu.maicraft.task;

import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 让其他功能登记“这轮工作结束后要做什么”，例如解除手里必须拿着镐子的要求。
 * 调度器在取消任务、失去玩家对象或长时间没有任务时调用这里；具体收尾内容由登记者负责。
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
