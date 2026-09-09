package org.maiwithu.maicraft.task.reflex;

/**
 * 一项自动行为的名字和说明，例如“缺氧时找地方换气”。
 * 这里只负责说明；实现此接口不会自动获得开关、保存设置或执行机会。
 * 需要操作玩家的行为，还必须实现并登记实际的 TaskChain。
 */
public interface Reflex {

    /** 名册中的唯一名字，用来避免重复登记。 */
    String id();

    /** 一句中文说明，例如“下落危险时尝试防摔”，供名册汇总展示。 */
    String describe();
}
