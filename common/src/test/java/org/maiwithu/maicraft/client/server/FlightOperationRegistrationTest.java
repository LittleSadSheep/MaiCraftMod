package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonObject;
import java.util.List;
import static org.maiwithu.maicraft.client.server.ServerRouterTestHarness.check;

/** 重现服务端已发布遥测、客户端漏登记的实机故障，使用生产注册函数完成真正的协议协商与只读派发。 */
public final class FlightOperationRegistrationTest {
    public static void run() {
        var missing=new ServerRouterTestHarness(true);
        welcome(missing);
        check(!missing.router.serverSupported("physics.flight_state"),"没有客户端契约时不能碰巧通过测试");
        var h=new ServerRouterTestHarness(true,MutationPersistence.NONE,ServerSessionRuntime::registerPhysicsObservations);
        welcome(h);
        check(h.router.serverSupported("physics.flight_state"),"生产初始化必须登记持续飞控遥测");
        var request=h.router.submit("physics.flight_state",new JsonObject(),false);h.advance(1);
        var sent=h.last("request");
        check(sent.get("operationId").getAsString().equals("physics.flight_state")&&!request.snapshot().mutating(),"飞控观察必须沿只读服务端通道派发");
        var reply=h.reply(request,"succeeded","not_applied","");var body=new JsonObject();body.addProperty("state","ready");reply.add("result",body);
        h.router.receive(reply,1);h.drain();
        check(request.snapshot().status()==ClientRequestReceipt.Status.SUCCEEDED,"真实路由器没有完成遥测回执");
        System.out.println("FlightOperationRegistrationTest: passed");
    }
    private static void welcome(ServerRouterTestHarness h) {
        var welcome=h.welcomeEnvelope("physics.snapshot","physics.assembly","physics.flight_state");
        for(String name:List.of("physics.snapshot","physics.assembly","physics.flight_state"))
            welcome.getAsJsonObject("features").getAsJsonObject(name).addProperty("mutating",false);
        h.router.receive(welcome,1);h.acknowledgeControl();
    }
}
