// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ponder;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Reads Ponder's presentation data only; never invokes scheduled world callbacks. */
public final class PonderInstructionReader {
    private PonderInstructionReader() {}

    public static PonderTranscript read(Object scene, Map<String, String> defaults) throws ReflectiveOperationException {
        String id = String.valueOf(scene.getClass().getMethod("getId").invoke(scene));
        String title = translated(String.valueOf(scene.getClass().getMethod("getTitle").invoke(scene)), defaults);
        List<PonderTranscript.Step> steps = new ArrayList<>();
        Map<String, Integer> unexpanded = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        Object raw = field(scene, "schedule");
        if (!(raw instanceof List<?> schedule)) throw new IllegalStateException("Ponder schedule is unavailable");
        int ticks = 0, ordinal = 0;
        for (Object instruction : schedule) {
            String type = instruction.getClass().getSimpleName();
            ordinal++;
            try {
                switch (type) {
                    case "DelayInstruction" -> ticks = Math.addExact(ticks, ((Number) field(instruction, "totalTicks")).intValue());
                    case "TextInstruction" -> {
                        Object element = field(instruction, "element");
                        Object getter = field(element, "textGetter");
                        if (!(getter instanceof Supplier<?> supplier)) throw new IllegalStateException("Ponder text supplier unavailable");
                        String text = translated(String.valueOf(supplier.get()), defaults);
                        steps.add(new PonderTranscript.Step(ordinal, ticks, "旁白", text, focus(field(element, "vec"))));
                    }
                    case "ShowInputInstruction" -> {
                        Object element = field(instruction, "element");
                        Object icon = field(element, "icon"), key = field(element, "key"), item = field(element, "item");
                        String symbol = icon instanceof Enum<?> value ? value.name() : icon == null ? "" : "自定义图标";
                        String action = switch (symbol) { case "ICON_LMB" -> "左键"; case "ICON_RMB" -> "右键"; case "ICON_SCROLL" -> "滚轮"; default -> symbol; };
                        String modifier = key == null ? "" : switch (key.toString()) {
                            case "ponder:sneak_and" -> "潜行";
                            case "ponder:ctrl_and" -> "Ctrl";
                            default -> key.toString();
                        };
                        StringBuilder text = new StringBuilder(modifier.isEmpty() ? "" : modifier + " + ").append(action);
                        if (item instanceof ItemStack stack && !stack.isEmpty()) text.append("；持物 `")
                                .append(BuiltInRegistries.ITEM.getKey(stack.getItem())).append("` × ").append(stack.getCount());
                        steps.add(new PonderTranscript.Step(ordinal, ticks, "控制提示", text.toString(), focus(field(element, "sceneSpace"))));
                    }
                    default -> unexpanded.merge(type, 1, Integer::sum);
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                unexpanded.merge(type, 1, Integer::sum);
                warnings.add("第 " + ordinal + " 条 " + type + " 未完整提取：" + unavailable.getClass().getSimpleName());
            }
        }
        return new PonderTranscript(id, title, steps, unexpanded, warnings);
    }

    static Object field(Object owner, String name) throws ReflectiveOperationException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                if (!field.trySetAccessible()) throw new IllegalAccessException("Cannot inspect Ponder " + name);
                return field.get(owner);
            } catch (NoSuchFieldException absent) { /* Search a superclass. */ }
        }
        throw new NoSuchFieldException(name);
    }

    private static String focus(Object value) { return value instanceof Vec3 vector ? vector.toString() : null; }
    private static String translated(String value, Map<String, String> defaults) { return defaults.getOrDefault(value, value); }
}
