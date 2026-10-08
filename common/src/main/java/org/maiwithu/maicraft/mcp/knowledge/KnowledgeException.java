// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

/** 将 MCP 的资源未找到代码跨越客户端线程运行时边界传递。 */
public final class KnowledgeException extends IllegalArgumentException {
    private final int code;
    public KnowledgeException(int code, String message) { super(message); this.code = code; }
    public int code() { return code; }
    public static KnowledgeException missing(String uri) { return new KnowledgeException(-32002, "Resource not found: " + uri); }
}
