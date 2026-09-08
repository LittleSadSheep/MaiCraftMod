// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ponder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.world.phys.Vec3;

/** A foreign plugin API fixture; deliberately no hardcoded Create component or running world. */
public final class PonderFixture {
    public static int compiled;
    public static boolean brokenEntry;
    public static final Localization GLOBAL = new Localization();
    public interface SceneAccess { Collection<Map.Entry<String, Story>> getRegisteredEntries(); }
    public interface Story { String getComponent(); String getSchematicLocation(); List<String> getTags(); }
    public record Board(String component, String schematic) implements Story {
        public String getComponent() { return component; }
        public String getSchematicLocation() { return schematic; }
        public List<String> getTags() { return List.of("addon:automation"); }
    }
    public static final class Index {
        public static SceneAccess getSceneAccess() { return new Registry(); }
        public static Localization getLangAccess() { return GLOBAL; }
    }
    public static final class Registry implements SceneAccess {
        public Collection<Map.Entry<String, Story>> getRegisteredEntries() {
            List<Map.Entry<String, Story>> rows = new ArrayList<>(List.of(Map.entry("addon:machine", new Board("addon:machine", "addon:usage")),
                    Map.entry("addon:machine", new Board("addon:machine", "addon:usage"))));
            if (brokenEntry) rows.add(Map.entry("broken:item", new Board(null, "broken:scene")));
            return rows;
        }
        public static Scene compileScene(Localization localization, Story entry, Level world) {
            if (world != null) throw new AssertionError("Knowledge must not create a Ponder world");
            if (localization == GLOBAL) throw new AssertionError("Knowledge must not mutate global localization");
            compiled++;
            localization.specific.put("addon:tutorial", Map.of("header", "Custom addon tutorial", "text_1", "A source rule, not a guessed block rule."));
            return new Scene();
        }
    }
    public static final class Level {}
    public static final class Localization {
        public final Map<String, Map<String, String>> specific = new LinkedHashMap<>();
        public final Map<String, String> shared = new LinkedHashMap<>(Map.of("addon:shared_rule", "Shared source text."));
    }
    public static final class Scene {
        final List<Object> schedule = new ArrayList<>(List.of(new DelayInstruction(),
                new TextInstruction(() -> "addon.ponder.tutorial.text_1"),
                new ShowInputInstruction(), new TextInstruction(() -> "addon.ponder.shared.shared_rule"), new WorldModifyInstruction()));
        public String getId() { return "addon:tutorial"; }
        public String getTitle() { return "addon.ponder.tutorial.header"; }
        public void begin() { throw new AssertionError("Do not begin the scene"); }
        public void tick() { throw new AssertionError("Do not play the scene"); }
    }
    public static class TickingInstruction { protected int totalTicks = 10; }
    public static final class DelayInstruction extends TickingInstruction {}
    public static final class TextInstruction {
        private final TextWindowElement element;
        TextInstruction(Supplier<String> text) { element = new TextWindowElement(text); }
    }
    public static final class TextWindowElement {
        Supplier<String> textGetter; Vec3 vec = new Vec3(2, 3, 4);
        TextWindowElement(Supplier<String> text) { textGetter = text; }
    }
    public enum Icon { ICON_RMB }
    public static final class ShowInputInstruction { private final InputWindowElement element = new InputWindowElement(); }
    public static final class InputWindowElement {
        private final Vec3 sceneSpace = new Vec3(5, 6, 7);
        Object item = null; Object icon = Icon.ICON_RMB; Object key = "ponder:sneak_and";
    }
    public static final class WorldModifyInstruction {
        public void tick() { throw new AssertionError("Opaque world instructions must never execute"); }
    }
    public static ReflectivePonderAccess access() {
        return new ReflectivePonderAccess(new ReflectivePonderAccess.Api(Index.class, SceneAccess.class,
                Story.class, Localization.class, Registry.class, Level.class));
    }
}
