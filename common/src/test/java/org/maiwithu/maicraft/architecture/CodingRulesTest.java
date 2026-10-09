// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * 编码检查：不写全局可变状态与单例访问器，对 Minecraft 客户端的写操作只在游戏接口层。
 *
 * <p>下层一旦能通过单例访问器随手调用上层服务，很快就会变成谁都能碰到谁；服务一律从构造函数传入。
 */
@AnalyzeClasses(packages = "org.maiwithu.maicraft", importOptions = ImportOption.DoNotIncludeTests.class)
class CodingRulesTest {
    private static final String ROOT = "org.maiwithu.maicraft.";

    /** 不新增可变的 static 字段（Mixin 包除外，Mixin 有时需要它记录注入状态）。 */
    @ArchTest
    static final ArchRule noMutableStaticFields = fields()
            .that().areDeclaredInClassesThat().resideInAPackage("org.maiwithu.maicraft..")
            .and().areDeclaredInClassesThat().resideOutsideOfPackage(ROOT + "game.mixin..")
            .and().areStatic()
            .should().beFinal()
            .allowEmptyShould(true);

    /**
     * final 的静态字段里装着可变状态（原子引用、原子计数）也是全局可变状态：只准出现在三处——
     * Mixin 进入游戏接口层的登记点 ClientHooks、Mixin 包、内嵌 Baritone 的桥（Baritone 的代码只能静态调用它们）。
     */
    @ArchTest
    static final ArchRule mutableStaticHoldersStayInRegistrationPoints = fields()
            .that().areDeclaredInClassesThat().resideInAPackage("org.maiwithu.maicraft..")
            .and().areStatic()
            .and(new DescribedPredicate<JavaField>("装着可变状态的原子类型") {
                @Override public boolean test(JavaField field) {
                    return field.getRawType().getPackageName().equals("java.util.concurrent.atomic");
                }
            })
            .should().beDeclaredInClassesThat().haveFullyQualifiedName(ROOT + "game.ClientHooks")
            .orShould().beDeclaredInClassesThat().resideInAnyPackage(
                    ROOT + "game.mixin..", ROOT + "behavior.navigation.baritone..")
            .allowEmptyShould(true);

    /** 不写 get()、instance()、getInstance() 这类静态单例访问器；服务从构造函数传入。 */
    @ArchTest
    static final ArchRule noSingletonAccessors = noMethods()
            .that().areDeclaredInClassesThat().resideInAPackage("org.maiwithu.maicraft..")
            .and().areStatic()
            .should().haveNameMatching("get|instance|getInstance")
            .allowEmptyShould(true);

    /** 游戏模式交互、发包、按键状态这些写操作只在游戏接口层；其他层只读地使用游戏对象。 */
    @ArchTest
    static final ArchRule clientWritesOnlyInGameLayer = noClasses()
            .that().resideInAPackage("org.maiwithu.maicraft..")
            .and().resideOutsideOfPackage(ROOT + "game..")
            .should().dependOnClassesThat().haveFullyQualifiedName("net.minecraft.client.multiplayer.MultiPlayerGameMode")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("net.minecraft.client.multiplayer.ClientPacketListener")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("net.minecraft.client.KeyMapping")
            .allowEmptyShould(true);

    /** 只有游戏接口层和调试界面可以直接取 Minecraft 客户端单例；其他层通过游戏接口层访问。 */
    @ArchTest
    static final ArchRule minecraftSingletonOnlyInGameLayer = noClasses()
            .that().resideInAPackage("org.maiwithu.maicraft..")
            .and().resideOutsideOfPackages(ROOT + "game..", ROOT + "debug..")
            .should().callMethod("net.minecraft.client.Minecraft", "getInstance")
            .allowEmptyShould(true);
}
