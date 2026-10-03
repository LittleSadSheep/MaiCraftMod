package org.maiwithu.maicraft.core.pathing.baritone;

import java.lang.reflect.Field;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import sun.misc.Unsafe;

/** 下车等待期间保持客户端乘坐事实，只有模拟服务器同步后才允许进入步行阶段。 */
public final class NavigationDismountTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (boolean serverConfirms : new boolean[]{true, false}) try (var world = new InteractionWorldTestHarness()) {
            // 此回归只观察乘坐引用，不初始化无关的渲染、特性开关和船只物理。
            var vehicle = (Boat) ((Unsafe) field(Unsafe.class, "theUnsafe").get(null)).allocateInstance(Boat.class);
            field(Entity.class, "vehicle").set(world.player, vehicle);
            var dismount = new NavigationDismount();
            check(dismount.tick(ClientRuntime.requireContext(world.player)) == NavigationDismount.State.WAITING,
                    "下车必须先等待同步，不能直接脱离载具");
            check(world.player.getVehicle() == vehicle && movement().sneaking(), "提交潜行输入后仍保留乘坐关系");
            world.nextTick();
            dismount.tick(ClientRuntime.requireContext(world.player));
            check(world.player.getVehicle() == vehicle && movement().sneaking(), "等待期间每刻续按，不能提前步行");
            // 仅测试夹具模拟服务器下车包；生产代码必须从真实乘客同步读取这个变化。
            if (serverConfirms) field(Entity.class, "vehicle").set(world.player, null);
            var state = NavigationDismount.State.WAITING;
            for (int tick = 0; tick < 40 && state == NavigationDismount.State.WAITING; tick++) {
                world.nextTick(); state = dismount.tick(ClientRuntime.requireContext(world.player));
            }
            check(state == (serverConfirms ? NavigationDismount.State.READY : NavigationDismount.State.FAILED),
                    "同步才完成；没有同步时有界结束，不伪造成功");
            check(movement().equals(BodyControlPort.Movement.STOPPED), "确认或超时都松开潜行键");
            check(serverConfirms || world.player.getVehicle() == vehicle, "超时不能擅自摘除乘坐关系");
        }
        System.out.println("NavigationDismountTest: passed");
    }
    private static BodyControlPort.Movement movement() throws Exception {
        var body = ClientRuntime.actor().body();
        return (BodyControlPort.Movement) field(body.getClass(), "movement").get(body);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }
    private static void check(boolean okay, String detail) { if (!okay) throw new AssertionError(detail); }
}
