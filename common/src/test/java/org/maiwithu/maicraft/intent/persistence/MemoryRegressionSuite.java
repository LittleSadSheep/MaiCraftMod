// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent.persistence;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalogTest;
import org.maiwithu.maicraft.core.integration.machine.catalog.MachineBlueprintCatalogTest;
import org.maiwithu.maicraft.core.integration.machine.catalog.MachineCatalogMigrationTest;
import org.maiwithu.maicraft.core.task.build.BuildProjectScaffoldPersistenceTest;
import org.maiwithu.maicraft.core.task.build.BuildProjectRevisionTest;
import org.maiwithu.maicraft.intent.BuildProjectContinuationTest;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneStoreTest;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneV2StoreTest;
import org.maiwithu.maicraft.core.blueprint.BuildingModelContractTest;
import org.maiwithu.maicraft.intent.BuildingSceneRuntimeTest;
import org.maiwithu.maicraft.intent.BuildingSceneVersionRuntimeTest;
import org.maiwithu.maicraft.core.task.enchant.EnchantmentSubmissionJournalTest;
import org.maiwithu.maicraft.intent.ChatDurableCheckpointTest;
import org.maiwithu.maicraft.client.server.MutationJournalTest;
import org.maiwithu.maicraft.mcp.ResponseArchiveTest;

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
        // 每类旧持久化入口都在本套件覆盖，防止无关游戏回归提前失败时漏验模型、施工和消费日志。
        BuildingSceneStoreTest.main(args);
        BuildingSceneV2StoreTest.main(args);
        BuildingModelContractTest.main(args);
        BuildingSceneRuntimeTest.main(args);
        BuildingSceneVersionRuntimeTest.main(args);
        BuildProjectContinuationTest.main(args);
        BuildProjectScaffoldPersistenceTest.main(args);
        BuildProjectRevisionTest.main(args);
        EnchantmentSubmissionJournalTest.main(args);
        ChatDurableCheckpointTest.main(args);
        MutationJournalTest.main(args);
        ResponseArchiveTest.main(args);
        System.out.println("MemoryRegressionSuite: passed");
    }
}
