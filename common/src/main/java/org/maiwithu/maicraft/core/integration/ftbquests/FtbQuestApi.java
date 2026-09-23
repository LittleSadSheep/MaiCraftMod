// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ftbquests;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.lang.reflect.Field;
import net.minecraft.network.chat.Component;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 集中读取 FTB 的任务属性；通过可选桥接避免未安装任务书的玩家被强制加载 FTB 类型。 */
final class FtbQuestApi {
    private FtbQuestApi() {}
    static Object call(Object owner, String method, Object... args) {
        return NativeApi.call(owner, null, method, args);
    }
    static boolean flag(Object owner, String method, Object... args) {
        return (Boolean) call(owner, method, args);
    }
    static String text(Object value) { return value instanceof Component component ? component.getString() : value.toString(); }
    static String id(Object value) {
        // FTB 的任务号是 64 位整数；传十六进制字符串，避免宿主以浮点数读取时串到另一个任务。
        return String.format(Locale.ROOT, "%016X", ((Number) call(value, "getId")).longValue());
    }
    static JsonObject identity(Object value) {
        JsonObject result = new JsonObject(); result.addProperty("id", id(value));
        result.addProperty("title", text(call(value, "getTitle"))); return result;
    }
    static JsonArray lines(Object values) {
        JsonArray result = new JsonArray();
        for (Object value : (Iterable<?>) values) result.add(text(value));
        return result;
    }
    static String dependencyRequirement(Object quest) {
        // FTB 未公开此前置规则的 getter；只读字段，避免调用会清理无效前置的 Quest.writeData。
        for (Class<?> type = quest.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField("dependencyRequirement");
                if (!field.trySetAccessible()) return "unknown";
                return call(field.get(quest), "getId").toString();
            } catch (NoSuchFieldException inherited) {
                // 扩展任务可能继承 Quest，继续查找声明规则的父类，不修改字段内容。
            } catch (ReflectiveOperationException | RuntimeException unavailable) { return "unknown"; }
        }
        return "unknown";
    }
}
