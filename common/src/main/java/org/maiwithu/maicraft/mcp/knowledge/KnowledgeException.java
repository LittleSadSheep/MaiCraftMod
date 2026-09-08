// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

/** Carries the MCP resource-not-found code across the client-thread runtime boundary. */
public final class KnowledgeException extends IllegalArgumentException {
    private final int code;
    public KnowledgeException(int code, String message) { super(message); this.code = code; }
    public int code() { return code; }
    public static KnowledgeException missing(String uri) { return new KnowledgeException(-32002, "Resource not found: " + uri); }
}
