package org.maiwithu.maicraft.task.reflex;

/**
 * 旧的策略说明记录，只保存名字与描述。当前没有构造调用，CoreReflexes 只剩一条未使用的导入。
 */
public record PolicyReflex(String id, String describe) implements Reflex {}
