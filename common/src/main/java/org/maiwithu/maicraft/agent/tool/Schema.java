package org.maiwithu.maicraft.agent.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 为内部工具编写参数格式说明：哪些字段必须填、是文字还是数字、范围多大。
 * 例如可以声明 count 必须是 1～64 的整数。这个类只生成说明，不会自己检查一次真实调用。
 * 字段更复杂时，工具可以自行返回 Map，不必使用这个辅助类。
 */
public final class Schema {

    private Schema() {}

    /** 无参数工具也接收一个对象，只是对象里不允许有字段。 */
    public static Map<String, Object> none() {
        return new Builder().build();
    }

    public static Builder object() {
        return new Builder();
    }

    // props 保存每个字段的格式；required 另外记录哪些字段必须给。
    // 例如声明数量为 1～64，只是在写格式说明，真正读数值和拒绝错误输入仍在工具执行处。
    public static final class Builder {
        private final Map<String, Object> props = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();

        public Builder integer(String name, String desc) {
            props.put(name, base("integer", desc));
            required.add(name);
            return this;
        }

        public Builder integer(String name, String desc, int min, int max) {
            Map<String, Object> p = base("integer", desc);
            p.put("minimum", min);
            p.put("maximum", max);
            props.put(name, p);
            required.add(name);
            return this;
        }

        public Builder number(String name, String desc, double min, double max) {
            Map<String, Object> p = base("number", desc);
            p.put("minimum", min);
            p.put("maximum", max);
            props.put(name, p);
            required.add(name);
            return this;
        }

        public Builder string(String name, String desc) {
            props.put(name, base("string", desc));
            required.add(name);
            return this;
        }

        /** 声明该文字字段可以完全不填；缺省后怎么处理由执行代码决定。 */
        public Builder optionalString(String name, String desc) {
            props.put(name, base("string", desc));
            return this;
        }

        /** 可以不填的整数，同时声明最小值和最大值。 */
        public Builder optionalInteger(String name, String desc, int min, int max) {
            Map<String, Object> p = base("integer", desc);
            p.put("minimum", min);
            p.put("maximum", max);
            props.put(name, p);
            return this;
        }

        public Builder optionalNumber(String name, String desc, double min) {
            Map<String, Object> p = base("number", desc);
            p.put("minimum", min);
            props.put(name, p);
            return this;
        }

        /** 可以不填；填了必须从列出的文字选项中选择。 */
        public Builder optionalEnum(String name, String desc, String... values) {
            Map<String, Object> p = base("string", desc);
            p.put("enum", List.of(values));
            props.put(name, p);
            return this;
        }

        /** 可选的文字列表，例如一组物品编号。 */
        public Builder optionalStringArray(String name, String desc) {
            Map<String, Object> items = new LinkedHashMap<>();
            items.put("type", "string");
            Map<String, Object> arr = new LinkedHashMap<>();
            arr.put("type", "array");
            arr.put("description", desc);
            arr.put("items", items);
            props.put(name, arr);
            return this;
        }

        /** 必填的文字列表；minItems 大于零时还要求至少有这么多项。 */
        public Builder stringArray(String name, String desc, int minItems) {
            Map<String, Object> items = new LinkedHashMap<>();
            items.put("type", "string");
            Map<String, Object> arr = new LinkedHashMap<>();
            arr.put("type", "array");
            arr.put("description", desc);
            arr.put("items", items);
            if (minItems > 0) arr.put("minItems", minItems);
            props.put(name, arr);
            required.add(name);
            return this;
        }

        /** 必填的整数列表。这里限制的是列表长度，不是每个整数的大小。 */
        public Builder intArray(String name, String desc, int minItems, int maxItems) {
            Map<String, Object> items = new LinkedHashMap<>();
            items.put("type", "integer");
            Map<String, Object> arr = new LinkedHashMap<>();
            arr.put("type", "array");
            arr.put("description", desc);
            arr.put("items", items);
            if (minItems > 0) arr.put("minItems", minItems);
            if (maxItems > 0) arr.put("maxItems", maxItems);
            props.put(name, arr);
            required.add(name);
            return this;
        }

        /** 与 intArray 一样声明列表长度，但整个字段可以不填。 */
        public Builder optionalIntArray(String name, String desc, int minItems, int maxItems) {
            Map<String, Object> items = new LinkedHashMap<>();
            items.put("type", "integer");
            Map<String, Object> arr = new LinkedHashMap<>();
            arr.put("type", "array");
            arr.put("description", desc);
            arr.put("items", items);
            if (minItems > 0) arr.put("minItems", minItems);
            if (maxItems > 0) arr.put("maxItems", maxItems);
            props.put(name, arr);
            return this;
        }

        public Builder bool(String name, String desc) {
            props.put(name, base("boolean", desc));
            required.add(name);
            return this;
        }

        /** 可选的真假开关；这里没有设置默认值，要看工具执行时如何处理缺省。 */
        public Builder optionalBool(String name, String desc) {
            props.put(name, base("boolean", desc));
            return this;
        }

        /** 必填的文字选项，值只能从给出的选项中选择。 */
        public Builder enumStr(String name, String desc, String... values) {
            Map<String, Object> p = base("string", desc);
            p.put("enum", List.of(values));
            props.put(name, p);
            required.add(name);
            return this;
        }

        /** 必填的对象列表；每个列表项都按 item 定义的字段和必填要求填写，不允许额外字段。 */
        public Builder objectArray(String name, String desc, java.util.function.Consumer<Builder> item) {
            Builder ib = new Builder();
            item.accept(ib);
            Map<String, Object> items = new LinkedHashMap<>();
            items.put("type", "object");
            items.put("properties", ib.props);
            items.put("required", List.copyOf(ib.required));
            items.put("additionalProperties", false);
            Map<String, Object> arr = new LinkedHashMap<>();
            arr.put("type", "array");
            arr.put("description", desc);
            arr.put("items", items);
            props.put(name, arr);
            required.add(name);
            return this;
        }

        // 生成整个参数对象的说明，并声明不接受未列出的字段。
        // properties 仍引用本 Builder 的字段表，所以生成后不应再用这个 Builder 追加字段。
        public Map<String, Object> build() {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("type", "object");
            root.put("properties", props);
            root.put("required", List.copyOf(required));
            root.put("additionalProperties", false);
            return root;
        }

        // 以下字段必须出现在对象里，但值可以写 null；这与 optional 方法允许完全省略不同。

        public Builder nullableNumber(String name, String desc) {
            props.put(name, nul("number", desc));
            required.add(name);
            return this;
        }

        public Builder nullableInteger(String name, String desc) {
            props.put(name, nul("integer", desc));
            required.add(name);
            return this;
        }

        public Builder nullableString(String name, String desc) {
            props.put(name, nul("string", desc));
            required.add(name);
            return this;
        }

        private static Map<String, Object> base(String type, String desc) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", type);
            p.put("description", desc);
            return p;
        }

        private static Map<String, Object> nul(String type, String desc) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", List.of(type, "null"));
            p.put("description", desc);
            return p;
        }
    }
}
