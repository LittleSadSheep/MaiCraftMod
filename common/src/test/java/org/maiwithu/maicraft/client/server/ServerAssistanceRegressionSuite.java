// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

/** Standalone fault-injection checks; no running client, optional mods or network server required. */
public final class ServerAssistanceRegressionSuite {
    public static void main(String[] args) throws Exception {
        ClientServerFallbackTest.main(args);
        ClientRequestReconciliationTest.main(args);
        ClientControlLifecycleTest.main(args);
        MutationJournalTest.main(args);
        ServerSessionLongRunTest.main(args);
        MachineObservationPagesTest.main(args);
        ServerRequestOwnersTest.main(args);
        System.out.println("ServerAssistanceRegressionSuite: passed");
    }
}
