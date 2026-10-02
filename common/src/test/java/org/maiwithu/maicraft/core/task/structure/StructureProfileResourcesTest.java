package org.maiwithu.maicraft.core.task.structure;

import java.io.StringReader;
import java.util.Map;

/** 模组结构能以真实已安装方块声明识别规则；缺方块的规则仍可列出，但不能伪称可搜索。 */
public final class StructureProfileResourcesTest {
    public static void main(String[] args) {
        String source = """
                {"canonicalId":"unknownmod:watchtower","dimensions":["minecraft:overworld"],
                 "clusterRadius":12,"minimumTotal":4,"evidenceDescription":"observed copper tower palette",
                 "groups":[{"label":"signature","minimum":4,"blockIds":["minecraft:copper_block"]}]}
                """;
        var profile = StructureProfileResources.parse(new StringReader(source));
        StructureEvidenceProfiles.installResources(Map.of(profile.canonicalId(), profile));
        check(StructureEvidenceProfiles.registeredIds().contains("unknownmod:watchtower"), "list mod profile");
        check(StructureEvidenceProfiles.resolve("unknownmod:watchtower") != null, "resolve installed native palette");
        var missing = StructureProfileResources.parse(new StringReader(source.replace("minecraft:copper_block", "absent:block")));
        StructureEvidenceProfiles.installResources(Map.of(missing.canonicalId(), missing));
        check(StructureEvidenceProfiles.resolve(missing.canonicalId()) == null, "missing mod block cannot produce a usable profile");
        try {
            StructureProfileResources.parse(new StringReader(source.replace("unknownmod:watchtower", "invented id")));
            throw new AssertionError("invalid profile ID accepted");
        } catch (IllegalArgumentException expected) { /* 错误配置在目录中显式报告。 */ }
        StructureEvidenceProfiles.installResources(Map.of());
        System.out.println("StructureProfileResourcesTest: passed");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
