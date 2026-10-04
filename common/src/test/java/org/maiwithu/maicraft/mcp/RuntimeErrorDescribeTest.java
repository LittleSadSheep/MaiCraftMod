// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

/** runtime_error 回执必须携带异常类型与原因链；只透传 getMessage() 时 LinkageError 只剩一个类名。 */
public final class RuntimeErrorDescribeTest {
    public static void main(String[] args) {
        // 080 实机形态：NoClassDefFoundError 的 message 只有斜杠分隔类名，无异常类型。
        var missing = new NoClassDefFoundError("org/maiwithu/maicraft/intent/CookAbilityAdapter");
        String described = EmbeddedMcpService.describe(missing);
        check(described.startsWith("NoClassDefFoundError:"), "回执应以异常类型开头: " + described);
        check(described.contains("org/maiwithu/maicraft/intent/CookAbilityAdapter"),
                "回执应保留缺失类名: " + described);
        // NPE 的 message 为 null，不能落回无信息占位句。
        check(EmbeddedMcpService.describe(new NullPointerException())
                .equals("NullPointerException"), "空消息异常应返回类型名");
        // 原因链逐环展开，初始化失败装成 NoDefFound 时能看出原始异常。
        var root = new IllegalStateException("furnace menu closed unexpectedly");
        var wrapped = new NoClassDefFoundError("org/maiwithu/maicraft/intent/CookAbilityAdapter");
        wrapped.initCause(root);
        String chained = EmbeddedMcpService.describe(wrapped);
        check(chained.contains("caused by IllegalStateException: furnace menu closed unexpectedly"),
                "回执应携带原因链: " + chained);
        // 有正常消息的异常仍带类型前缀，与 invalid_arguments 的人写消息区分开。
        check(EmbeddedMcpService.describe(new IllegalArgumentException("bad thing"))
                .equals("IllegalArgumentException: bad thing"), "普通异常也应带类型前缀");
        System.out.println("RuntimeErrorDescribeTest: passed");
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }
}
