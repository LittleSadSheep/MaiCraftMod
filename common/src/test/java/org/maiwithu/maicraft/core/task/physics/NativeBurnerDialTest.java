package org.maiwithu.maicraft.core.task.physics;

/** 不同服务器容量配置会改变原生面板步长；模型请求应按实际刻度结算并保留最小档。 */
public final class NativeBurnerDialTest {
    public static void run() {
        check(NativeBurnerDial.interval(500)==5 && NativeBurnerDial.applied(127,500)==125,"默认容量按五立方取档");
        check(NativeBurnerDial.interval(2000)==20 && NativeBurnerDial.applied(5,2000)==5,"大容量面板最小列仍钳到五立方");
        check(NativeBurnerDial.column(255,2000)==12 && NativeBurnerDial.applied(255,2000)==240,"设置包列数与实际容量分别表达");
        check(NativeBurnerDial.applied(9,10)==9,"小容量配置不能被固定五立方刻度覆盖");
        for(int value:new int[]{0,4,501}) {
            try { NativeBurnerDial.column(value,500); throw new AssertionError("原生范围外请求被编码"); }
            catch(IllegalArgumentException expected) { }
        }
        System.out.println("NativeBurnerDialTest: passed");
    }
    private static void check(boolean okay,String why) { if(!okay)throw new AssertionError(why); }
}
