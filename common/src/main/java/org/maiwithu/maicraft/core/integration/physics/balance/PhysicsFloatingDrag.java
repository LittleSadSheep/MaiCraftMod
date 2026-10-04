package org.maiwithu.maicraft.core.integration.physics.balance;

import java.util.List;
import org.joml.Matrix3d;

/** 按原生浮力材料的线性阻力重算蒙皮载荷，保留整片蒙皮转动时的阻尼力偶。 */
public record PhysicsFloatingDrag(double scale,double pressure,double horizontal,double vertical,
                                  boolean scaleWithGravity,double nativeStepSeconds,Spread spread) {
    public record Spread(double xx,double yy,double zz,double xy,double xz,double yz) {
        public Spread {
            if(!Double.isFinite(xx+yy+zz+xy+xz+yz)||xx<0||yy<0||zz<0)throw new IllegalArgumentException("蒙皮位置二阶矩无效");
        }
        public Matrix3d matrix(){return new Matrix3d(xx,xy,xz,xy,yy,yz,xz,yz,zz);}
    }
    public record Cell(PhysicsVector point,double scale) {
        public Cell {if(point==null||!Double.isFinite(scale)||scale<=0)throw new IllegalArgumentException("浮力材料位置或尺度无效");}
    }
    public record Cloud(PhysicsVector center,double scale,Spread spread) {}
    public record Response(PhysicsVector force,PhysicsVector couple) {}
    public PhysicsFloatingDrag {
        if(!Double.isFinite(scale+pressure+horizontal+vertical+nativeStepSeconds)||scale<=0||pressure<0
                ||horizontal<0||vertical<0||nativeStepSeconds<=0||spread==null)throw new IllegalArgumentException("原生线性阻力参数无效");
    }
    public static Cloud cloud(List<Cell> cells) {
        double total=0;PhysicsVector weighted=PhysicsVector.ZERO;
        for(var cell:cells){total+=cell.scale();weighted=weighted.add(cell.point().scale(cell.scale()));}
        if(total<=0||!Double.isFinite(total))throw new IllegalArgumentException("浮力材料组不能为空");
        var center=weighted.scale(1/total);double xx=0,yy=0,zz=0,xy=0,xz=0,yz=0;
        for(var cell:cells) {
            var r=cell.point().subtract(center);double weight=cell.scale()/total;
            // Sable 每格给位置二阶矩额外加 scale/6，不能只用格中心而丢掉单格也有的转动阻尼。
            xx+=weight*(r.x()*r.x()+1.0/6);yy+=weight*(r.y()*r.y()+1.0/6);zz+=weight*(r.z()*r.z()+1.0/6);
            xy+=weight*r.x()*r.y();xz+=weight*r.x()*r.z();yz+=weight*r.y()*r.z();
        }
        return new Cloud(center,total,new Spread(xx,yy,zz,xy,xz,yz));
    }
    public Response force(PhysicsVector localPointVelocity,PhysicsVector localAngularVelocity,PhysicsVector localGravity) {
        double gravity=localGravity.length(),coefficient=scale*pressure*(scaleWithGravity?gravity:1);
        Matrix3d drag;
        if(localGravity.dot(localGravity)>1e-5) {
            var g=localGravity;double k=(horizontal-vertical)/g.dot(g);
            drag=new Matrix3d(k*g.x()*g.x()-horizontal,k*g.x()*g.y(),k*g.x()*g.z(),
                    k*g.y()*g.x(),k*g.y()*g.y()-horizontal,k*g.y()*g.z(),
                    k*g.z()*g.x(),k*g.z()*g.y(),k*g.z()*g.z()-horizontal);
        } else drag=new Matrix3d().scaling(1-horizontal); // 保留原生零重力分支，不私自改成另一套阻力规则。
        drag.scale(coefficient);
        // 原生在浮力材料阶段前补偿 dt/2.1 的重力速度；静止时的小竖直偏置也必须按同一约定计算。
        var velocity=localPointVelocity.subtract(localGravity.scale(nativeStepSeconds/2.1));
        var force=PhysicsVector.of(drag.transform(velocity.mutable()));
        var x=spread.matrix();var yx=new Matrix3d(drag).mul(x);
        double tx=x.m00()+x.m11()+x.m22(),ty=drag.m00()+drag.m11()+drag.m22(),txy=yx.m00()+yx.m11()+yx.m22();
        var torqueMatrix=new Matrix3d().scaling(tx).sub(x).mul(new Matrix3d().scaling(ty).sub(drag))
                .sub(new Matrix3d().scaling(txy).sub(yx));
        return new Response(force,PhysicsVector.of(torqueMatrix.transform(localAngularVelocity.mutable())));
    }
}
