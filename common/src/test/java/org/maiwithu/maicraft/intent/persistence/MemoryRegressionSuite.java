// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent.persistence;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** 先验证 SQLite 事务，再验证真实任务的迁移、重连和退出时等待落盘。 */
public final class MemoryRegressionSuite {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        MemoryDatabaseTest.main(args);
        IntentSqliteMigrationTest.main(args);
        IntentStateStoreTest.main(args);
        System.out.println("MemoryRegressionSuite: passed");
    }
}
