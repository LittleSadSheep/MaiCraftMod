package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** 从已完成步骤中解析玩家所说的地点关系，匹配不唯一时保留未知，交还能力适配器处理。 */
final class PriorResultResolver {
    private PriorResultResolver() { }

    static Goal.WorldPosition resolve(
            IntentTaskRecord record, Goal.SemanticTarget target, String dimension) {
        // 从已成功的步骤里找位置。只有一个时直接使用；有多个时按名称和描述匹配，打平则不擅自选。
        List<ReportedPosition> candidates = new ArrayList<>();
        List<IntentTaskRecord.StepSnapshot> completed = record.stepResults();
        for (int index = completed.size() - 1; index >= 0; index--) {
            IntentTaskRecord.StepSnapshot step = completed.get(index);
            if (!step.success()) continue;
            Goal.WorldPosition internal = record.internalStepPosition(step.index());
            if (internal != null) {
                Goal sourceGoal = step.index() >= 0 && step.index() < record.steps().size()
                        ? record.steps().get(step.index()) : null;
                candidates.add(new ReportedPosition(step, sourceGoal, internal));
                continue;
            }
            JsonObject root;
            try {
                root = step.result();
            } catch (RuntimeException ignored) {
                continue;
            }
            JsonObject data = root.has("data") && root.get("data").isJsonObject()
                    ? root.getAsJsonObject("data") : root;
            Goal.WorldPosition position = nestedPosition(data, "verified_position", dimension);
            if (position == null) position = nestedPosition(data, "final_position", dimension);
            if (position == null) position = nestedPosition(data, "position", dimension);
            if (position == null && numbers(data, "final_x", "final_y", "final_z")) {
                position = new Goal.WorldPosition(
                        (int) Math.floor(data.get("final_x").getAsDouble()),
                        (int) Math.floor(data.get("final_y").getAsDouble()),
                        (int) Math.floor(data.get("final_z").getAsDouble()),
                        dimension);
            }
            if (position == null) continue;
            Goal sourceGoal = step.index() >= 0 && step.index() < record.steps().size()
                    ? record.steps().get(step.index()) : null;
            candidates.add(new ReportedPosition(step, sourceGoal, position));
        }
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.getFirst().position();

        String query = ((target.label() == null ? "" : target.label()) + " "
                + (target.relation() == null ? "" : target.relation())).strip();
        int bestScore = 0;
        ReportedPosition best = null;
        boolean ambiguous = false;
        for (ReportedPosition candidate : candidates) {
            int score = semanticScore(query, candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
                ambiguous = false;
            } else if (score > 0 && score == bestScore
                    && best != null && !best.position().equals(candidate.position())) {
                ambiguous = true;
            }
        }
        // 有多个已确认地点时，必须用关系说明区分；不能直接把最近一个无关地点当成“那里”。
        return bestScore > 0 && !ambiguous ? best.position() : null;
    }

    private static int semanticScore(String query, ReportedPosition candidate) {
        // 这是文字相似度打分，不是另一个大模型：描述、能力名和词片段重合越多，分数越高。
        if (query == null || query.isBlank()) return 0;
        String needle = normalizeSemanticText(query);
        IntentTaskRecord.StepSnapshot step = candidate.step();
        Goal goal = candidate.goal();
        String source = step.ability() + " " + step.message() + " " + step.resultJson();
        if (goal != null) source += " " + goal.toJson();
        String haystack = normalizeSemanticText(source);
        int score = 0;
        if (!needle.isBlank() && haystack.contains(needle)) score += 200;
        if (goal != null) {
            String outcome = normalizeSemanticText(goal.outcome());
            if (!outcome.isBlank() && (needle.contains(outcome) || outcome.contains(needle))) {
                score += 120;
            }
            String ability = goal.ability();
            int colon = ability.indexOf(':');
            String suffix = normalizeSemanticText(colon < 0 ? ability : ability.substring(colon + 1));
            if (!suffix.isBlank() && needle.contains(suffix)) score += 80;
        }
        for (String unit : semanticUnits(needle)) {
            if (haystack.contains(unit)) score += Math.min(24, 4 + unit.length() * 2);
        }
        return score;
    }

    private static List<String> semanticUnits(String normalized) {
        // 英文按空格拆词并排除 there 等泛指词；中文还拆出连续两个字，便于匹配较长描述的一部分。
        List<String> result = new ArrayList<>();
        for (String token : normalized.split(" +")) {
            if (token.length() < 2 || List.of(
                    "the", "that", "there", "prior", "previous", "result", "step",
                    "earlier", "place", "position", "from", "into").contains(token)) continue;
            result.add(token);
            int[] points = token.codePoints().toArray();
            if (points.length >= 2 && Arrays.stream(points).anyMatch(
                    point -> Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN)) {
                for (int index = 0; index + 1 < points.length; index++) {
                    result.add(new String(points, index, 2));
                }
            }
        }
        return result;
    }

    private static String normalizeSemanticText(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}:_./-]+", " ").strip();
    }

    private record ReportedPosition(
            IntentTaskRecord.StepSnapshot step, Goal goal, Goal.WorldPosition position) {}

    private static Goal.WorldPosition nestedPosition(JsonObject data, String key, String fallbackDimension) {
        if (!data.has(key) || !data.get(key).isJsonObject()) return null;
        JsonObject value = data.getAsJsonObject(key);
        if (!numbers(value, "x", "y", "z")) return null;
        String dimension = value.has("dimension") && value.get("dimension").isJsonPrimitive()
                ? value.get("dimension").getAsString()
                : fallbackDimension;
        return new Goal.WorldPosition(
                (int) Math.floor(value.get("x").getAsDouble()),
                (int) Math.floor(value.get("y").getAsDouble()),
                (int) Math.floor(value.get("z").getAsDouble()), dimension);
    }

    private static boolean numbers(JsonObject value, String x, String y, String z) {
        return value.has(x) && value.get(x).isJsonPrimitive()
                && value.has(y) && value.get(y).isJsonPrimitive()
                && value.has(z) && value.get(z).isJsonPrimitive();
    }
}
