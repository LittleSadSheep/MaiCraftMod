// SPDX-License-Identifier: GPL-3.0-only
/**
 * 寻死能力 {@code maicraft:suicide}：开着死亡不掉落时主动死一次回出生点或床边。
 * 先收起护甲与不死图腾，按死得快慢挑一种死法（现成岩浆、摔得死的落差、随身点火、仙人掌、敌对生物、随身倒岩浆），
 * 走过去接触；死了交给控制循环停摆与死亡恢复问题，重生后按原件穿回护甲。尝试期间任务自己关掉生存需求。
 */
package org.maiwithu.maicraft.ability.suicide;
