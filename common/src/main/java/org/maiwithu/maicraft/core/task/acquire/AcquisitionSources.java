// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.List;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord.Source;

/** 取物的前置需求沿用当前许可；补工具、燃料或工作台都不能成为扩大取材范围的理由。 */
final class AcquisitionSources {
    private AcquisitionSources() {}

    static List<Source> forTool(List<Source> parent, boolean stockOnlyUpgrade) {
        // 必需工具继承原来源；富余材料带来的升级只取现货或合成，失败后回到便宜工具，不另开采集链。
        return stockOnlyUpgrade
                ? parent.stream().filter(source -> source == Source.INVENTORY
                        || source == Source.STORAGE || source == Source.CRAFT).toList()
                : List.copyOf(parent);
    }

    static List<Source> forCookingInputs(List<Source> parent) {
        // 加工原料和燃料沿用当前需求的许可，去掉 COOK 避免“为了开炉又先开炉”的递归。
        return parent.stream().filter(source -> source != Source.COOK).toList();
    }
}
