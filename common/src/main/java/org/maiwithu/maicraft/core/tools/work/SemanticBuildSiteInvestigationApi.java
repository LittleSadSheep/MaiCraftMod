// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.tools.work;

import org.maiwithu.maicraft.agent.tool.ToolRegistry;
import org.maiwithu.maicraft.core.task.build.BuildSiteInvestigationTaskRecord;

/**
 * 向包外提供一次注册入口：先触发勘察记录的工厂注册，再登记隐藏工具。当前只有 MaiCraftCore 调用这里。
 */
public final class SemanticBuildSiteInvestigationApi {
    private SemanticBuildSiteInvestigationApi() {}

    public static void register() {
        BuildSiteInvestigationTaskRecord.ensureRegistered();
        ToolRegistry.register(new SemanticBuildSiteInvestigationTool());
    }
}
