// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

public final class KnowledgeRegressionSuite {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibraryTest.main(args);
        org.maiwithu.maicraft.core.integration.create.CreateTooltipKnowledgeTest.main(args);
        org.maiwithu.maicraft.core.integration.ponder.PonderKnowledgeTest.main(args);
        var source = new org.maiwithu.maicraft.mcp.knowledge.MinecraftKnowledgeSource();
        var document = source.read("maicraft://knowledge/block/minecraft/chest");
        if (document == null || !document.text().contains("facing") || source.read("file:///private") != null)
            throw new AssertionError("Registry document must expose actual properties and reject filesystem URIs");
        KnowledgeHttpTest.main(args);
        System.out.println("KnowledgeRegressionSuite: passed");
    }
}
