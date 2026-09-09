package org.maiwithu.maicraft.task.reflex;

/**
 * 给某条自动选择规则保存一个名字和一句说明。
 * 这个记录本身不会执行规则，也不会申请身体控制；真正的判断仍由使用该规则的代码负责。
 */
public record PolicyReflex(String id, String describe) implements Reflex {}
