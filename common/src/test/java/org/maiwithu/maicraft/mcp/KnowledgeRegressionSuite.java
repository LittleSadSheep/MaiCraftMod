// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

// 验证知识资源库与 HTTP 的只读行为，先初始化原版注册信息以便检查真实方块属性。
public final class KnowledgeRegressionSuite {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibraryTest.main(args);
        org.maiwithu.maicraft.core.integration.create.CreateTooltipKnowledgeTest.main(args);
        org.maiwithu.maicraft.core.integration.ponder.PonderKnowledgeTest.main(args);
        // 同时确认知识 URI 能读出箱子的朝向属性，并拒绝把任意文件路径当成知识资源。
        var source = new org.maiwithu.maicraft.mcp.knowledge.MinecraftKnowledgeSource();
        var document = source.read("maicraft://knowledge/block/minecraft/chest");
        if (document == null || !document.text().contains("facing") || source.read("file:///private") != null)
            throw new AssertionError("Registry document must expose actual properties and reject filesystem URIs");
        KnowledgeHttpTest.main(args);
        System.out.println("KnowledgeRegressionSuite: passed");
    }
}
