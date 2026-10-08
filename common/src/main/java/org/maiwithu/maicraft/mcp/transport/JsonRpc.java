// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.transport;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.Set;

/**
 * JSON-RPC 2.0 的应答拼装与请求参数读取。
 *
 * <p>MCP 通过 application/json 传递事实；这里只做 JSON 必需转义，避免物品名称中的
 * 尖括号等字符膨胀为六字符 HTML 转义。错误码只使用 MCP 客户端实际会读到的那几个。
 */
final class JsonRpc {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private JsonRpc() {}

    static JsonObject success(JsonElement id, JsonElement result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", idOrNull(id));
        response.add("result", JsonRpc.nonNull(result));
        return response;
    }

    static JsonObject error(JsonElement id, int code, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        body.addProperty("message", message);
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", idOrNull(id));
        response.add("error", body);
        return response;
    }

    static JsonElement idOrNull(JsonElement id) {
        return id == null ? JsonNull.INSTANCE : id;
    }

    static JsonElement nonNull(JsonElement element) {
        return element == null ? JsonNull.INSTANCE : element;
    }

    /** params 缺省时按空对象处理；不是对象时按 Invalid Request 拒收。 */
    static JsonObject optionalObject(JsonElement element) {
        if (element == null || element.isJsonNull()) return new JsonObject();
        if (!element.isJsonObject()) throw new IllegalArgumentException("params must be an object");
        return element.getAsJsonObject();
    }

    static String requiredString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return value.getAsString();
    }

    static String nullableString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    static void optionalMeta(JsonObject params) {
        JsonElement meta = params.get("_meta");
        if (meta != null && !meta.isJsonNull() && !meta.isJsonObject()) {
            throw new IllegalArgumentException("_meta must be an object");
        }
    }

    /** 只接受列出的参数名；多余的参数说明调用方误解了接口，明确报错而不是悄悄忽略。 */
    static void only(JsonObject object, String... names) {
        Set<String> allowed = Set.of(names);
        object.keySet().forEach(name -> {
            if (!allowed.contains(name)) throw new IllegalArgumentException("unexpected parameter: " + name);
        });
    }

    /** 带JSON-RPC错误码的异常；在请求处理中途抛出，由入口统一转成错误应答。 */
    static final class RpcException extends RuntimeException {
        private final int code;

        RpcException(int code, String message) {
            super(message);
            this.code = code;
        }

        int code() {
            return code;
        }
    }
}
