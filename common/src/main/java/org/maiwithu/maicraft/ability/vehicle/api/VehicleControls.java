// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.maiwithu.maicraft.ability.vehicle.spi.VehicleReader;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

/**
 * 操纵表：每个输入在开这辆载具时担任的角色（动力、刹车、升力、偏航……），带来源——推出来的、试按过的、LLM 定的，
 * 以后者为准；外加车头朝哪（结构自己的哪根水平轴，试按动力时看出来，或 LLM 定）。
 * 推出来的每次现读（machine_inspect 读回路），试过的与 LLM 定的按结构身份存在这个世界的文档库里，载具跟着结构走。
 * 机器能力（看、改）和驾驶能力共用这一份。
 */
public final class VehicleControls {

    /** 文档库里的范围名：操纵表都存这个范围下。 */
    static final String SCOPE = "vehicle-controls";
    /** 一份操纵表的存盘预算。 */
    private static final int DOCUMENT_LIMIT = 64 * 1024;

    /** 能担任的角色：动力、倒车、刹车、升力、下降、左右偏航、抬头低头、左右横滚、左右转向，none 是不用它。 */
    public static final List<String> ROLES = List.of("power", "reverse", "brake", "lift", "descend",
            "yaw_left", "yaw_right", "pitch_up", "pitch_down", "roll_left", "roll_right",
            "steer_left", "steer_right", "none");
    /** 车头能朝的结构自己的水平轴。 */
    public static final List<String> AXES = List.of("+x", "-x", "+z", "-z");

    /** 来源：推出来的、试按过的、LLM 定的。 */
    public static final String INFERRED = "inferred";
    public static final String TESTED = "tested";
    public static final String ASSIGNED = "llm";

    /**
     * 操纵表的一行。
     *
     * @param control 输入（打字机:W）
     * @param role    角色：{@link #ROLES} 之一；推出来但方向未定的转向写 steer，推不出写 unknown
     * @param source  来源：inferred / tested / llm
     * @param note    依据一句话
     */
    public record Entry(String control, String role, String source, String note) {
        public Entry {
            Objects.requireNonNull(control, "control");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(source, "source");
            note = note == null ? "" : note;
        }
    }

    /**
     * 存下来的部分：试过的与 LLM 定的行，和车头朝哪。
     *
     * @param entries       试过的、LLM 定的行
     * @param forward       车头朝结构自己的哪根水平轴（+x / -x / +z / -z）；不知道为空串
     * @param forwardSource 车头是怎么知道的：tested / llm；不知道为空串
     */
    public record Stored(List<Entry> entries, String forward, String forwardSource) {
        public static final Stored EMPTY = new Stored(List.of(), "", "");

        public Stored {
            entries = List.copyOf(entries);
            forward = forward == null ? "" : forward;
            forwardSource = forwardSource == null ? "" : forwardSource;
        }
    }

    /**
     * 合并后的操纵表：每个输入一行（LLM 定的 ← 试过的 ← 推出来的），车头，开这一类载具必需的角色缺哪几个。
     *
     * @param missing 缺的必需角色（中文一句一个）；种类说不清、或不是载具时写明
     */
    public record ControlMap(List<Entry> entries, String forward, String forwardSource, List<String> missing) {
        public ControlMap {
            entries = List.copyOf(entries);
            missing = List.copyOf(missing);
        }
    }

    private final DocumentStore documents;
    private final String worldKey;

    public VehicleControls(DocumentStore documents, String worldKey) {
        this.documents = Objects.requireNonNull(documents, "documents");
        this.worldKey = Objects.requireNonNull(worldKey, "worldKey");
    }

    /** 这辆存下来的部分；没存过、读不出来为空表。 */
    public Stored stored(String structureId) {
        try {
            String json = documents.read(SCOPE, worldKey, structureId, DOCUMENT_LIMIT);
            return json == null ? Stored.EMPTY : decode(json);
        } catch (IOException | RuntimeException broken) {
            return Stored.EMPTY;
        }
    }

    /** 记下 LLM 定的角色与车头（同一输入的旧行被替换）；存不进去返回假。 */
    public boolean assign(String structureId, Map<String, String> roles, String forward) {
        Stored before = stored(structureId);
        Map<String, Entry> rows = rows(before.entries());
        roles.forEach((control, role) -> rows.put(control, new Entry(control, role, ASSIGNED, "LLM 定的")));
        boolean newForward = forward != null && !forward.isBlank();
        return save(structureId, new Stored(List.copyOf(rows.values()), newForward ? forward : before.forward(),
                newForward ? ASSIGNED : before.forwardSource()));
    }

    /** 记下试按得到的角色与车头；LLM 定过的行和车头不动。存不进去返回假。 */
    public boolean tested(String structureId, List<Entry> entries, String forward) {
        Stored before = stored(structureId);
        Map<String, Entry> rows = rows(before.entries());
        for (Entry entry : entries) {
            Entry old = rows.get(entry.control());
            if (old == null || !old.source().equals(ASSIGNED)) rows.put(entry.control(), entry);
        }
        boolean keepForward = before.forwardSource().equals(ASSIGNED) || forward == null || forward.isBlank();
        return save(structureId, new Stored(List.copyOf(rows.values()), keepForward ? before.forward() : forward,
                keepForward ? before.forwardSource() : TESTED));
    }

    /** 结构换了身份（拆回再组装）：把存下来的部分搬到新身份上。 */
    public boolean move(String fromId, String toId) {
        Stored before = stored(fromId);
        return before == Stored.EMPTY || save(toId, before);
    }

    /**
     * 合并：推出来的打底，试过的盖上去，LLM 定的再盖上去；然后按种类查必需角色。
     *
     * @param kind     载具种类（machine_inspect 的 kind）
     * @param inferred 读回路推出来的输入
     */
    public static ControlMap merge(String kind, List<VehicleReader.Input> inferred, Stored stored) {
        Map<String, Entry> rows = new LinkedHashMap<>();
        for (VehicleReader.Input input : inferred) {
            rows.put(input.control(), new Entry(input.control(), input.role(), INFERRED, input.basis()));
        }
        // 存下来的每个输入只有一行（LLM 定的已经盖过试过的），直接盖在推出来的上面。
        for (Entry entry : stored.entries()) rows.put(entry.control(), entry);
        List<Entry> entries = List.copyOf(rows.values());
        return new ControlMap(entries, stored.forward(), stored.forwardSource(), missing(kind, entries));
    }

    /**
     * 开这一类载具必需的角色缺哪几个：飞艇要动力、升力、偏航（左右至少一个），车要动力、转向，船要动力、偏航。
     * 方向没定的转向不算有；种类说不清或不是载具写明开不了。
     */
    public static List<String> missing(String kind, List<Entry> entries) {
        Set<String> roles = entries.stream().map(Entry::role).collect(Collectors.toSet());
        List<String> missing = new ArrayList<>();
        switch (kind) {
            case "airship" -> {
                need(missing, roles, "动力（power）", "power");
                need(missing, roles, "升力（lift）", "lift");
                need(missing, roles, "偏航（yaw_left 或 yaw_right）", "yaw_left", "yaw_right");
            }
            case "car" -> {
                need(missing, roles, "动力（power）", "power");
                need(missing, roles, "转向（steer_left 或 steer_right）", "steer_left", "steer_right");
            }
            case "boat" -> {
                need(missing, roles, "动力（power）", "power");
                need(missing, roles, "偏航（yaw_left 或 yaw_right）", "yaw_left", "yaw_right");
            }
            case "plane" -> missing.add("固定翼还不会自动起降");
            case "structure" -> missing.add("没有座位或控制件，不是载具");
            default -> missing.add("种类说不清（" + kind + "），不知道开它要哪些角色");
        }
        return missing;
    }

    private static void need(List<String> missing, Set<String> roles, String what, String... any) {
        for (String role : any) {
            if (roles.contains(role)) return;
        }
        missing.add(what);
    }

    private static Map<String, Entry> rows(List<Entry> entries) {
        Map<String, Entry> rows = new LinkedHashMap<>();
        for (Entry entry : entries) rows.put(entry.control(), entry);
        return rows;
    }

    private boolean save(String structureId, Stored stored) {
        try {
            String json = encode(stored);
            if (json.getBytes(StandardCharsets.UTF_8).length > DOCUMENT_LIMIT) return false;
            return documents.write(SCOPE, worldKey, structureId, json, false);
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private static String encode(Stored stored) {
        JsonObject root = new JsonObject();
        JsonArray rows = new JsonArray();
        for (Entry entry : stored.entries()) {
            JsonObject row = new JsonObject();
            row.addProperty("control", entry.control());
            row.addProperty("role", entry.role());
            row.addProperty("source", entry.source());
            row.addProperty("note", entry.note());
            rows.add(row);
        }
        root.add("entries", rows);
        root.addProperty("forward", stored.forward());
        root.addProperty("forward_source", stored.forwardSource());
        return root.toString();
    }

    private static Stored decode(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<Entry> entries = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("entries")) {
            JsonObject row = element.getAsJsonObject();
            entries.add(new Entry(row.get("control").getAsString(), row.get("role").getAsString(),
                    row.get("source").getAsString(), row.get("note").getAsString()));
        }
        return new Stored(entries, root.get("forward").getAsString(), root.get("forward_source").getAsString());
    }
}
