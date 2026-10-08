// SPDX-License-Identifier: GPL-3.0-only
/**
 * 对外入口（L4）：MCP 传输、对外接口 v2 的五个工具、视图投影与知识库（docs/design/07）。
 *
 * <p>只负责协议、校验、投影与转发，不写业务规则。可以依赖：{@code kernel}、{@code behavior}（只读视图）、
 * 各能力的 {@code api}。不可以依赖：能力与联动的内部包。
 */
package org.maiwithu.maicraft.gateway;
