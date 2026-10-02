// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.container;

import org.maiwithu.maicraft.intent.ContainerMemoryCheckpointTest;
import org.maiwithu.maicraft.core.pathing.baritone.NavigationBodyRangeTest;
import org.maiwithu.maicraft.core.task.acquire.OrdinaryStorageAcquireTest;
import org.maiwithu.maicraft.core.task.acquire.StorageSupplyRadiusTest;
import org.maiwithu.maicraft.core.task.supply.MaterialSupplyReceiptTest;

/** 箱子调查独立回归入口，覆盖自主记忆、真实菜单和固定范围取料。 */
public final class ContainerSearchRegressionSuite {
    public static void main(String[] args) throws Exception {
        ContainerMemoryCheckpointTest.main(args);
        NavigationBodyRangeTest.main(args);
        ContainerInvestigationTest.main(args);
        ContainerSupplySourcesTest.main(args);
        OrdinaryStorageAcquireTest.main(args);
        StorageSupplyRadiusTest.main(args);
        MaterialSupplyReceiptTest.main(args);
    }
}
