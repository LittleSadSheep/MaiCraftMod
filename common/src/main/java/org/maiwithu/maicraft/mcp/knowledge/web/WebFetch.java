// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge.web;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;

/** 检索和正文读取共享同一请求期限；测试用已知网页回放，不需要触碰角色或真实百科。 */
@FunctionalInterface
public interface WebFetch {
    Page get(URI uri, long deadlineNanos) throws IOException, InterruptedException;

    record Page(URI uri, String body, Instant fetchedAt, String lastModified, boolean cached) {
        Page fromCache() { return new Page(uri, body, fetchedAt, lastModified, true); }
    }
}
