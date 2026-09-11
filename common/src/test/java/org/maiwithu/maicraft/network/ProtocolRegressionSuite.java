// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

/** Standalone regression entry; only Gson and these protocol classes are required. */
public final class ProtocolRegressionSuite {
    public static void main(String[] args) {
        ProtocolMutationTest.main(args);
        ProtocolLifecycleTest.main(args);
        ProtocolBoundaryTest.main(args);
        ProtocolObservationTest.main(args);
    }
}
