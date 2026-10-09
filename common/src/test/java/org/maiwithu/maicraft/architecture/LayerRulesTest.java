// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * 分层检查：依赖只能往下走，违反时构建失败。
 *
 * <p>这些规则从第一天起严格执行，没有"历史违规名单"。检查只能收紧，不能为了让构建通过而放宽。
 */
@AnalyzeClasses(packages = "org.maiwithu.maicraft", importOptions = ImportOption.DoNotIncludeTests.class)
class LayerRulesTest {
    private static final String ROOT = "org.maiwithu.maicraft.";

    /** 层的方向：游戏接口 ← 内核 ← 玩家行为 ← 能力与联动模组 ← MCP 入口 ← 启动；网络消息只给游戏接口层与服务端用。 */
    @ArchTest
    static final ArchRule layersOnlyDependDownward = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
            .layer("Network").definedBy(ROOT + "network..")
            .layer("Game").definedBy(ROOT + "game..")
            .layer("Kernel").definedBy(ROOT + "kernel..")
            .layer("Behavior").definedBy(ROOT + "behavior..")
            .layer("Ability").definedBy(ROOT + "ability..")
            .layer("Compat").definedBy(ROOT + "compat..")
            .layer("Mcp").definedBy(ROOT + "mcp..")
            .layer("Debug").definedBy(ROOT + "debug..")
            .layer("Server").definedBy(ROOT + "server..")
            .layer("Bootstrap").definedBy(ROOT + "bootstrap..")
            .whereLayer("Bootstrap").mayNotBeAccessedByAnyLayer()
            .whereLayer("Debug").mayOnlyBeAccessedByLayers("Bootstrap")
            .whereLayer("Mcp").mayOnlyBeAccessedByLayers("Bootstrap")
            .whereLayer("Compat").mayOnlyBeAccessedByLayers("Bootstrap")
            .whereLayer("Ability").mayOnlyBeAccessedByLayers("Bootstrap", "Mcp", "Compat")
            .whereLayer("Behavior").mayOnlyBeAccessedByLayers("Ability", "Compat", "Mcp", "Debug", "Bootstrap")
            .whereLayer("Kernel").mayOnlyBeAccessedByLayers("Behavior", "Ability", "Compat", "Mcp", "Debug", "Bootstrap")
            .whereLayer("Game").mayOnlyBeAccessedByLayers("Kernel", "Behavior", "Ability", "Compat", "Mcp", "Debug", "Bootstrap")
            .whereLayer("Server").mayOnlyBeAccessedByLayers("Bootstrap")
            .whereLayer("Network").mayOnlyBeAccessedByLayers("Game", "Server", "Bootstrap");

    /** 内核不直接碰 Minecraft 的类：存档、玩家、世界都经游戏接口层转成内核自己的说法。 */
    @ArchTest
    static final ArchRule kernelDoesNotTouchMinecraft = noClasses()
            .that().resideInAPackage(ROOT + "kernel..")
            .should().dependOnClassesThat().resideInAPackage("net.minecraft..");

    /** 能力之间只能通过对方的 api、spi 子包互相依赖，不能伸进对方的内部包。 */
    @ArchTest
    static final ArchRule abilitiesOnlyMeetThroughApiOrSpi = classes()
            .that().resideInAPackage(ROOT + "ability..")
            .should(onlyUseOtherAbilitiesThroughApiOrSpi())
            .allowEmptyShould(true);

    /** MCP 入口只能看到能力的 api 子包。 */
    @ArchTest
    static final ArchRule mcpSeesOnlyAbilityApi = noClasses()
            .that().resideInAPackage(ROOT + "mcp..")
            .should().dependOnClassesThat(resideInAPackage(ROOT + "ability..")
                    .and(not(resideInAPackage(ROOT + "ability.*.api.."))))
            .allowEmptyShould(true);

    /** 联动模组只能实现能力的 spi，不能依赖能力的内部包。 */
    @ArchTest
    static final ArchRule compatSeesOnlyAbilitySpi = noClasses()
            .that().resideInAPackage(ROOT + "compat..")
            .should().dependOnClassesThat(resideInAPackage(ROOT + "ability..")
                    .and(not(resideInAPackage(ROOT + "ability.*.spi.."))))
            .allowEmptyShould(true);

    /** 联动模组之间互不依赖，一个模组没装不会牵连另一个。 */
    @ArchTest
    static final ArchRule compatModsAreIndependent = slices()
            .matching(ROOT + "compat.(*)..")
            .should().notDependOnEachOther()
            .allowEmptyShould(true);

    /** 服务端一侧按模组分的读取之间同样互不依赖。 */
    @ArchTest
    static final ArchRule serverCompatModsAreIndependent = slices()
            .matching(ROOT + "server.compat.(*)..")
            .should().notDependOnEachOther()
            .allowEmptyShould(true);

    /** 只有寻路的 Baritone 适配层可以使用 Baritone 的非 api 包。 */
    @ArchTest
    static final ArchRule baritoneInternalsStayInNavigation = noClasses()
            .that().resideInAPackage("org.maiwithu.maicraft..")
            .and().resideOutsideOfPackage(ROOT + "behavior.navigation.baritone..")
            .should().dependOnClassesThat(resideInAPackage("baritone..")
                    .and(not(resideInAnyPackage("baritone.api.."))))
            .allowEmptyShould(true);

    private static ArchCondition<JavaClass> onlyUseOtherAbilitiesThroughApiOrSpi() {
        return new ArchCondition<>("只通过 api / spi 子包依赖其他能力") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String own = abilityOf(item.getPackageName());
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                    String target = dependency.getTargetClass().getPackageName();
                    String other = abilityOf(target);
                    if (other != null && !other.equals(own) && !isApiOrSpi(target, other)) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    // 从包名里取出能力名：org.maiwithu.maicraft.ability.sleep.xxx → sleep；不是能力包时返回 null。
    private static String abilityOf(String packageName) {
        String prefix = ROOT + "ability.";
        if (!packageName.startsWith(prefix)) return null;
        String rest = packageName.substring(prefix.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    private static boolean isApiOrSpi(String packageName, String ability) {
        String base = ROOT + "ability." + ability;
        return packageName.equals(base + ".api") || packageName.startsWith(base + ".api.")
                || packageName.equals(base + ".spi") || packageName.startsWith(base + ".spi.");
    }
}
