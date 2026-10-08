// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个 Java 源文件的扫描视图：原文、剥掉注释与字面量后的"代码"、以及其中的字符串字面量。
 *
 * <p>剥离时保留换行，行号与原文一致；这样注释里的说明（例如写着 maicraft:sleep 的示例）
 * 不会被源码规则误判，而真正写在代码里的违规照样能被找到。
 */
final class JavaSource {
    final Path path;
    final String text;
    final List<String> lines;
    /** 剥掉注释、字符串、字符和文本块之后的代码，长度与原文相同、换行位置一致。 */
    final String code;
    /** 代码里出现的全部字符串字面量（含文本块）的内容。 */
    final List<String> strings;

    private JavaSource(Path path, String text) {
        this.path = path;
        this.text = text;
        this.lines = text.lines().toList();
        List<String> found = new ArrayList<>();
        this.code = strip(text, found);
        this.strings = List.copyOf(found);
    }

    static JavaSource read(Path path) {
        try {
            return new JavaSource(path, Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n"));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** 某个字符偏移所在的行号（从 1 开始）。 */
    int lineOf(int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    // 逐字符扫描：注释与字面量替换成空格（换行保留），字符串内容另存一份。
    private static String strip(String text, List<String> strings) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (text.startsWith("//", i)) {
                int end = text.indexOf('\n', i);
                end = end < 0 ? text.length() : end;
                blank(text, i, end, out);
                i = end;
            } else if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                end = end < 0 ? text.length() : end + 2;
                blank(text, i, end, out);
                i = end;
            } else if (text.startsWith("\"\"\"", i)) {
                int end = text.indexOf("\"\"\"", i + 3);
                end = end < 0 ? text.length() : end + 3;
                strings.add(text.substring(Math.min(i + 3, end), Math.max(i + 3, end - 3)));
                blank(text, i, end, out);
                i = end;
            } else if (c == '"' || c == '\'') {
                int end = i + 1;
                while (end < text.length() && text.charAt(end) != c && text.charAt(end) != '\n') {
                    end += text.charAt(end) == '\\' ? 2 : 1;
                }
                end = Math.min(end + 1, text.length());
                if (c == '"') strings.add(text.substring(i + 1, Math.max(i + 1, end - 1)));
                blank(text, i, end, out);
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static void blank(String text, int from, int to, StringBuilder out) {
        for (int i = from; i < to; i++) out.append(text.charAt(i) == '\n' ? '\n' : ' ');
    }
}
