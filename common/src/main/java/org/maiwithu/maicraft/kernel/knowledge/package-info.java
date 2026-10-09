// SPDX-License-Identifier: GPL-3.0-only
/**
 * 知识来源：LLM 查资料的一个来源长什么样（KnowledgeSource），以及它交出来的资料条目与正文（KnowledgeDocument）。
 *
 * <p>接口放在内核，是因为联动模组也会提供资料（任务书、教程），而联动模组看不到 MCP 入口；
 * 目录、检索与读取仍在 MCP 入口的知识库里做。
 */
package org.maiwithu.maicraft.kernel.knowledge;
