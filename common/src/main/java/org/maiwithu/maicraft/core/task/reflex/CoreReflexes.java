package org.maiwithu.maicraft.core.task.reflex;

import org.maiwithu.maicraft.task.reflex.Reflex;
import org.maiwithu.maicraft.task.reflex.ReflexRegistry;
import org.maiwithu.maicraft.task.reflex.PolicyReflex;

import org.maiwithu.maicraft.core.task.chain.MLGChain;
import org.maiwithu.maicraft.core.task.chain.MobDefenseChain;
import org.maiwithu.maicraft.core.task.chain.UnstuckChain;

/**
 * maicraft-core's reflex roster: the four survival chains (which implement
 * {@link Reflex} themselves — chain shape untouched) plus one pure policy,
 * registered once at {@code MaiCraftCore.init}. The chain instances enlisted here
 * are roster representatives only (id/describe are constants); the live,
 * per-companion chain instances stay inside each {@code CompanionBrain}.
 */
public final class CoreReflexes {

    private CoreReflexes() {}

    public static void registerAll() {
        ReflexRegistry.register(new MLGChain());
        ReflexRegistry.register(new org.maiwithu.maicraft.core.task.chain.BreathChain());
        ReflexRegistry.register(new MobDefenseChain());
        ReflexRegistry.register(new UnstuckChain());
    }
}
