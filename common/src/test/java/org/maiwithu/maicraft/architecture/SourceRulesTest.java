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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 源码检查：扫描源文件文本，违反时构建失败。
 *
 * <p>覆盖：许可证头、类型必须有中文类注释、禁止全限定名、能力 ID 字面量只出现在能力包里、
 * 类与方法的大小上限、测试不用 Unsafe 与反射改私有成员，以及命名：类型名不用空泛或容易误解的词、
 * 不再使用已经改掉的旧说法、注释不引用文档章节与版本号。
 *
 * <p>大小上限的例外登记在测试资源 architecture-exemptions.txt 里并写明理由；
 * 命名的词表在测试资源 naming-rules.txt 里，每个词都写了原因和改法。
 */
class SourceRulesTest {
    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    private static final List<Path> MAIN_ROOTS = List.of(
            REPO.resolve("common/src/main/java"),
            REPO.resolve("fabric/src/main/java"),
            REPO.resolve("neoforge/src/main/java"));
    // 内嵌 Baritone 是原样保留的第三方源码，上面的源码规则只约束本仓自己写的代码，不扫它的目录。
    private static final Path THIRD_PARTY_ROOT = REPO.resolve("common/src/main/java/baritone");
    // Litematica 与 Schematica 的编译垫片按第三方模组的原样类型名与结构编写，同样只约束本仓自己写的代码。
    private static final List<Path> THIRD_PARTY_ROOTS = List.of(
            THIRD_PARTY_ROOT,
            REPO.resolve("common/src/main/java/com/github"),
            REPO.resolve("common/src/main/java/fi"));
    private static final Path TEST_ROOT = REPO.resolve("common/src/test/java");
    private static final Path ABILITY_DOC_ROOT = REPO.resolve("common/src/main/resources");
    private static final String LICENSE_HEADER = "// SPDX-License-Identifier: GPL-3.0-only";
    private static final int MAX_CLASS_LINES = 800;
    private static final int MAX_METHOD_LINES = 80;

    private static final Pattern TOP_LEVEL_TYPE = Pattern.compile(
            "^(?:public |final |abstract |sealed |non-sealed |strictfp )*(?:class|interface|enum|record|@interface) (\\w+)");
    private static final Pattern DECLARED_TYPE = Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Z]\\w*)");
    private static final Pattern CAMEL_WORD = Pattern.compile("[A-Z]+(?![a-z])|[A-Z][a-z0-9]*");
    // 只认整词 Unsafe（sun.misc.Unsafe），不误伤 UNSAFE_TO_STOP 或 isUnsafe 这类名字。
    private static final Pattern UNSAFE = Pattern.compile("\\bUnsafe\\b");
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final Pattern QUALIFIED_NAME = Pattern.compile(
            "\\b(?:java|javax|net|com|org|io|jdk|sun)\\.[a-z0-9_]+(?:\\.[a-z0-9_]+)*\\.[A-Z]\\w*");
    private static final Pattern ABILITY_ID = Pattern.compile("^maicraft:[a-z][a-z0-9_]*$");
    // 文档位置会漂移、版本对比会过时：注释直接写清含义与原因，历史看 git log。
    private static final Pattern DOC_OR_VERSION_REFERENCE = Pattern.compile(
            "docs" + "/design/|第\\s*[0-9.]+\\s*节|\u00a7|\\bv[0-9]+\\b|WP-[0-9]");
    private static final Pattern METHOD_HEADER = Pattern.compile(
            "(?s)^(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:[\\w<>\\[\\],.?]+\\s+)*(\\w+)\\s*\\(.*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?$");
    private static final Set<String> NOT_METHODS = Set.of(
            "if", "for", "while", "switch", "catch", "synchronized", "try", "return", "new", "else", "do");

    private static List<JavaSource> mainSources;
    private static List<JavaSource> testSources;
    private static Set<String> sizeExemptions;
    private static Map<String, String> bannedTypeWords;
    private static Map<String, String> retiredTerms;

    @BeforeAll
    static void load() {
        mainSources = MAIN_ROOTS.stream().filter(Files::isDirectory).flatMap(SourceRulesTest::javaFiles)
                .map(JavaSource::read).toList();
        testSources = javaFiles(TEST_ROOT).map(JavaSource::read).toList();
        sizeExemptions = readExemptions();
        bannedTypeWords = readNamingRules("word");
        retiredTerms = readNamingRules("term");
        assertTrue(!mainSources.isEmpty(), "没有找到主源码，检查测试的工作目录是否为 common 模块");
    }

    @Test
    void everySourceFileStartsWithLicenseHeader() {
        List<String> problems = new ArrayList<>();
        for (JavaSource source : concat(mainSources, testSources)) {
            if (source.lines.isEmpty() || !source.lines.getFirst().equals(LICENSE_HEADER)) {
                problems.add(relative(source.path) + "：第一行应为 " + LICENSE_HEADER);
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
                    problems.add(relative(source.path) + ":" + (i + 1) + " 类型 " + matcher.group(1)
                            + " 缺少中文类注释：写清它在游戏里负责什么、为什么存在");
                }
            }
        }
        report("缺少中文类注释", problems);
    }

    @Test
    void testMethodNamesUseOneLanguagePerClass() {
        // 测试方法名写成一句场景，中文英文都行，但同一个测试类里统一一种，读测试报告时不来回切换。
        Pattern testMethod = Pattern.compile("@Test\\s+(?:@\\w+(?:\\([^)]*\\))?\\s+)*void\\s+([^\\s(]+)\\s*\\(");
        List<String> problems = new ArrayList<>();
        for (JavaSource source : testSources) {
            Matcher matcher = testMethod.matcher(source.code);
            int chinese = 0;
            int other = 0;
            while (matcher.find()) {
                if (matcher.group(1).codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
                    chinese++;
                } else {
                    other++;
                }
            }
            if (chinese > 0 && other > 0) {
                problems.add(relative(source.path) + " 里中文测试方法名 " + chinese + " 个、英文 " + other
                        + " 个；同一个测试类统一一种写法");
            }
        }
        report("测试方法名混用两种写法", problems);
    }

    @Test
    void noFullyQualifiedNamesInCode() {
        // 测试代码同样只用短名：替身与断言里的类型也写进 import。
        List<String> problems = new ArrayList<>();
        for (JavaSource source : concat(mainSources, testSources)) {
            String[] codeLines = source.code.split("\n", -1);
            for (int i = 0; i < codeLines.length; i++) {
                if (isImportOrPackage(codeLines[i])) continue;
                Matcher matcher = QUALIFIED_NAME.matcher(codeLines[i]);
                if (matcher.find()) {
                    problems.add(relative(source.path) + ":" + (i + 1) + " 使用了全限定名 " + matcher.group()
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
                    problems.add(relative(source.path) + " 写了能力 ID 字面量 \"" + literal
                            + "\"；能力 ID 只能出现在本能力的包里，其他地方查能力规格上的属性");
                }
            }
        }
        report("能力 ID 字面量越界", problems);
    }

    @Test
    void classesAndMethodsStaySmall() {
        List<String> problems = new ArrayList<>();
        for (JavaSource source : mainSources) {
            if (sizeExemptions.contains(relative(source.path))) continue;
            if (source.lines.size() > MAX_CLASS_LINES) {
                problems.add(relative(source.path) + " 有 " + source.lines.size() + " 行，超过 " + MAX_CLASS_LINES
                        + " 行：拆阶段或拆出判断类");
            }
            for (int[] method : methods(source.code)) {
                int length = source.lineOf(method[2]) - source.lineOf(method[1]) + 1;
                if (length > MAX_METHOD_LINES) {
                    problems.add(relative(source.path) + ":" + source.lineOf(method[1]) + " 的方法有 " + length
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
            if (UNSAFE.matcher(source.code).find() || source.code.contains(".setAccessible(")) {
                problems.add(relative(source.path) + " 使用了 Unsafe 或反射改私有成员；判断类用替身接口测试，任务用动作替身测试");
            }
        }
        report("测试反射进私有实现", problems);
    }

    @Test
    void typeNamesAvoidVagueOrMisleadingWords() {
        List<String> problems = new ArrayList<>();
        for (JavaSource source : concat(mainSources, testSources)) {
            Matcher declaration = DECLARED_TYPE.matcher(source.code);
            while (declaration.find()) {
                String name = declaration.group(1);
                Matcher word = CAMEL_WORD.matcher(name);
                while (word.find()) {
                    String reason = bannedTypeWords.get(word.group());
                    if (reason != null) {
                        problems.add(relative(source.path) + ":" + source.lineOf(declaration.start()) + " 类型 " + name
                                + " 含有 " + word.group() + "：" + reason);
                    }
                }
            }
        }
        report("类型名用了空泛或容易误解的词", problems);
    }

    @Test
    void retiredTermsAreNotUsed() {
        List<String> problems = new ArrayList<>();
        for (JavaSource source : concat(mainSources, testSources)) {
            findRetiredTerms(source.path, source.lines, problems);
        }
        for (Path doc : abilityDocs()) {
            findRetiredTerms(doc, readLines(doc), problems);
        }
        report("用了已经改掉的旧说法", problems);
    }

    @Test
    void commentsDoNotPointIntoDocsOrVersions() {
        List<String> problems = new ArrayList<>();
        for (JavaSource source : concat(mainSources, testSources)) {
            for (int i = 0; i < source.lines.size(); i++) {
                String line = source.lines.get(i);
                if (isImportOrPackage(line)) continue;
                Matcher matcher = DOC_OR_VERSION_REFERENCE.matcher(line);
                if (matcher.find()) {
                    problems.add(relative(source.path) + ":" + (i + 1) + " 引用了 " + matcher.group()
                            + "：文档位置会变、版本对比会过时，直接写清含义与原因");
                }
            }
        }
        report("注释引用文档章节或版本号", problems);
    }

    private static void findRetiredTerms(Path path, List<String> lines, List<String> problems) {
        for (int i = 0; i < lines.size(); i++) {
            for (Map.Entry<String, String> term : retiredTerms.entrySet()) {
                if (lines.get(i).contains(term.getKey())) {
                    problems.add(relative(path) + ":" + (i + 1) + " 出现「" + term.getKey() + "」：" + term.getValue());
                }
            }
        }
    }

    private static boolean isImportOrPackage(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("package ") || trimmed.startsWith("import ");
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
        Set<String> paths = new HashSet<>();
        for (String line : readLines(REPO.resolve("common/src/test/resources/architecture-exemptions.txt"))) {
            String[] parts = line.trim().split("\\s+", 3);
            if (parts.length == 3 && parts[0].equals("size")) paths.add(parts[1]);
        }
        return paths;
    }

    // naming-rules.txt 每行"种类 词 原因"，种类是 word（类型名用词）或 term（旧说法）。
    private static Map<String, String> readNamingRules(String kind) {
        Map<String, String> rules = new LinkedHashMap<>();
        for (String line : readLines(REPO.resolve("common/src/test/resources/naming-rules.txt"))) {
            String[] parts = line.trim().split("\\s+", 3);
            if (parts.length == 3 && parts[0].equals(kind)) rules.put(parts[1], parts[2]);
        }
        assertTrue(!rules.isEmpty(), "naming-rules.txt 里没有 " + kind + " 规则");
        return rules;
    }

    private static List<Path> abilityDocs() {
        if (!Files.isDirectory(ABILITY_DOC_ROOT)) return List.of();
        try (Stream<Path> files = Files.walk(ABILITY_DOC_ROOT)) {
            return files.filter(path -> path.toString().endsWith(".md")).toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Stream<Path> javaFiles(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> THIRD_PARTY_ROOTS.stream().noneMatch(
                            thirdParty -> path.toAbsolutePath().normalize().startsWith(thirdParty)))
                    .toList().stream();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static List<JavaSource> concat(List<JavaSource> first, List<JavaSource> second) {
        List<JavaSource> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static String relative(Path path) {
        return REPO.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static void report(String rule, List<String> problems) {
        if (!problems.isEmpty()) fail(rule + "（" + problems.size() + " 处）：\n  " + String.join("\n  ", problems));
    }
}
