// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ponder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.ponder.PonderReads.RegisteredScene;
import org.maiwithu.maicraft.compat.ponder.PonderReads.ShownInScene;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeNotReady;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;

/**
 * 思索当作资料来源：资料目录里一行索引；搜索与物品资料页的"相关资料"里每个场景一条；读一个场景给一篇。
 *
 * <p>一篇场景资料：标题、演示结构文件名、两句读法说明，旁白与操作提示按出现顺序、在关键帧处分段，
 * 最后一段 JSON 给演示结构（开场全部格）与每一段改了哪些格。要放一遍才知道这些：第一次读时在演示世界里放，
 * 一次读放不完就说"还在准备"，下一刻接着放；放完的按场景留着，换语言、换世界时作废重放。
 *
 * <p>碰 Ponder 的每一下都经联动入口：模组接口对不上时联动停用，这个来源回答"用不了"，不让查资料整体报错。
 */
final class PonderScenes implements KnowledgeSource {
    static final String PREFIX = "maicraft://knowledge/ponder/";
    static final String INDEX = PREFIX + "index";
    /** 一次读资料最多往下放多久；剩下的下一刻接着放。 */
    private static final long BUDGET_NANOS = 25_000_000L;
    /** 放过的场景最多留几个：一个场景的结构与旁白不大，但别无限攒。 */
    private static final int KEPT_SCENES = 32;

    private final CompatModule module;
    private final PonderReads reads;
    private final LongSupplier clock;
    private final Map<String, SceneRecorder> recordings = new LinkedHashMap<>(16, 0.75f, true);
    private String environment = "";

    PonderScenes(CompatModule module, PonderReads reads, LongSupplier clock) {
        this.module = Objects.requireNonNull(module, "module");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 一个场景的资料地址，例如 maicraft://knowledge/ponder/create/mechanical_mixer/1。 */
    static String uri(RegisteredScene scene) {
        return PREFIX + scene.component().replaceFirst(":", "/") + "/" + scene.number();
    }

    @Override public List<KnowledgeDocument.Entry> entries() {
        return List.of(new KnowledgeDocument.Entry(INDEX, "ponder.index", "思索（Ponder）",
                "Create 及其附属模组按 W 看到的演示：有思索的全部物品，每件几个场景",
                "思索 ponder 演示 教程 create 机械动力"));
    }

    // 每个场景一条：标题要放过才知道，这里用物品名加序号；按物品名、ID、演示结构名、思索标签搜得到。
    @Override public List<KnowledgeDocument.Entry> searchCandidates(String query) {
        List<KnowledgeDocument.Entry> entries = new ArrayList<>();
        for (RegisteredScene scene : scenes()) entries.add(entry(scene));
        return List.copyOf(entries);
    }

    @Override public List<KnowledgeDocument.Entry> entriesAbout(String registryId) {
        return scenes().stream().filter(scene -> scene.component().equals(registryId)).map(this::entry).toList();
    }

    @Override public KnowledgeDocument read(String uri) {
        if (uri.equals(INDEX)) return index();
        if (!uri.startsWith(PREFIX)) return null;
        Optional<RegisteredScene> scene = find(uri.substring(PREFIX.length()));
        if (scene.isEmpty()) return null;
        return scene(uri, scene.get());
    }

    @Override public String status() {
        try {
            List<RegisteredScene> scenes = scenes();
            long items = scenes.stream().map(RegisteredScene::component).distinct().count();
            return "思索（Ponder）：可用，" + scenes.size() + " 个场景、" + items + " 件物品";
        } catch (RuntimeException failure) {
            return "思索（Ponder）：读不到场景注册表（" + failure.getMessage() + "）";
        }
    }

    private List<RegisteredScene> scenes() {
        return module.call("列思索场景", reads::scenes);
    }

    private KnowledgeDocument.Entry entry(RegisteredScene scene) {
        String name = module.call("读物品名字", () -> reads.itemName(scene.component()));
        return new KnowledgeDocument.Entry(uri(scene), "ponder.scene", name + " · 思索场景 " + scene.number(),
                "思索场景 · 演示结构 " + scene.schematic(),
                scene.component() + " " + name + " " + scene.schematic() + " " + String.join(" ", scene.tags()) + " 思索 ponder");
    }

    // 地址末尾是序号，前面是物品的命名空间与路径。
    private Optional<RegisteredScene> find(String tail) {
        int last = tail.lastIndexOf('/');
        int first = tail.indexOf('/');
        if (last <= 0 || first == last) return Optional.empty();
        String component = tail.substring(0, first) + ":" + tail.substring(first + 1, last);
        String number = tail.substring(last + 1);
        if (!number.matches("[1-9][0-9]{0,3}")) return Optional.empty();
        return scenes().stream()
                .filter(scene -> scene.component().equals(component) && scene.number() == Integer.parseInt(number))
                .findFirst();
    }

    // 有思索的全部物品，按命名空间分组，开头给总数；不截断。
    private KnowledgeDocument index() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RegisteredScene scene : scenes()) counts.merge(scene.component(), 1, Integer::sum);
        StringBuilder text = new StringBuilder("# 思索（Ponder）\n\n");
        text.append("共 ").append(counts.size()).append(" 件物品有思索场景，")
                .append(counts.values().stream().mapToInt(Integer::intValue).sum()).append(" 个场景。")
                .append("读一个场景：lookup(topic=\"knowledge\", id=\"").append(PREFIX).append("<命名空间>/<路径>/<序号>\")，")
                .append("序号按注册顺序编，和思索界面里的先后不一定一致；")
                .append("也可以先读物品资料页（id 给物品 ID），那里列着它的场景。\n");
        String namespace = "";
        for (Map.Entry<String, Integer> row : counts.entrySet()) {
            String current = row.getKey().substring(0, row.getKey().indexOf(':'));
            if (!current.equals(namespace)) {
                namespace = current;
                text.append("\n## ").append(namespace).append('\n');
            }
            text.append("- ").append(module.call("读物品名字", () -> reads.itemName(row.getKey())))
                    .append("（").append(row.getKey()).append("）：").append(row.getValue()).append(" 个场景\n");
        }
        return new KnowledgeDocument(INDEX, "ponder.index", "思索（Ponder）", "有思索的全部物品", text.toString());
    }

    // 一个场景：放过就直接给；没放完先往下放一点，还没完就说"还在准备"，下一刻再来。放不出来如实说原因。
    private KnowledgeDocument scene(String uri, RegisteredScene scene) {
        String now = module.call("读游戏语言与世界", reads::environment);
        if (!now.equals(environment)) {
            recordings.clear();
            environment = now;
        }
        String key = uri(scene);
        SceneRecorder recorder = recordings.get(key);
        try {
            if (recorder == null) {
                recorder = new SceneRecorder(module.call("开始放思索场景", () -> reads.play(scene)), clock);
                recordings.put(key, recorder);
                while (recordings.size() > KEPT_SCENES) recordings.remove(recordings.keySet().iterator().next());
            }
            SceneRecorder running = recorder;
            if (!running.done() && !module.call("放思索场景", () -> running.advance(BUDGET_NANOS))) {
                throw new KnowledgeNotReady(running.progress());
            }
        } catch (KnowledgeNotReady preparing) {
            throw preparing;
        } catch (RuntimeException failure) {
            recordings.remove(key);
            return new KnowledgeDocument(uri, "ponder.scene", title(scene), "思索场景",
                    "# " + title(scene) + "\n\n这一场景放不出来：" + failure.getMessage()
                            + "。这不代表这件东西没有用法，可以读它的物品资料页，或者换它的另一个场景。\n");
        }
        return new KnowledgeDocument(uri, "ponder.scene", recorder.title(), "思索场景", SceneText.of(uri, scene,
                title(scene), recorder));
    }

    private String title(RegisteredScene scene) {
        return module.call("读物品名字", () -> reads.itemName(scene.component())) + "的第 " + scene.number() + " 个思索场景";
    }

    /** 一篇场景资料的正文：旁白分段写成文字，结构写成 JSON。 */
    static final class SceneText {
        private SceneText() {}

        static String of(String uri, RegisteredScene scene, String where, SceneRecorder recorder) {
            StringBuilder text = new StringBuilder("# ").append(recorder.title()).append("（").append(where).append("）\n\n");
            text.append("演示结构：").append(scene.schematic()).append("；场景 ID：").append(recorder.sceneId()).append('\n');
            // 序号按注册顺序编，思索界面会再按作者给的先后排：说清楚，免得拿界面上的第几个来对。
            text.append("序号按注册顺序编，和思索界面里的先后不一定一致。\n");
            text.append("读法：思索里的创造马达、创造物品栏代表外部的动力与材料输入，不是要照搬的建材；")
                    .append("演示里的等待刻数是动画节奏，不是加工速度。\n");
            if (recorder.cutShort()) {
                text.append("这一场景超过 ").append(SceneRecorder.MAX_TICKS).append(" 刻还没放完，后面的没有记下。\n");
            }
            List<SceneRecorder.Line> lines = recorder.lines();
            long unreadable = lines.stream().filter(line -> !line.shown().readable()).count();
            if (unreadable > 0) {
                text.append("有 ").append(unreadable).append(" 段读不到内容（装的 Ponder 版本和实测过的对不上），只知道那里出现了一段。\n");
            }
            text.append("\n旁白与操作提示（按出现顺序；关键帧处分段）\n");
            for (SceneRecorder.Segment segment : recorder.segments()) {
                if (segment.lines().isEmpty()) continue;
                text.append("  第 ").append(segment.index()).append(" 段\n");
                for (int number : segment.lines()) {
                    text.append("    ").append(number).append(". ").append(words(lines.get(number - 1).shown())).append('\n');
                }
            }
            text.append("\n结构（offset 相对演示世界的最小角；某一段结束时的方块 = blocks 加上到这一段为止的改动）：\n```json\n")
                    .append(new GsonBuilder().disableHtmlEscaping().create().toJson(structure(uri, recorder))).append("\n```\n");
            return text.toString();
        }

        // 操作提示写成人话：潜行 + 右键，手持 扳手（create:wrench）×1。
        static String words(ShownInScene shown) {
            if (!shown.readable()) return shown.isInput() ? "〔操作提示〕读不到内容" : "读不到内容";
            if (!shown.isInput()) return shown.text();
            String input = switch (shown.input()) {
                case "ICON_LMB" -> "左键";
                case "ICON_RMB" -> "右键";
                case "ICON_SCROLL" -> "滚轮";
                default -> "图标 " + shown.input();
            };
            String modifier = switch (shown.modifier()) {
                case "" -> "";
                case "ponder:sneak_and" -> "潜行 + ";
                case "ponder:ctrl_and" -> "Ctrl + ";
                default -> shown.modifier() + " + ";
            };
            String held = shown.itemId().isEmpty() ? "" : "，手持 " + shown.itemId() + " ×" + shown.count();
            return "〔操作提示〕" + modifier + input + held;
        }

        private static JsonObject structure(String uri, SceneRecorder recorder) {
            JsonObject json = new JsonObject();
            json.addProperty("scene", uri);
            json.add("size", position(recorder.size()));
            JsonArray blocks = new JsonArray();
            recorder.opening().forEach((position, state) -> blocks.add(block(position, state)));
            json.add("blocks", blocks);
            JsonArray keyframes = new JsonArray();
            for (SceneRecorder.Segment segment : recorder.segments()) {
                JsonObject row = new JsonObject();
                row.addProperty("index", segment.index());
                JsonArray narration = new JsonArray();
                segment.lines().forEach(narration::add);
                row.add("narration", narration);
                JsonArray changed = new JsonArray();
                segment.changed().forEach((position, state) -> changed.add(block(position, state)));
                row.add("changed", changed);
                JsonArray removed = new JsonArray();
                segment.removed().forEach(position -> removed.add(position(position)));
                row.add("removed", removed);
                keyframes.add(row);
            }
            json.add("keyframes", keyframes);
            return json;
        }

        private static JsonObject block(BlockPos position, BlockState state) {
            JsonObject block = new JsonObject();
            block.add("offset", position(position));
            block.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            JsonObject properties = new JsonObject();
            for (Property<?> property : state.getProperties()) {
                properties.addProperty(property.getName(), value(state, property));
            }
            block.add("properties", properties);
            return block;
        }

        private static <T extends Comparable<T>> String value(BlockState state, Property<T> property) {
            return property.getName(state.getValue(property));
        }

        private static JsonArray position(BlockPos position) {
            JsonArray array = new JsonArray();
            array.add(position.getX());
            array.add(position.getY());
            array.add(position.getZ());
            return array;
        }
    }
}
