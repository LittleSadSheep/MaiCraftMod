// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ftbquests;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.lang.reflect.Field;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import static org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestApi.call;

/** 将客户端已有的原生目标定义转换成 JSON；不执行条件检查或奖励回调，长整数始终保留精确字符串。 */
final class FtbQuestData {
    private FtbQuestData() {}
    static CompoundTag definition(Object object) {
        CompoundTag data = new CompoundTag(); call(object, "writeData", data, call(object, "holderLookup")); return data;
    }
    static JsonElement json(Tag tag) {
        if (tag == null) return JsonNull.INSTANCE;
        if (tag instanceof CompoundTag compound) {
            JsonObject result = new JsonObject();
            compound.getAllKeys().stream().sorted().forEach(key -> result.add(key, json(compound.get(key)))); return result;
        }
        if (tag instanceof CollectionTag<?> collection) {
            JsonArray result = new JsonArray(); collection.forEach(value -> result.add(json(value))); return result;
        }
        if (tag instanceof LongTag value) return new JsonPrimitive(Long.toString(value.getAsLong()));
        if (tag instanceof NumericTag value) return new JsonPrimitive(value.getAsNumber());
        return new JsonPrimitive(tag.getAsString());
    }
    static Object field(Object object, String name) {
        // 仅供适配器读取缺少 getter 的固定规则字段；调用方不能通过 Resource 指定任意反射目标。
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                if (!field.trySetAccessible()) break;
                return field.get(object);
            } catch (NoSuchFieldException inherited) {
                // 扩展对象继承原生字段时继续查父类，不触碰其可变状态。
            } catch (ReflectiveOperationException unavailable) { throw new IllegalStateException("Cannot read FTB " + name, unavailable); }
        }
        throw new IllegalStateException("Unavailable FTB field: " + name);
    }
}
