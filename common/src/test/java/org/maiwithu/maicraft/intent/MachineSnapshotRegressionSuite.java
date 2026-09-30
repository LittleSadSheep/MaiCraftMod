// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.server.MachineSnapshotEnrichmentTest;
import org.maiwithu.maicraft.core.integration.machine.ConstructionSiteGeometryTest;
import org.maiwithu.maicraft.mcp.ResponseArchiveTest;
import org.maiwithu.maicraft.mcp.TaskViewTest;

/** 回放同一场地在长时间思考、实际变化和失败后补读时的行为，检查模型首份回执可直接使用新现场。 */
public final class MachineSnapshotRegressionSuite {
    private MachineSnapshotRegressionSuite() {}

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // 先验证当前方块与原锚点，再验证消费后的原生失败，最后检查任务与网络两层呈现。
        ConstructionSiteGeometryTest.main(args);
        ConstructionSiteRuntimeTest.main(args);
        MachineSnapshotEnrichmentTest.main(args);
        IntentTerminalStateTest.main(args);
        TaskViewTest.main(args);
        ResponseArchiveTest.main(args);
    }
}
