// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;

/**
 * 机械动力设置框的设置项，纯函数：键怎么起名、值怎么写、LLM 写的值换成 Create 设置界面里的哪一行哪个数。
 *
 * <p>键：过滤格 filter（黄铜隧道每面一格写 filter.north 这样），限件数 filter.amount；数值 value，选项 option；
 * 同一种有好几个时第二个起加 .2、.3。值：过滤格写物品 ID，空着写 empty；限件数写 any、&lt;=16、=16；
 * 数值写整数（转速带正负，时长按刻）；选项写名字。
 */
final class CreateValueSettings {

    static final String FILTER = "filter";
    static final String AMOUNT = "amount";
    static final String VALUE = "value";
    static final String OPTION = "option";
    static final String EMPTY = "empty";
    /** 时长按刻、秒、分三档：每档最多拨到 60。类别：游戏事实（BrassDiodeScrollValueBehaviour）。 */
    private static final int TICKS_PER_SECOND = 20;
    private static final int TICKS_PER_MINUTE = 1200;
    private static final int PER_UNIT = 60;

    private CreateValueSettings() {
    }

    /** 一项设置对应的是哪个框：过滤格（含限件数）还是数值框。 */
    record Target(CreateValues.FilterBox filter, boolean amount, CreateValues.ValueBox value) {
    }

    /** 全部设置项与现值。 */
    static List<MachineSetting> of(CreateValues.Boxes boxes) {
        List<MachineSetting> settings = new ArrayList<>();
        for (int i = 0; i < boxes.filters().size(); i++) {
            CreateValues.FilterBox box = boxes.filters().get(i);
            String key = filterKey(boxes, i);
            settings.add(new MachineSetting(key, filterValue(box.filter()), List.of(),
                    box.label() + "：写物品 ID 只让这样东西过（手持它点过滤格），写 empty 清空"));
            if (box.countVisible()) {
                settings.add(new MachineSetting(key + "." + AMOUNT, amountValue(box), List.of(),
                        box.label() + "的件数：any 不限，<=N 最多 N 件，=N 正好 N 件（N 不超过 " + box.maxCount() + "）"));
            }
        }
        for (int i = 0; i < boxes.values().size(); i++) {
            CreateValues.ValueBox box = boxes.values().get(i);
            settings.add(new MachineSetting(valueKey(boxes, i), shown(box), box.options(), describe(box)));
        }
        return settings;
    }

    /** 键对应哪个框；不认的键给空。 */
    static Optional<Target> target(CreateValues.Boxes boxes, String key) {
        for (int i = 0; i < boxes.filters().size(); i++) {
            String filterKey = filterKey(boxes, i);
            if (key.equals(filterKey)) return Optional.of(new Target(boxes.filters().get(i), false, null));
            if (key.equals(filterKey + "." + AMOUNT)) return Optional.of(new Target(boxes.filters().get(i), true, null));
        }
        for (int i = 0; i < boxes.values().size(); i++) {
            if (key.equals(valueKey(boxes, i))) return Optional.of(new Target(null, false, boxes.values().get(i)));
        }
        return Optional.empty();
    }

    /** 过滤格的读数：空着是 empty，不然是物品 ID。 */
    static String filterValue(ItemStack filter) {
        return filter.isEmpty() ? EMPTY : BuiltInRegistries.ITEM.getKey(filter.getItem()).toString();
    }

    /** 限件数的读数：不限是 any，最多是 &lt;=N，正好是 =N。 */
    static String amountValue(CreateValues.FilterBox box) {
        if (box.count() == 0 || box.upTo() && box.count() >= box.maxCount()) return "any";
        return (box.upTo() ? "<=" : "=") + box.count();
    }

    /** 数值框的读数：选项写名字，别的写数。 */
    static String shown(CreateValues.ValueBox box) {
        if (box.kind() == CreateValues.Kind.OPTION) {
            return box.value() >= 0 && box.value() < box.options().size() ? box.options().get(box.value())
                    : String.valueOf(box.value());
        }
        return String.valueOf(box.value());
    }

    /**
     * LLM 写的数值或选项换成设置界面里的哪一行、哪个数：普通数第 0 行；转速第 0 行是反着转（负）、第 1 行正着转；
     * 时长按刻、秒、分三行各 1 到 60；选项第 0 行、第几个。写不成的给出原因。
     */
    static Encoded encode(CreateValues.ValueBox box, String wanted) {
        if (box.kind() == CreateValues.Kind.OPTION) {
            int index = box.options().indexOf(wanted.toLowerCase(Locale.ROOT));
            return index < 0 ? Encoded.no("「" + box.label() + "」只能选 " + String.join("、", box.options()))
                    : Encoded.of(0, index);
        }
        Integer number = parse(wanted);
        if (number == null) return Encoded.no("「" + box.label() + "」要写整数：" + wanted);
        return switch (box.kind()) {
            case NUMBER -> number < 0 || number > box.max() ? Encoded.no("「" + box.label() + "」能设 0 到 " + box.max())
                    : Encoded.of(0, number);
            case SIGNED -> Math.abs(number) > box.max() ? Encoded.no("「" + box.label() + "」能设 -" + box.max() + " 到 " + box.max())
                    : Encoded.of(number < 0 ? 0 : 1, Math.abs(number));
            case TICKS -> ticks(box, number);
            case OPTION -> Encoded.no("选项不是数");
        };
    }

    /** 过滤格的限件数换成哪一行哪个数：第 0 行最多、第 1 行正好；any 是最多一整组。 */
    static Encoded encodeAmount(CreateValues.FilterBox box, String wanted) {
        String text = wanted.trim();
        if (text.equals("any")) return Encoded.of(0, box.maxCount());
        boolean upTo = text.startsWith("<=");
        boolean exactly = text.startsWith("=");
        Integer number = parse(text.substring(upTo ? 2 : exactly ? 1 : 0));
        if ((!upTo && !exactly) || number == null || number < 1 || number > box.maxCount()) {
            return Encoded.no("「" + box.label() + "」的件数写 any、<=N 或 =N，N 在 1 到 " + box.maxCount());
        }
        return Encoded.of(upTo ? 0 : 1, number);
    }

    /** 换算结果：哪一行哪个数，或者写不成的原因。 */
    record Encoded(int row, int value, String problem) {
        static Encoded of(int row, int value) {
            return new Encoded(row, value, null);
        }

        static Encoded no(String problem) {
            return new Encoded(0, 0, problem);
        }
    }

    // 时长：60 刻以内按刻；再长要是整秒（60 秒以内）或整分。
    private static Encoded ticks(CreateValues.ValueBox box, int ticks) {
        if (ticks >= 1 && ticks <= PER_UNIT) return Encoded.of(0, ticks);
        if (ticks % TICKS_PER_SECOND == 0 && ticks / TICKS_PER_SECOND <= PER_UNIT) {
            return Encoded.of(1, ticks / TICKS_PER_SECOND);
        }
        if (ticks % TICKS_PER_MINUTE == 0 && ticks / TICKS_PER_MINUTE <= PER_UNIT) {
            return Encoded.of(2, ticks / TICKS_PER_MINUTE);
        }
        return Encoded.no("「" + box.label() + "」按刻写：60 刻以内随便写，再长要是 20 的倍数（整秒）或 1200 的倍数（整分）");
    }

    private static String describe(CreateValues.ValueBox box) {
        return box.label() + "：" + switch (box.kind()) {
            case NUMBER -> "写 0 到 " + box.max() + " 的整数";
            case SIGNED -> "写 -" + box.max() + " 到 " + box.max() + " 的整数，正负是转向";
            case TICKS -> "按刻写（20 刻一秒），60 刻以上要是整秒或整分";
            case OPTION -> "写其中一个名字";
        };
    }

    // 同一种框有好几个时第二个起加序号；黄铜隧道每面一格的过滤格按那一面起名。
    private static String filterKey(CreateValues.Boxes boxes, int index) {
        CreateValues.FilterBox box = boxes.filters().get(index);
        if (box.side() != null) return FILTER + "." + box.side().getName();
        int order = 1;
        for (int i = 0; i < index; i++) if (boxes.filters().get(i).side() == null) order++;
        return order == 1 ? FILTER : FILTER + "." + order;
    }

    private static String valueKey(CreateValues.Boxes boxes, int index) {
        CreateValues.ValueBox box = boxes.values().get(index);
        boolean option = box.kind() == CreateValues.Kind.OPTION;
        int order = 1;
        for (int i = 0; i < index; i++) {
            if ((boxes.values().get(i).kind() == CreateValues.Kind.OPTION) == option) order++;
        }
        String base = option ? OPTION : VALUE;
        return order == 1 ? base : base + "." + order;
    }

    private static Integer parse(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException broken) {
            return null;
        }
    }
}
