// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** 旧身体回归入口沿用新的随行场景，保证副手确认与主任务让位不会退回抢占式插灯。 */
public final class TorchLightingBehaviorTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        AuxiliaryActionTest.main(args);
        AutomaticLightingTest.main(args);
    }
}
