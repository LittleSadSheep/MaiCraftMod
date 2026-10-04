package org.maiwithu.maicraft.core.integration.physics.flight;

import org.maiwithu.maicraft.core.pathing.transport.TransportSession;

/** 已开始驾驶但尚在着陆收尾的取消不能冒充无效果；已确认停稳与失去飞机也必须区别呈现。 */
final class FlightOutcomeReceiptTest {
    static void run() {
        var idle=AircraftFlightTask.outcomeFacts(null,false);
        var settling=AircraftFlightTask.outcomeFacts(null,true);
        var lost=AircraftFlightTask.outcomeFacts(TransportSession.Result.failed("lost","遥测不可读",true,true),true);
        var landed=AircraftFlightTask.outcomeFacts(TransportSession.Result.success("真实停稳"),true);
        check(Boolean.FALSE.equals(idle.get("outcome_uncertain")),"未发出驾驶输入不应产生虚假不确定性");
        check(Boolean.TRUE.equals(settling.get("effects_started"))&&Boolean.TRUE.equals(settling.get("outcome_uncertain")),"取消时实际控制仍未结算");
        check(Boolean.TRUE.equals(lost.get("outcome_uncertain")),"失去飞机的未知结果必须保留");
        check(Boolean.FALSE.equals(landed.get("outcome_uncertain"))&&Boolean.FALSE.equals(landed.get("mechanical_retry_allowed")),"确认停稳也不能机械重放起飞");
        System.out.println("FlightOutcomeReceiptTest: passed");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
