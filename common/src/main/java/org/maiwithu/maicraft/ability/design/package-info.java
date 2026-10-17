// SPDX-License-Identifier: GPL-3.0-only
/**
 * 建筑设计：LLM 画的图纸怎么变成逐格的计划格。图纸是材料表加对象（图元、组件实例、屋顶），
 * 经格式校验、组件展开、逐格采样与涂装、叠加结算，编出相对设计原点的格；图纸不绑地点，盖在哪由 build 决定。
 * 对外只露 {@code api} 子包：编译入口与编译结果。
 */
package org.maiwithu.maicraft.ability.design;
