// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

/** 独立回归测试入口；仅依赖 Gson 和这些协议类。 */
public final class ProtocolRegressionSuite {
    public static void main(String[] args) {
        ProtocolMutationTest.main(args);
        ProtocolLifecycleTest.main(args);
        ProtocolBoundaryTest.main(args);
        ProtocolObservationTest.main(args);
    }
}
