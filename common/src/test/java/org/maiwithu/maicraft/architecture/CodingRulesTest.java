// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * 编码护栏（docs/design/02 第 7 节的 A6、A7）：不写全局可变状态与单例访问器，Minecraft 客户端的写操作只在平台层。
 *
 * <p>v1 有 120 个可变 static 字段，下层通过单例访问器随手调用上层服务，最终谁都能碰到谁；
 * v2 的服务一律从构造函数注入。
 */
@AnalyzeClasses(packages = "org.maiwithu.maicraft", importOptions = ImportOption.DoNotIncludeTests.class)
class CodingRulesTest {
    private static final String ROOT = "org.maiwithu.maicraft.";

    /** A7：不新增可变的 static 字段（Mixin 包除外，Mixin 有时需要它记录注入状态）。 */
    @ArchTest
    static final ArchRule noMutableStaticFields = fields()
            .that().areDeclaredInClassesThat().resideInAPackage("org.maiwithu.maicraft..")
            .and().areDeclaredInClassesThat().resideOutsideOfPackage(ROOT + "platform.mixin..")
            .and().areStatic()
            .should().beFinal()
            .allowEmptyShould(true);

    /** A7：不写 get()、instance()、getInstance() 这类静态单例访问器；服务从构造函数注入。 */
    @ArchTest
    static final ArchRule noSingletonAccessors = noMethods()
            .that().areDeclaredInClassesThat().resideInAPackage("org.maiwithu.maicraft..")
            .and().areStatic()
            .should().haveNameMatching("get|instance|getInstance")
            .allowEmptyShould(true);

    /** A6：游戏模式交互、发包、按键状态这些写操作只在平台层；其他层只读地使用游戏对象。 */
    @ArchTest
    static final ArchRule clientWritesOnlyInPlatform = noClasses()
            .that().resideInAPackage("org.maiwithu.maicraft..")
            .and().resideOutsideOfPackage(ROOT + "platform..")
            .should().dependOnClassesThat().haveFullyQualifiedName("net.minecraft.client.multiplayer.MultiPlayerGameMode")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("net.minecraft.client.multiplayer.ClientPacketListener")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("net.minecraft.client.KeyMapping")
            .allowEmptyShould(true);

    /** A6：只有平台层和调试界面可以直接取 Minecraft 客户端单例；其他层通过平台服务访问。 */
    @ArchTest
    static final ArchRule minecraftSingletonOnlyInPlatform = noClasses()
            .that().resideInAPackage("org.maiwithu.maicraft..")
            .and().resideOutsideOfPackages(ROOT + "platform..", ROOT + "debug..")
            .should().callMethod("net.minecraft.client.Minecraft", "getInstance")
            .allowEmptyShould(true);
}
