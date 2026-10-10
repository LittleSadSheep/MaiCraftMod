// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.param;

/**
 * 参数类型。每种类型的校验与规范化规则对所有能力一致，由 {@link ParamSpecs} 统一执行。
 *
 * <p>资源类参数只检查写法（命名空间:路径，标签以 # 开头）；该物品、方块是否真的已注册，
 * 由能力在决定每一步时结合实际安装的模组判断。
 */
public enum ParamType {
    /** 整数；"6" 这样的整数字符串会按 6 处理并在 notes 里说明。 */
    INTEGER("integer", false),
    /** 数字；数字字符串会按数字处理并在 notes 里说明。 */
    NUMBER("number", false),
    /** 布尔；"true"、"false" 字符串会按布尔处理并在 notes 里说明。 */
    BOOLEAN("boolean", false),
    /** 非空文字。 */
    TEXT("string", false),
    /** 从固定选项中选一个；大小写和首尾空白会整理。 */
    CHOICE("string", false),
    /** 物品 ID，或 # 开头的物品标签。 */
    ITEM_OR_TAG("string", false),
    /** 方块 ID，或 # 开头的方块标签。 */
    BLOCK_OR_TAG("string", false),
    /** 实体类型 ID（不接受标签）。 */
    ENTITY_TYPE("string", false),
    /** 多个物品 ID 或标签；只给一个字符串时按一项的列表处理。 */
    ITEM_LIST("array", true),
    /** 多个方块 ID 或标签；只给一个字符串时按一项的列表处理。 */
    BLOCK_LIST("array", true),
    /** 多段非空文字；只给一个字符串时按一项的列表处理。 */
    TEXT_LIST("array", true),
    /** 多个实体类型 ID（不接受标签）；只给一个字符串时按一项的列表处理。 */
    ENTITY_TYPE_LIST("array", true),
    /** 原样的 JSON 对象或数组：图纸、修改、逐格清单这类结构化正文；入口只认形状，内容由能力自己校验。 */
    JSON("object", false);

    private final String schemaType;
    private final boolean list;

    ParamType(String schemaType, boolean list) {
        this.schemaType = schemaType;
        this.list = list;
    }

    /** 能力说明里参数表的 JSON 类型名。 */
    public String schemaType() {
        return schemaType;
    }

    public boolean isList() {
        return list;
    }

    /** 是否是有上下限的数值类型。 */
    public boolean isNumeric() {
        return this == INTEGER || this == NUMBER;
    }
}
