package org.maiwithu.maicraft.core.task.physics;

import java.util.Map;

/** 重放胶层先到、耐久后到的实机顺序；同刻重复读取不能凭空构成稳定确认。 */
public final class BondMaterialSettlementTest {
    public static void run() {
        var before=material(99,false);var settle=new BondMaterialSettlement(before,100);
        check(!settle.observe(100,before)&&!settle.observe(101,before),"胶层先到时旧耐久被当作已结算");
        check(!settle.observe(102,material(97,false))&&!settle.observe(102,material(97,false)),"同刻重复读数被当成两刻稳定");
        check(settle.observe(103,material(97,false))&&settle.evidence().get("state").equals("material_change_observed"),"迟到的真实扣减没有被记录");
        var unknown=new BondMaterialSettlement(before,0);
        check(unknown.observe(40,before)&&unknown.evidence().get("state").equals("consumption_not_observed"),"未确认耗材不能伪报免费");
        var creative=new BondMaterialSettlement(material(99,true),0);
        check(!creative.observe(0,material(99,true))&&creative.observe(1,material(99,true)),"创造模式稳定的无扣减应单独结算");
    }
    private static Map<String,Object> material(long remaining,boolean creative) {return Map.of("count",1,"remaining_durability",remaining,"creative",creative);}
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
}
