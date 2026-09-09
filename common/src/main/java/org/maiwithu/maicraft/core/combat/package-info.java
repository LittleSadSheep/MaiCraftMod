/**
 * 战斗分为“判断当前局面”和“让玩家实际行动”。
 * Battlefield 保存当刻信息，AttackPlan 决定盯谁、近战、用弓或撤退。
 * Loadout 和 WeaponDamage 提供武器候选，Swing 给出挥击等待条件，Menace 估计危险范围，Haven 尝试选择撤退点。
 * 这些辅助类的数字多为近似和偏好，不保证覆盖所有 Mod 的特殊伤害、附魔或攻击方式。
 * 实际发出攻击、走位、举盾、等待动作结果和拾取掉落物的是 core.task.combat.AttackCompanionTask。
 * ShieldPlan 目前没有生产调用，不能把它保留的举盾规则误当成已经生效的行为。
 */
package org.maiwithu.maicraft.core.combat;
