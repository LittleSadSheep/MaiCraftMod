// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 成员名的命名检查：naming-rules.txt 里的禁用词（word）不只管类型名，也管我们自己起的字段、方法、枚举常量与 record 组件。
 * 只查我们声明的名字；调用 Minecraft、JDK 的方法（getRecipeManager、ExecutorService）不是我们起的，不算。
 * 覆盖或实现外部类型的方法名由外部定，也不算；Mixin 包里的名字要对上游戏的字段与方法，同样跳过。
 * 词的本义正好就是要说的（MCP 服务的网络端口、HTTP 请求体、线程池、处理一个请求）时，在 naming-rules.txt 里用
 * {@code allow 类名.成员名 原因} 逐个放行，写明为什么。
 */
class MemberNamesTest {
    private static final String ROOT = "org.maiwithu.maicraft";
    private static final Path RULES = Path.of("src/test/resources/naming-rules.txt");
    /** 驼峰拆词：首字母先大写，再按大写字母开头切。 */
    private static final Pattern CAMEL_WORD = Pattern.compile("[A-Z]+(?![a-z])|[A-Z][a-z0-9]*");
    /** 编译器生成的名字：lambda、桥接、内部类访问器。 */
    private static final Pattern GENERATED = Pattern.compile(".*\\$.*");
    private static final Set<String> ENUM_METHODS = Set.of("values", "valueOf");

    private static JavaClasses classes;
    private static Map<String, String> bannedWords;
    /** 逐个放行的成员：键是去掉 org.maiwithu.maicraft. 的类名加成员名。 */
    private static Set<String> allowed;

    @BeforeAll
    static void load() throws IOException {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
        bannedWords = new LinkedHashMap<>();
        allowed = new TreeSet<>();
        for (String line : Files.readAllLines(RULES)) {
            String[] parts = line.trim().split("\\s+", 3);
            if (parts.length == 3 && parts[0].equals("word")) bannedWords.put(parts[1], parts[2]);
            if (parts.length == 3 && parts[0].equals("allow")) allowed.add(parts[1]);
        }
        assertTrue(!bannedWords.isEmpty(), "naming-rules.txt 里没有 word 规则");
    }

    @Test
    void ourMemberNamesAvoidBannedWords() {
        Set<String> problems = new TreeSet<>();
        for (JavaClass type : classes) {
            if (!type.getPackageName().startsWith(ROOT) || type.getPackageName().startsWith(ROOT + ".game.mixin")) {
                continue;
            }
            for (JavaField field : type.getFields()) {
                if (field.getModifiers().contains(JavaModifier.SYNTHETIC) || GENERATED.matcher(field.getName()).matches()) {
                    continue;
                }
                check(type, "字段", field.getName(), problems);
            }
            for (JavaMethod method : type.getMethods()) {
                String name = method.getName();
                if (method.getModifiers().contains(JavaModifier.SYNTHETIC)
                        || method.getModifiers().contains(JavaModifier.BRIDGE)
                        || GENERATED.matcher(name).matches()
                        || (type.isEnum() && ENUM_METHODS.contains(name))
                        || namedByOutsideType(type, method)) {
                    continue;
                }
                check(type, "方法", name, problems);
            }
        }
        assertTrue(problems.isEmpty(), "成员名含有禁用词：\n" + String.join("\n", problems));
    }

    // 拆词后逐个对禁用词表；record 组件同时是字段和方法，结果去重。
    private static void check(JavaClass type, String kind, String name, Set<String> problems) {
        String member = type.getName().substring(ROOT.length() + 1) + "." + name;
        if (allowed.contains(member)) return;
        for (String word : words(name)) {
            String reason = bannedWords.get(word);
            if (reason != null) {
                problems.add(member + "（" + kind + "）含有 " + word + "：" + reason);
            }
        }
    }

    // 驼峰名首字母大写后切；全大写带下划线的常量按下划线切，每段转成首字母大写。
    static List<String> words(String name) {
        List<String> words = new ArrayList<>();
        if (name.equals(name.toUpperCase(Locale.ROOT))) {
            for (String part : name.split("_")) {
                if (part.isEmpty()) continue;
                words.add(part.charAt(0) + part.substring(1).toLowerCase(Locale.ROOT));
            }
            return words;
        }
        Matcher matcher = CAMEL_WORD.matcher(Character.toUpperCase(name.charAt(0)) + name.substring(1));
        while (matcher.find()) words.add(matcher.group());
        return words;
    }

    // 方法名由外部类型定：本类继承或实现的、不在本仓的类型里有同名同参数个数的方法。
    private static boolean namedByOutsideType(JavaClass type, JavaMethod method) {
        List<JavaClass> supertypes = new ArrayList<>(type.getAllRawSuperclasses());
        supertypes.addAll(type.getAllRawInterfaces());
        for (JavaClass supertype : supertypes) {
            if (supertype.getPackageName().startsWith(ROOT)) continue;
            for (JavaMethod inherited : supertype.getMethods()) {
                if (inherited.getName().equals(method.getName())
                        && inherited.getRawParameterTypes().size() == method.getRawParameterTypes().size()) {
                    return true;
                }
            }
        }
        return false;
    }
}
