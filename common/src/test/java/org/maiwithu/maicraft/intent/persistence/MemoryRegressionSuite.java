// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent.persistence;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalogTest;
import org.maiwithu.maicraft.core.integration.machine.catalog.MachineBlueprintCatalogTest;
import org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalogMigrationTest;

/** 先验证 SQLite 事务，再验证真实任务的迁移、重连和退出时等待落盘。 */
public final class MemoryRegressionSuite {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        MemoryDatabaseTest.main(args);
        IntentSqliteMigrationTest.main(args);
        IntentStateStoreTest.main(args);
        // 机器档案与任务共用同一数据库实现，迁移和蓝图引用也必须跟随持久化回归运行。
        MachineCatalogTest.main(args);
        MachineBlueprintCatalogTest.main(args);
        MachineCatalogMigrationTest.main(args);
        System.out.println("MemoryRegressionSuite: passed");
    }
}
