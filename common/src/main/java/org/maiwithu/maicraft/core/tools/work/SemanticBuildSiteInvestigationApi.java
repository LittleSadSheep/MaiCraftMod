// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.tools.work;

import org.maiwithu.maicraft.agent.tool.ToolRegistry;
import org.maiwithu.maicraft.core.task.build.BuildSiteInvestigationTaskRecord;

/** Registers the hidden build-site investigation capability and its task runner. */
public final class SemanticBuildSiteInvestigationApi {
    private SemanticBuildSiteInvestigationApi() {}

    public static void register() {
        BuildSiteInvestigationTaskRecord.ensureRegistered();
        ToolRegistry.register(new SemanticBuildSiteInvestigationTool());
    }
}
