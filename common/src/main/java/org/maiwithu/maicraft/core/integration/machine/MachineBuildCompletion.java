// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.Map;

/**
 * 记录装配验收走到了哪里，让任务是否结束和对外报告使用同一份状态。生产是否成功始终要另外操作和观察。
 */
final class MachineBuildCompletion {
    private final boolean explicitBlueprint;
    private boolean geometryVerified;
    private boolean complete;

    MachineBuildCompletion(boolean explicitBlueprint) { this.explicitBlueprint = explicitBlueprint; }

    /**
     * 逐格蓝图只要求结构安装，结构通过即可完成；语义布局还要继续做它声明的形成或配置验收。
     */
    boolean acceptGeometry() {
        geometryVerified = true;
        if (explicitBlueprint) complete = true;
        return complete;
    }

    // 只能在结构已通过、且确有语义布局验收阶段时完成；不能跳过结构检查直接记成功。
    void acceptCommissioning() {
        if (!geometryVerified || explicitBlueprint)
            throw new IllegalStateException("Only a verified semantic layout has construction commissioning obligations");
        complete = true;
    }

    Map<String, Object> report() {
        return Map.of("machine_geometry_verified", geometryVerified,
                "machine_production_verified", false,
                "construction_complete", complete,
                "configuration_complete", !explicitBlueprint && complete,
                "configuration_status", explicitBlueprint ? "separate_use_phase" : complete ? "complete" : "pending");
    }
}
