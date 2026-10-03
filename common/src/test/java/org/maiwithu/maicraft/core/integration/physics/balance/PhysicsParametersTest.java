package org.maiwithu.maicraft.core.integration.physics.balance;

import com.google.gson.JsonParser;
import static org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBalanceRegression.*;

/** 施工需要完整补丁；未知模式、非整数方块位置和无效预测工况都应在接管角色前暴露。 */
final class PhysicsParametersTest {
    static void run() {
        var root=JsonParser.parseString("{\"structure_id\":\"00000000-0000-4000-8000-000000000001\"}").getAsJsonObject();
        check(PhysicsBalanceParameters.parse(root).operation().equals("analyze"),"默认只能分析");
        root.addProperty("operation","apply");
        rejects(()->PhysicsBalanceParameters.parse(root),"不能默认施工推荐结果");
        root.add("edits",JsonParser.parseString("[{\"position\":{\"x\":1,\"y\":-2,\"z\":3},\"block_id\":\"minecraft:iron_block\"}]"));
        var parsed=PhysicsBalanceParameters.parse(root); check(parsed.request().getAsJsonArray("edits").size()==1,"保留明确的局部补丁");
        root.getAsJsonArray("edits").get(0).getAsJsonObject().getAsJsonObject("position").addProperty("x",1.5);
        rejects(()->PhysicsBalanceParameters.parse(root),"不能把半格坐标截断成另一格");
        check(parsed.request().getAsJsonArray("edits").get(0).getAsJsonObject().getAsJsonObject("position").get("x").getAsInt()==1,"修改请求不能改变已冻结的方案");
        root.remove("edits");root.addProperty("operation","simulate");root.addProperty("perturbation_degrees",20);
        rejects(()->PhysicsBalanceParameters.parse(root),"扰动应小于验收倾角");
        root.remove("perturbation_degrees");root.addProperty("teleport",true);
        rejects(()->PhysicsBalanceParameters.parse(root),"不得接受直接改动物理位置的额外参数");
        // 飞行速度只能成为明确的试算工况，缺轴、字符串或越界速度不得默默补成另一种场景。
        root.remove("teleport");root.add("reference_velocity",JsonParser.parseString("{\"x\":10,\"y\":0,\"z\":-2}"));
        check(PhysicsBalanceParameters.parse(root).referenceVelocity().equals(v(10,0,-2)),"参考航速没有保留方向或大小");
        root.getAsJsonObject("reference_velocity").remove("y");
        rejects(()->PhysicsBalanceParameters.parse(root),"不完整航速被接受");
        root.add("reference_velocity",JsonParser.parseString("{\"x\":257,\"y\":0,\"z\":0}"));
        rejects(()->PhysicsBalanceParameters.parse(root),"超出试算范围的航速被接受");
        // 起飞前只能声明明确的刹车两端工况，缺字段和越界信号都不能偷偷补成另一种试算。
        root.remove("reference_velocity");
        root.add("wheel_brakes",JsonParser.parseString("{\"wheel:1\":{\"running\":0,\"stopped\":1}}"));
        check(PhysicsBalanceParameters.parse(root).wheelBrakes().get("wheel:1").equals(new PhysicsWheel.Brakes(0,1)),"刹车工况没有按来源冻结");
        for(String invalid:new String[]{"[]","{\"wheel:1\":{\"running\":0}}","{\"wheel:1\":{\"running\":-0.1,\"stopped\":1}}",
                "{\"wheel:1\":{\"running\":0,\"stopped\":2}}","{\"wheel:1\":{\"running\":\"0\",\"stopped\":1}}"}) {
            root.add("wheel_brakes",JsonParser.parseString(invalid));
            rejects(()->PhysicsBalanceParameters.parse(root),"无效刹车工况被接受: "+invalid);
        }
    }
    private static void rejects(Runnable action,String message) {
        try { action.run(); throw new AssertionError(message); } catch(IllegalArgumentException expected) { }
    }
}
