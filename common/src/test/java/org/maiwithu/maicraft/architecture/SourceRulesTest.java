// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.architecture;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 源码护栏（docs/design/02 第 7 节的 A8–A10，以及仓库的编码约定）：扫描源文件文本，违反时构建失败。
 *
 * <p>覆盖：许可证头、类型必须有中文类注释、禁止全限定名、能力 ID 字面量只出现在能力包里、
 * 类与方法的大小上限、测试不用 Unsafe 与反射改私有成员。大小上限的例外登记在测试资源
 * architecture-exemptions.txt 里，并写明理由。
 */
class SourceRulesTest {
    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    private static final List<Path> MAIN_ROOTS = List.of(
            REPO.resolve("common/src/main/java"),
            REPO.resolve("fabric/src/main/java"),
            REPO.resolve("neoforge/src/main/java"));
    private static final Path TEST_ROOT = REPO.resolve("common/src/test/java");
    private static final String LICENSE_HEADER = "// SPDX-License-Identifier: GPL-3.0-only";
    private static final int MAX_CLASS_LINES = 800;
    private static final int MAX_METHOD_LINES = 80;

    private static final Pattern TOP_LEVEL_TYPE = Pattern.compile(
            "^(?:public |final |abstract |sealed |non-sealed |strictfp )*(?:class|interface|enum|record|@interface) (\\w+)");
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final Pattern QUALIFIED_NAME = Pattern.compile(
            "\\b(?:java|javax|net|com|org|io|jdk|sun)\\.[a-z0-9_]+(?:\\.[a-z0-9_]+)*\\.[A-Z]\\w*");
    private static final Pattern ABILITY_ID = Pattern.compile("^maicraft:[a-z][a-z0-9_]*$");
    private static final Pattern METHOD_HEADER = Pattern.compile(
            "(?s)^(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:[\\w<>\\[\\],.?]+\\s+)*(\\w+)\\s*\\(.*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?$");
    private static final Set<String> NOT_METHODS = Set.of(
            "if", "for", "while", "switch", "catch", "synchronized", "try", "return", "new", "else", "do");

    private static List<JavaSource> mainSources;
    private static List<JavaSource> testSources;
    private static Set<String> sizeExemptions;

    @BeforeAll
    static void load() {
        mainSources = MAIN_ROOTS.stream().filter(Files::isDirectory).flatMap(SourceRulesTest::javaFiles)
                .map(JavaSource::read).toList();
        testSources = javaFiles(TEST_ROOT).map(JavaSource::read).toList();
        sizeExemptions = readExemptions();
        assertTrue(!mainSources.isEmpty(), "没有找到主源码，检查测试的工作目录是否为 common 模块");
    }

    @Test
    void everySourceFileStartsWithLicenseHeader() {
        List<String> problems = new ArrayList<>();
        for (JavaSource source : concat(mainSources, testSources)) {
            if (source.lines.isEmpty() || !source.lines.getFirst().equals(LICENSE_HEADER)) {
                problems.add(relative(source) + "：第一行应为 " + LICENSE_HEADER);
            }
        }
        report("缺少许可证头", problems);
    }

    @Test
    void everyTopLevelTypeHasChineseDocumentation() {
        List<String> problems = new ArrayList<>();
        for (JavaSource source : mainSources) {
            for (int i = 0; i < source.lines.size(); i++) {
                Matcher matcher = TOP_LEVEL_TYPE.matcher(source.lines.get(i));
                if (matcher.find() && !hasChineseDocAbove(source.lines, i)) {
                    problems.add(relative(source) + ":" + (i + 1) + " 类型 " + matcher.group(1)
                            + " 缺少中文类注释：写清它在游戏里负责什么、为什么存在");
                }
            }
        }
        report("缺少中文类注释", problems);
    }

    @Test
    void noFullyQualifiedNamesInCode() {
        List<String> problems = new ArrayList<>();
        for (JavaSource source : mainSources) {
            String[] codeLines = source.code.split("\n", -1);
            for (int i = 0; i < codeLines.length; i++) {
                String line = codeLines[i].trim();
                if (line.startsWith("package ") || line.startsWith("import ")) continue;
                Matcher matcher = QUALIFIED_NAME.matcher(codeLines[i]);
                if (matcher.find()) {
                    problems.add(relative(source) + ":" + (i + 1) + " 使用了全限定名 " + matcher.group()
                            + "；把类型写进 import，正文只用短名");
                }
            }
        }
        report("禁止全限定名", problems);
    }

    @Test
    void abilityIdLiteralsOnlyInAbilityPackages() {
        List<String> problems = new ArrayList<>();
        for (JavaSource source : mainSources) {
            boolean inAbility = source.path.toString().replace('\\', '/').contains("/maicraft/ability/");
            if (inAbility) continue;
            for (String literal : source.strings) {
                if (ABILITY_ID.matcher(literal).matches()) {
                    problems.add(relative(source) + " 写了能力 ID 字面量 \"" + literal
                            + "\"；能力 ID 只能出现在本能力的包里，其他地方查能力描述符上的属性");
                }
            }
        }
        report("能力 ID 字面量越界", problems);
    }

    @Test
    void classesAndMethodsStaySmall() {
        List<String> problems = new ArrayList<>();
        for (JavaSource source : mainSources) {
            if (sizeExemptions.contains(relative(source))) continue;
            if (source.lines.size() > MAX_CLASS_LINES) {
                problems.add(relative(source) + " 有 " + source.lines.size() + " 行，超过 " + MAX_CLASS_LINES
                        + " 行：拆阶段或拆出决策类");
            }
            for (int[] method : methods(source.code)) {
                int length = source.lineOf(method[2]) - source.lineOf(method[1]) + 1;
                if (length > MAX_METHOD_LINES) {
                    problems.add(relative(source) + ":" + source.lineOf(method[1]) + " 的方法有 " + length
                            + " 行，超过 " + MAX_METHOD_LINES + " 行：拆成命名清楚的小方法");
                }
            }
        }
        report("类或方法过长", problems);
    }

    @Test
    void testsDoNotHackIntoInternals() {
        List<String> problems = new ArrayList<>();
        for (JavaSource source : testSources) {
            if (source.code.contains("Unsafe") || source.code.contains(".setAccessible(")) {
                problems.add(relative(source) + " 使用了 Unsafe 或反射改私有成员；决策类用替身接口测试，执行器用子步骤替身测试");
            }
        }
        report("测试反射进私有实现", problems);
    }

    // 从类型声明行往上，跳过注解与空行，应当紧挨着一段含中文的 /** ... */。
    private static boolean hasChineseDocAbove(List<String> lines, int declaration) {
        int i = declaration - 1;
        while (i >= 0 && (lines.get(i).isBlank() || lines.get(i).trim().startsWith("@"))) i--;
        if (i < 0 || !lines.get(i).trim().endsWith("*/")) return false;
        StringBuilder doc = new StringBuilder();
        while (i >= 0) {
            doc.append(lines.get(i));
            if (lines.get(i).trim().startsWith("/**")) return CJK.matcher(doc).find();
            if (lines.get(i).trim().startsWith("/*")) return false;
            i--;
        }
        return false;
    }

    // 找出所有方法体：返回 {名字位置占位, 左花括号偏移, 右花括号偏移}。
    // 判断依据是左花括号前的"头部"像方法或构造器签名，且不是控制语句、类型声明、Lambda 或匿名类。
    private static List<int[]> methods(String code) {
        List<int[]> found = new ArrayList<>();
        int segmentStart = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == ';' || c == '}') {
                segmentStart = i + 1;
            } else if (c == '{') {
                String header = code.substring(segmentStart, i).trim();
                if (isMethodHeader(header)) {
                    int end = matchingBrace(code, i);
                    if (end > i) found.add(new int[]{0, i, end});
                }
                segmentStart = i + 1;
            }
        }
        return found;
    }

    private static boolean isMethodHeader(String header) {
        if (header.isEmpty() || header.contains("->") || header.contains("=")) return false;
        if (header.matches("(?s).*\\b(class|interface|enum|record|new)\\b.*")) return false;
        Matcher matcher = METHOD_HEADER.matcher(header);
        return matcher.matches() && !NOT_METHODS.contains(matcher.group(1));
    }

    private static int matchingBrace(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            if (code.charAt(i) == '{') depth++;
            else if (code.charAt(i) == '}' && --depth == 0) return i;
        }
        return -1;
    }

    private static Set<String> readExemptions() {
        Path file = REPO.resolve("common/src/test/resources/architecture-exemptions.txt");
        Set<String> paths = new HashSet<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = line.trim().split("\\s+", 3);
                if (parts.length == 3 && parts[0].equals("size")) paths.add(parts[1]);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return paths;
    }

    private static Stream<Path> javaFiles(Path root) {
        try {
            return Files.walk(root).filter(path -> path.toString().endsWith(".java")).toList().stream();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static List<JavaSource> concat(List<JavaSource> first, List<JavaSource> second) {
        List<JavaSource> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static String relative(JavaSource source) {
        return REPO.relativize(source.path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static void report(String rule, List<String> problems) {
        if (!problems.isEmpty()) fail(rule + "（" + problems.size() + " 处）：\n  " + String.join("\n  ", problems));
    }
}
