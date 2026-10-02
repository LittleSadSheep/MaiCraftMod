// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import java.io.IOException;

/** 查资料失败只说明外部来源不可用，不能据此断言机器、物品或配方不存在。 */
public final class WebKnowledgeException extends IOException {
    private final String code;

    public WebKnowledgeException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
