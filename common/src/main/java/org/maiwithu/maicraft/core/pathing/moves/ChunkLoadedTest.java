package org.maiwithu.maicraft.core.pathing.moves;

/**
 * 告诉费用计算器某个水平坐标的区块是否已加载。ALWAYS 表示调用者自己承担已加载前提，并不会实际加载区块。
 */
@FunctionalInterface
public interface ChunkLoadedTest {

    /** 永远视为已加载(实时世界场景)。 */
    ChunkLoadedTest ALWAYS = (x, z) -> true;

    boolean isLoaded(int blockX, int blockZ);
}
