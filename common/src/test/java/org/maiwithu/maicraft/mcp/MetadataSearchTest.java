// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

/** 名称容错覆盖错字、漏字、相邻颠倒和 Unicode；未知词不会被当成可执行对象。 */
public final class MetadataSearchTest {
    public static void main(String[] args) {
        check(new MetadataSearch.Query("精密构建").match("create:precision_mechanism", "精密构件 · 材料与工艺", "") != null,
                "a Chinese typo still discovers the registered item");
        check(new MetadataSearch.Query("铜质齿伦").match("demo:copper_gear", "铜质齿轮", "") != null,
                "matching does not depend on a product-specific alias");
        check(new MetadataSearch.Query("machien").match("maicraft:build_machine", "Build machine", "") != null,
                "adjacent transposition is recoverable");
        check(new MetadataSearch.Query("precision_mechanim").match("create:precision_mechanism", "精密构件", "") != null,
                "missing identifier character is recoverable");
        var exact = new MetadataSearch.Query("精密构件").match("create:precision_mechanism", "精密构件", "");
        var approximate = new MetadataSearch.Query("精密构件").match("demo:other", "精密构建器", "");
        check(exact != null && approximate != null && exact.score() > approximate.score(), "exact name ranks first");
        check(new MetadataSearch.Query("ＣＲＥＡＴＥ：ＰＲＥＣＩＳＩＯＮ＿ＭＥＣＨＡＮＩＳＭ")
                .match("create:precision_mechanism", "精密构件", "").kind().equals("exact"), "normalized identifier");
        check(new MetadataSearch.Query("红").match("demo:blue", "蓝色方块", "") == null, "short words are literal");
        check(new MetadataSearch.Query("无关飞船").match("create:precision_mechanism", "精密构件", "") == null,
                "unrelated words remain unmatched");
        check(new MetadataSearch.Query(".*").match("demo:item", "任意物品", "") == null, "input is not a regex program");
        check(new MetadataSearch.Query("动力 连接").match("demo:connect", "连接机器", "提供旋转动力接口") != null,
                "keywords may match different metadata fields");
        check(new MetadataSearch.Query("𠀀测试").match("demo:unicode", "𠀀测试", "").kind().equals("exact"),
                "Unicode names preserve code points");
        System.out.println("MetadataSearchTest: passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
