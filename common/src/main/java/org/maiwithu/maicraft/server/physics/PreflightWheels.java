package org.maiwithu.maicraft.server.physics;

import java.util.ArrayList;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBody;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsVector;

/** 用已观察轮胎和地面重算启停工况；缺少原生接地或配置证据时保留未知，不假装车轮已经装好。 */
final class PreflightWheels {
    private PreflightWheels() {}
    static PhysicsBody model(PhysicsBody source,PhysicsBlockEdits view,double rpm) {
        var loads=new ArrayList<PhysicsBody.Load>();var unknowns=new ArrayList<>(source.unknowns());boolean modeled=false;
        for(var load:source.loads()) {
            var wheel=load.wheel();
            if(wheel==null) {loads.add(load);continue;}
            if(wheel.mount()==null) {
                loads.add(load);unknowns.add("unmodeled:车轮 "+load.id()+" 缺少轮座位置，不能把轮胎施力点当作施工格");continue;
            }
            var mount=BlockPos.containing(wheel.mount().x(),wheel.mount().y(),wheel.mount().z()).offset(view.origin);
            var before=view.level.getBlockState(mount);var after=view.getBlockState(mount);
            if(after.getBlock()!=before.getBlock())continue;
            if(!after.equals(before)) {
                loads.add(load);unknowns.add("unmodeled:车轮 "+load.id()+" 朝向或配置补丁须在原生施工后重新读取接地和转向");continue;
            }
            if(!Set.of("contact","airborne","no_tire","no_ground").contains(wheel.contactState())) {
                loads.add(load);unknowns.add("unmodeled:车轮 "+load.id()+" 原生状态未完整确认: "+wheel.contactState());continue;
            }
            if(wheel.groundStructureId()!=null)unknowns.add("unmodeled:车轮 "+load.id()+" 支撑在另一物理结构上，尚未联合推进其支撑平面");
            // 替换已经归属的轮胎载荷，而非追加另一份支撑力；原生实测对象仍保持不变。
            loads.add(new PhysicsBody.Load(load.id(),load.group(),load.point(),PhysicsVector.ZERO,PhysicsVector.ZERO,
                    PhysicsBody.Frame.BODY,false,0,0,null,wheel.predictAt(rpm)));modeled=true;
        }
        if(modeled)unknowns.add("轮胎按原生悬挂、驱动、滚阻及刹车公式预测；支撑面和摩擦取当前命中样本，尚未复演前方坑坎、路面变化及车身碰撞");
        return new PhysicsBody(source.structureId(),source.dimension(),source.tick(),source.mass(),source.center(),source.inertia(),source.rotation(),
                source.position(),source.velocity(),source.angularVelocity(),source.gravity(),loads,unknowns);
    }
}
