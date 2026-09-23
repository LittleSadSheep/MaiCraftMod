// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

/** 独立故障注入测试；无需运行客户端、可选模组或网络服务器。 */
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
