package org.maiwithu.maicraft.core.task.reflex;

import org.maiwithu.maicraft.task.reflex.Reflex;
import org.maiwithu.maicraft.task.reflex.ReflexRegistry;
import org.maiwithu.maicraft.task.reflex.PolicyReflex;

import org.maiwithu.maicraft.core.task.chain.MLGChain;
import org.maiwithu.maicraft.core.task.chain.MobDefenseChain;
import org.maiwithu.maicraft.core.task.chain.BreathChain;
import org.maiwithu.maicraft.core.task.chain.NightRestChain;
import org.maiwithu.maicraft.core.task.chain.TorchLightingChain;

/**
 * 登记自动自救与日常休息的名字和说明：防摔、换气、自卫、夜间休息。
 * 这里创建的对象只用来列说明，不会开始控制玩家；真正每刻检查和执行的对象由 CompanionBrain 创建。
 */
public final class CoreReflexes {

    private CoreReflexes() {}

    public static void registerAll() {
        ReflexRegistry.register(new MLGChain());
        ReflexRegistry.register(new BreathChain());
        ReflexRegistry.register(new MobDefenseChain());
        ReflexRegistry.register(new NightRestChain());
        // 这里只登记说明；每个身体实例实际使用的补光链由 BrainChains 创建。
        ReflexRegistry.register(new TorchLightingChain());
    }
}
