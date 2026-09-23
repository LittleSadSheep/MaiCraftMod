// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.core.integration.create.CreateTooltipKnowledgeTest;
import org.maiwithu.maicraft.core.integration.emi.EmiRecipeKnowledgeTest;
import org.maiwithu.maicraft.core.integration.ponder.PonderKnowledgeTest;
import org.maiwithu.maicraft.mcp.knowledge.BuildingModelContractResourcesTest;
import org.maiwithu.maicraft.mcp.knowledge.BuildingTutorialResourcesTest;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibraryTest;
import org.maiwithu.maicraft.mcp.knowledge.MinecraftKnowledgeSource;
import org.maiwithu.maicraft.mcp.knowledge.RecipeKnowledgeSourceTest;
import org.maiwithu.maicraft.mcp.knowledge.MachineAssemblyResourcesTest;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestAccessTest;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbTaskConditionsTest;
import org.maiwithu.maicraft.mcp.knowledge.FtbQuestsKnowledgeSourceTest;

// 验证知识资源库与 HTTP 的只读行为，先初始化原版注册信息以便检查真实方块属性。
public final class KnowledgeRegressionSuite {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        PublicTargetContractTest.main(args);
        KnowledgeLibraryTest.main(args);
        BuildingModelContractResourcesTest.main(args);
        // 教材先跑实际建模与方块状态检查，避免把能解析但会堵门或填满屋顶的案例交给设计 Agent。
        BuildingTutorialResourcesTest.main(args);
        // 材料配方页只读取所选工艺页并链接设备教程，缺失知识不能伪装成可执行配方。
        RecipeKnowledgeSourceTest.main(args);
        MachineAssemblyResourcesTest.main(args);
        EmiRecipeKnowledgeTest.main(args);
        CreateTooltipKnowledgeTest.main(args);
        PonderKnowledgeTest.main(args);
        // 先验证玩家可见范围和队伍同步，再确认同一任务书经知识接口分页和按需展开。
        FtbQuestAccessTest.main(args);
        FtbTaskConditionsTest.main(args);
        FtbQuestsKnowledgeSourceTest.main(args);
        // 同时确认知识 URI 能读出箱子的朝向属性，并拒绝把任意文件路径当成知识资源。
        var source = new MinecraftKnowledgeSource();
        var document = source.read("maicraft://knowledge/block/minecraft/chest");
        if (document == null || !document.text().contains("facing") || source.read("file:///private") != null)
            throw new AssertionError("Registry document must expose actual properties and reject filesystem URIs");
        KnowledgeHttpTest.main(args);
        System.out.println("KnowledgeRegressionSuite: passed");
    }
}
