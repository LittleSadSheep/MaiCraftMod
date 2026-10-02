// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 按站点给当前查询客户端的规则决定是否读取，不通过换身份绕开禁止访问的搜索路径。 */
final class RobotsRules {
    private record Rule(Pattern pattern, int length, boolean allow) {}
    private record Group(List<String> agents, List<Rule> rules, long delayMillis) {}
    private final List<Rule> rules = new ArrayList<>();
    private long delayMillis = 1000;

    RobotsRules(String text) {
        List<Group> groups = new ArrayList<>();
        List<String> agents = new ArrayList<>();
        List<Rule> entries = new ArrayList<>();
        boolean directives = false;
        long delay = 1000;
        for (String raw : text.replace("\uFEFF", "").split("\\R")) {
            String line = raw.split("#", 2)[0].strip();
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            if (key.equals("user-agent")) {
                if (directives) {
                    groups.add(new Group(agents, entries, delay));
                    agents = new ArrayList<>(); entries = new ArrayList<>(); delay = 1000; directives = false;
                }
                agents.add(value.toLowerCase(Locale.ROOT));
            } else if (!agents.isEmpty()) {
                directives = true;
                if ((key.equals("allow") || key.equals("disallow")) && !value.isEmpty()) {
                    String normalized = normalize(value);
                    boolean end = normalized.endsWith("$");
                    String path = end ? normalized.substring(0, normalized.length() - 1) : normalized;
                    String regex = "^" + String.join(".*", List.of(path.split("\\*", -1)).stream().map(Pattern::quote).toList()) + (end ? "$" : "");
                    entries.add(new Rule(Pattern.compile(regex), path.replace("*", "").length(), key.equals("allow")));
                } else if (key.equals("crawl-delay")) {
                    try { delay = Math.max(1000, (long) Math.ceil(Double.parseDouble(value) * 1000)); }
                    catch (NumberFormatException ignored) { /* 无效延迟保留默认间隔，不让角色密集访问站点。 */ }
                }
            }
        }
        groups.add(new Group(agents, entries, delay));
        int best = groups.stream().mapToInt(RobotsRules::specificity).max().orElse(-1);
        for (Group group : groups) if (best >= 0 && specificity(group) == best) {
            rules.addAll(group.rules()); delayMillis = Math.max(delayMillis, group.delayMillis());
        }
    }

    boolean allows(URI uri) {
        String path = normalize(uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        Rule selected = null;
        for (Rule rule : rules) if (rule.pattern().matcher(path).find()
                && (selected == null || rule.length() > selected.length() || rule.length() == selected.length() && rule.allow())) selected = rule;
        return selected == null || selected.allow();
    }

    long delayMillis() { return delayMillis; }

    private static int specificity(Group group) {
        return group.agents().stream().mapToInt(agent -> agent.equals("*") ? 0
                : !agent.isEmpty() && "maicraftknowledge".contains(agent) ? agent.length() : -1).max().orElse(-1);
    }

    // 站点规则与请求都归一化百分号编码，中文条目不能因编码方式不同而绕开同一条限制。
    private static String normalize(String value) {
        StringBuilder result = new StringBuilder();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 255;
            if (b == '%' && i + 2 < bytes.length) {
                int hi = Character.digit((char) bytes[i + 1], 16), lo = Character.digit((char) bytes[i + 2], 16);
                if (hi >= 0 && lo >= 0) {
                    b = hi * 16 + lo; i += 2;
                    if (b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b >= '0' && b <= '9' || "-._~".indexOf(b) >= 0) result.append((char) b);
                    else result.append("%%%02X".formatted(b));
                    continue;
                }
            }
            if (b >= 128) result.append("%%%02X".formatted(b)); else result.append((char) b);
        }
        return result.toString();
    }
}
