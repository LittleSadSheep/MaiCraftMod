// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.param;

import java.util.List;
import java.util.Objects;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 一个参数的完整定义：名字、类型、是否必填、默认值、范围或选项、说明。
 * 能力说明里的参数表、MCP 入口的校验与规范化、能力拿到的带类型取值，都由这一份定义生成。
 *
 * @param name         参数名，必须已登记在 {@link ParamNames}
 * @param type         参数类型
 * @param required     是否必填；必填参数不能有默认值
 * @param defaultValue 省略时的取值；整数为 Long、数字为 Double、布尔为 Boolean、文字为 String、列表为 List
 * @param min          数值下限（含），只用于数值类型；null 表示不限
 * @param max          数值上限（含），只用于数值类型；null 表示不限
 * @param choices      可选值，只用于 CHOICE
 * @param doc          这个参数在本能力里的含义，写给 LLM 看
 */
public record ParamSpec(
        String name,
        ParamType type,
        boolean required,
        Object defaultValue,
        Long min,
        Long max,
        List<String> choices,
        String doc) {

    public ParamSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(doc, "doc");
        choices = choices == null ? List.of() : List.copyOf(choices);
        if (!ParamNames.contains(name)) {
            throw new IllegalArgumentException("参数名 " + name + " 不在参数名表里：先在 ParamNames 登记并写清含义，"
                    + "确认现有名字确实表达不了，再使用");
        }
        if (doc.isBlank()) throw new IllegalArgumentException("参数 " + name + " 缺少说明");
        if (required && defaultValue != null) {
            throw new IllegalArgumentException("必填参数 " + name + " 不能有默认值");
        }
        if ((min != null || max != null) && !type.isNumeric()) {
            throw new IllegalArgumentException("参数 " + name + " 不是数值类型，不能设上下限");
        }
        if (min != null && max != null && min > max) {
            throw new IllegalArgumentException("参数 " + name + " 的下限大于上限");
        }
        if (type == ParamType.CHOICE && choices.isEmpty()) {
            throw new IllegalArgumentException("选项参数 " + name + " 至少要有一个可选值");
        }
        if (type != ParamType.CHOICE && !choices.isEmpty()) {
            throw new IllegalArgumentException("只有选项参数可以声明可选值：" + name);
        }
        if (type == ParamType.CHOICE && defaultValue != null && !choices.contains(defaultValue)) {
            throw new IllegalArgumentException("参数 " + name + " 的默认值不在可选值里");
        }
        if (defaultValue != null && !matches(type, defaultValue)) {
            throw new IllegalArgumentException("参数 " + name + " 的默认值类型与 " + type + " 不符：" + defaultValue.getClass());
        }
    }

    // 默认值必须与解析后的取值类型一致，能力读参数时才不会因为默认值而拿到另一种类型。
    private static boolean matches(ParamType type, Object value) {
        return switch (type) {
            case INTEGER -> value instanceof Long;
            case NUMBER -> value instanceof Double;
            case BOOLEAN -> value instanceof Boolean;
            case TEXT, CHOICE, ITEM_OR_TAG, BLOCK_OR_TAG, ENTITY_TYPE -> value instanceof String;
            case ITEM_LIST, BLOCK_LIST, TEXT_LIST, ENTITY_TYPE_LIST -> value instanceof List<?>;
            case JSON_OBJECT -> value instanceof JsonObject;
            case JSON_ARRAY -> value instanceof JsonArray;
        };
    }

    /** 开始定义一个参数。 */
    public static Builder of(String name, ParamType type) {
        return new Builder(name, type);
    }

    /** 参数定义的构建器：{@code ParamSpec.of("count", ParamType.INTEGER).range(1, 256).defaultValue(1L).doc("……").build()}。 */
    public static final class Builder {
        private final String name;
        private final ParamType type;
        private boolean required;
        private Object defaultValue;
        private Long min;
        private Long max;
        private List<String> choices = List.of();
        private String doc = "";

        private Builder(String name, ParamType type) {
            this.name = name;
            this.type = type;
        }

        public Builder required() {
            required = true;
            return this;
        }

        /** 默认值；整数可以直接写 {@code 1}，数字可以直接写 {@code 1.5f}，这里会换成解析后的取值类型。 */
        public Builder defaultValue(Object value) {
            defaultValue = switch (value) {
                case Integer number -> (long) number;
                case Float number -> (double) number;
                case null, default -> value;
            };
            return this;
        }

        public Builder range(long lower, long upper) {
            min = lower;
            max = upper;
            return this;
        }

        public Builder choices(String... values) {
            choices = List.of(values);
            return this;
        }

        public Builder doc(String text) {
            doc = text;
            return this;
        }

        public ParamSpec build() {
            return new ParamSpec(name, type, required, defaultValue, min, max, choices, doc);
        }
    }
}
