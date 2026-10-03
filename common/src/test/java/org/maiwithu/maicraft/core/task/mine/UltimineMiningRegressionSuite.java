// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.mine;

import org.maiwithu.maicraft.core.integration.ultimine.UltimineSelectionPolicyTest;
import org.maiwithu.maicraft.core.integration.ultimine.UltimineBatchTest;
import org.maiwithu.maicraft.client.actor.UltimineBreakTest;
import org.maiwithu.maicraft.client.actor.MineUltimineTaskTest;

/** 连锁采集独立回放，核对原生整脉授权并保留施工九格行为。 */
public final class UltimineMiningRegressionSuite {
    public static void main(String[] args) throws Exception {
        UltimineSelectionPolicyTest.main(args);
        UltimineBatchTest.main(args);
        UltimineBreakTest.main(args);
        MineUltimineTaskTest.main(args);
        ProspectTunnelPlanTest.main(args);
        ProspectTunnelDriverTest.main(args);
    }
}
