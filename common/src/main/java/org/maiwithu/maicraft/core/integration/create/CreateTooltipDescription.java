// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Create's localized ItemDescription data, without keyboard state, font wrapping or a rendered GUI. */
public record CreateTooltipDescription(String translationKey, String summary, List<Detail> behaviours,
                                       List<Detail> controls, boolean truncated) {
    public record Detail(String condition, String explanation) {}
    private static final int MAX_CHARACTERS = 32768;

    public CreateTooltipDescription {
        behaviours = List.copyOf(behaviours);
        controls = List.copyOf(controls);
    }

    /** Translation lookup returns null for absent keys; numbering follows ItemDescription.fillBuilder. */
    public static CreateTooltipDescription read(String key, Function<String, String> translations) {
        Budget budget = new Budget();
        String summary = translations.apply(key + ".summary");
        if (summary == null) return new CreateTooltipDescription(key, "", List.of(), List.of(), false);
        String text = budget.take(summary, true);
        List<Detail> behaviours = details(key, "condition", "behaviour", translations, budget);
        List<Detail> controls = details(key, "control", "action", translations, budget);
        return new CreateTooltipDescription(key, text, behaviours, controls, budget.truncated);
    }

    private static List<Detail> details(String key, String condition, String explanation,
                                      Function<String, String> translations, Budget budget) {
        List<Detail> result = new ArrayList<>();
        for (int i = 1; i < 100 && !budget.truncated; i++) {
            String heading = translations.apply(key + "." + condition + i);
            if (heading == null) break;
            String body = translations.apply(key + "." + explanation + i);
            result.add(new Detail(budget.take(heading, false),
                    budget.take(body == null ? "（未提供对应说明）" : body, true)));
        }
        return result;
    }

    public boolean isEmpty() { return summary.isEmpty() && behaviours.isEmpty() && controls.isEmpty(); }

    public String searchText() {
        StringBuilder text = new StringBuilder(summary);
        for (Detail detail : behaviours) text.append(' ').append(detail.condition()).append(' ').append(detail.explanation());
        for (Detail detail : controls) text.append(' ').append(detail.condition()).append(' ').append(detail.explanation());
        return text.toString();
    }

    public String markdown() {
        if (isEmpty()) return "";
        StringBuilder text = new StringBuilder("## Create Shift / Ctrl 使用说明\n\n")
                .append("来源：当前语言资源中的 `").append(translationKey).append("`。\n\n")
                .append(summary).append("\n");
        append(text, "条件与行为（Shift）", behaviours);
        append(text, "操作（Ctrl）", controls);
        if (truncated) text.append("\n说明超过提取上限，部分内容未收录。\n");
        return text.toString();
    }

    private static void append(StringBuilder text, String title, List<Detail> details) {
        if (details.isEmpty()) return;
        text.append("\n### ").append(title).append("\n\n");
        for (Detail detail : details) text.append("- ").append(detail.condition()).append("：")
                .append(detail.explanation()).append('\n');
    }

    private static final class Budget {
        int remaining = MAX_CHARACTERS;
        boolean truncated;
        String take(String value, boolean highlightMarkup) {
            // Create strips underscores only in summary/body lines, not literal condition/control headings.
            String plain = (highlightMarkup ? value.replace("_", "") : value).replaceAll("§[0-9A-FK-ORa-fk-or]", "").strip();
            if (plain.length() > remaining) { plain = plain.substring(0, remaining); truncated = true; }
            remaining -= plain.length();
            return plain;
        }
    }

    /** Client-thread cache. A new language object invalidates both locale changes and resource-pack reloads. */
    public static final class Cache {
        private static final int CAPACITY = 1024;
        private Object revision;
        private final Map<String, CreateTooltipDescription> values = new LinkedHashMap<>(32, 0.75f, true);
        public CreateTooltipDescription read(Object currentRevision, String key, Function<String, String> translations) {
            if (revision != currentRevision) { values.clear(); revision = currentRevision; }
            CreateTooltipDescription result = values.get(key);
            if (result != null) return result;
            result = CreateTooltipDescription.read(key, translations);
            // Most registry items have no Create description. Avoid filling the cache with absent entries.
            if (!result.isEmpty()) {
                if (values.size() >= CAPACITY) values.remove(values.keySet().iterator().next());
                values.put(key, result);
            }
            return result;
        }
    }
}
