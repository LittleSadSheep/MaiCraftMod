// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.suicide;

import org.maiwithu.maicraft.intent.SuicideAbilityTest;
import org.maiwithu.maicraft.intent.SuicideRespawnTest;
import org.maiwithu.maicraft.client.actor.SuicideFireTest;
import org.maiwithu.maicraft.client.actor.SuicideHostileTest;

/** 在独立游戏夹具中依次验证危险观察和身体动作，不让主动寻死测试影响其他能力的自保状态。 */
public final class SuicideRegressionSuite {
    public static void main(String[] args) throws Exception {
        SuicideHazardsTest.main(args);
        SuicideTaskTest.main(args);
        SuicideHostileTest.main(args);
        SuicideFireTest.main(args);
        SuicideAbilityTest.main(args);
        SuicideRespawnTest.main(args);
        System.out.println("SuicideRegressionSuite: passed");
    }
}
