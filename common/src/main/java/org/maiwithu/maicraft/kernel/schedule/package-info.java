// SPDX-License-Identifier: GPL-3.0-only
/**
 * 身体仲裁：每刻从需求（反射）和任务中选出唯一的身体使用者。
 *
 * <p>唯一规则：致命需求打断一切，有害需求打断空闲与忙碌，舒适需求只在空闲时插入（docs/design/03 的 M6）。
 * 没有第二个身体使用者：随行补光这类辅助动作也作为需求参与仲裁。
 */
package org.maiwithu.maicraft.kernel.schedule;
