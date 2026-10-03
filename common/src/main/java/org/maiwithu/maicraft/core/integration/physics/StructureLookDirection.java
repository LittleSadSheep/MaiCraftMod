package org.maiwithu.maicraft.core.integration.physics;

import java.util.function.Function;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 坐在旋转的载具上时，把世界中的瞄准方向换回原生相机局部方向，实际转头仍由身体控制入口执行。 */
public final class StructureLookDirection {
    private static final String ROTATION="dev.ryanhcode.sable.mixinhelpers.camera.camera_rotation.EntitySubLevelRotationHelper";
    private static final String POSES="dev.ryanhcode.sable.mixinterface.clip_overwrite.LevelPoseProviderExtension";
    private StructureLookDirection() {}
    public static Vec3 toCamera(Entity observer,Vec3 worldDirection) {
        if(!NativeApi.present(ROTATION))return worldDirection;
        // 沿用 calculateViewVector 的姿态来源及 CAMERA 模式；解锁相机、普通步行和原生自定义旋转各按实际状态处理。
        Function<Object,Object> poses=ship->NativeApi.is(observer.level(),POSES)
                ?NativeApi.call(observer.level(),POSES,"sable$getPose",ship):NativeApi.call(ship,null,"logicalPose");
        var camera=(Quaterniondc)NativeApi.call(null,ROTATION,"getEntityOrientation",observer,poses,0.0F,
                NativeApi.enumValue(ROTATION+"$Type","CAMERA"));
        return inverse(worldDirection,camera);
    }
    static Vec3 inverse(Vec3 worldDirection,Quaterniondc camera) {
        if(camera==null)return worldDirection;
        if(!Double.isFinite(camera.lengthSquared())||camera.lengthSquared()<1e-12)
            throw new IllegalStateException("原生相机姿态无效，尚未提交视角输入");
        var local=camera.transformInverse(new Vector3d(worldDirection.x,worldDirection.y,worldDirection.z));
        return new Vec3(local.x,local.y,local.z);
    }
}
