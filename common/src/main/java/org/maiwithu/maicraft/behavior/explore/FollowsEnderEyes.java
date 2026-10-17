// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import java.util.Optional;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 扔末影之眼找要塞要用到的现场：身上有几只眼、扔一只并盯着它飞、捡回落下的那颗、朝一处走一段。
 * 生产实现接真实客户端，测试用替身；判断在 {@link EyeBearings}，一趟怎么扔怎么走在 {@link EnderEyeSearch}。
 */
public interface FollowsEnderEyes {

    /** 身上带着几只末影之眼（背包、快捷栏、副手）。 */
    int eyesCarried();

    /** 脚下那一格与所在维度；不在世界里为空。 */
    Optional<WorldPosition> feet();

    /** 扔一只眼：拿到主手、抬头对着天空右键一下，盯着飞出去的眼直到它落下或碎掉。 */
    EyeThrow throwEye();

    /** 捡回落下的那颗眼：走过去让原版拾取吸进包。 */
    Action fetch(int dropEntity);

    /** 朝 (x, z) 那一柱列走一段；高度到了附近再找能站的格子。 */
    WalkRun walkToward(int x, int z, TerrainPermit permit);

    /** 扔一只眼的动作；做完后读它看到的经过。 */
    interface EyeThrow extends Action {

        /** 看到的飞行经过；眼根本没飞起来（这个世界没有它能指的要塞）为空。动作做完后才有意义。 */
        Optional<EyeFlight> flight();
    }
}
