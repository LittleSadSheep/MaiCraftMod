// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.TargetKind;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * 读目标里的 target：要去的地方或要处理的东西。全接口只有这一种"指一个地方或一个东西"的写法，
 * kind 决定还要哪些字段；能力不接受的 kind 在这里就报错，不等到任务里才发现。
 */
final class TargetReader {
    private static final List<String> KINDS = Arrays.stream(TargetKind.values())
            .map(kind -> kind.name().toLowerCase(Locale.ROOT)).toList();
    private static final List<String> TOWARDS = Arrays.stream(Target.Toward.values())
            .map(toward -> toward.name().toLowerCase(Locale.ROOT)).toList();

    private TargetReader() {}

    /**
     * @param accepted 能力接受的 kind；能力不认识时为 null，只检查写法、不检查能力接不接受
     */
    static Target read(JsonElement raw, String path, Set<TargetKind> accepted, RequestCheck check) {
        if (!raw.isJsonObject()) {
            check.error(path, "target 应该是一个对象", "{\"kind\": \"seen\", \"id\": \"b5\"}");
            return null;
        }
        JsonObject object = raw.getAsJsonObject();
        String kindName = check.choice(object, "kind", path + ".kind", KINDS);
        if (kindName == null) {
            if (!object.has("kind")) check.error(path + ".kind", "缺少 kind", String.join(" / ", KINDS));
            return null;
        }
        TargetKind kind = TargetKind.valueOf(kindName.toUpperCase(Locale.ROOT));
        if (accepted != null && !accepted.contains(kind)) {
            check.error(path + ".kind", "这个能力不接受 kind=" + kindName, accepted.isEmpty()
                    ? "这个能力不需要 target" : String.join(" / ", accepted.stream()
                    .map(value -> value.name().toLowerCase(Locale.ROOT)).sorted().toList()));
        }
        return switch (kind) {
            case HERE -> only(object, path, check, List.of("kind"), new Target.Here());
            case SEEN -> seen(object, path, check);
            case LANDMARK -> named(object, path, check, Target.Landmark::new);
            case PLAYER -> named(object, path, check, Target.Player::new);
            case POSITION -> position(object, path, check);
            case DIRECTION -> direction(object, path, check);
            case PREVIOUS -> previous(object, path, check);
        };
    }

    private static Target only(JsonObject object, String path, RequestCheck check, List<String> known, Target target) {
        check.rejectUnknownFields(object, path, known);
        return target;
    }

    private static Target seen(JsonObject object, String path, RequestCheck check) {
        check.rejectUnknownFields(object, path, List.of("kind", "id"));
        String id = check.text(object, "id", path + ".id", true);
        if (id == null) return null;
        String normalized = id.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[efb][0-9]+")) {
            check.error(path + ".id", "观察编号 " + id + " 写法不对", "observe 给出的编号，例如 e12、f3、b5");
            return null;
        }
        return new Target.Seen(normalized);
    }

    private static Target named(JsonObject object, String path, RequestCheck check,
                                Function<String, Target> create) {
        check.rejectUnknownFields(object, path, List.of("kind", "name"));
        String name = check.text(object, "name", path + ".name", true);
        return name == null ? null : create.apply(name);
    }

    private static Target position(JsonObject object, String path, RequestCheck check) {
        check.rejectUnknownFields(object, path, List.of("kind", "x", "y", "z", "dimension"));
        Integer x = check.integer(object, "x", path + ".x", true);
        Integer y = check.integer(object, "y", path + ".y", false);
        Integer z = check.integer(object, "z", path + ".z", true);
        String dimension = check.text(object, "dimension", path + ".dimension", false);
        return x == null || z == null ? null : new Target.Position(x, y, z, dimension);
    }

    private static Target direction(JsonObject object, String path, RequestCheck check) {
        check.rejectUnknownFields(object, path, List.of("kind", "toward", "distance"));
        String toward = check.choice(object, "toward", path + ".toward", TOWARDS);
        if (toward == null && !object.has("toward")) {
            check.error(path + ".toward", "缺少 toward", String.join(" / ", TOWARDS));
        }
        Integer distance = check.integer(object, "distance", path + ".distance", true);
        if (distance != null && distance <= 0) {
            check.error(path + ".distance", "distance 必须是正整数（格）", "例如 100");
            return null;
        }
        return toward == null || distance == null
                ? null : new Target.Direction(Target.Toward.valueOf(toward.toUpperCase(Locale.ROOT)), distance);
    }

    private static Target previous(JsonObject object, String path, RequestCheck check) {
        check.rejectUnknownFields(object, path, List.of("kind", "step"));
        Integer step = check.integer(object, "step", path + ".step", false);
        if (step != null && step < 0) {
            check.error(path + ".step", "step 不能是负数", "前面某一步的序号，从 0 开始");
            return null;
        }
        return new Target.Previous(step);
    }
}
