// SPDX-License-Identifier: GPL-3.0-only
/**
 * 机器能力：看一台机器或一片机器区（machine_inspect）、审阅一份机器蓝图（machine_review）、
 * 改一台机器的设置（machine_configure）、用一台机器做东西（machine_run）、按机器蓝图施工（machine_build）。
 *
 * <p>机器能力只认 {@code spi} 包里的机器类型与网络读取器接口，不认具体是哪个模组的机器；
 * 没装对应联动时机器照常能看、能按蓝图放方块，操作认不出的机器如实说不支持。
 */
package org.maiwithu.maicraft.ability.machine;
