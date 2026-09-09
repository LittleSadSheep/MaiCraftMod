package org.maiwithu.maicraft.core.task.reflex;

import org.maiwithu.maicraft.task.reflex.Reflex;
import org.maiwithu.maicraft.task.reflex.ReflexRegistry;
import org.maiwithu.maicraft.task.reflex.PolicyReflex;

import org.maiwithu.maicraft.core.task.chain.MLGChain;
import org.maiwithu.maicraft.core.task.chain.MobDefenseChain;

/**
 * 登记三种自动自救的名字和说明：防摔、换气、自卫。
 * 这里创建的对象只用来列说明，不会开始控制玩家；真正每刻检查和执行的对象由 CompanionBrain 创建。
 */
public final class CoreReflexes {

    private CoreReflexes() {}

    public static void registerAll() {
        ReflexRegistry.register(new MLGChain());
        ReflexRegistry.register(new org.maiwithu.maicraft.core.task.chain.BreathChain());
        ReflexRegistry.register(new MobDefenseChain());
    }
}
