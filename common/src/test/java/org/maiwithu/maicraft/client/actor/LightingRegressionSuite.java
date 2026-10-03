// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import org.maiwithu.maicraft.intent.AutomaticLightingContractTest;

/** 照明与主任务共用身体时，先验证资源仲裁，再回放真实副手准备和光照验收。 */
public final class LightingRegressionSuite {
    public static void main(String[] args) throws Exception {
        AuxiliaryActionTest.main(args);
        AuxiliaryNavigationTest.main(args);
        AutomaticLightingTest.main(args);
        AreaLightingTest.main(args);
        AutomaticLightingContractTest.main(args);
    }
}
