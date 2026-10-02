// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

/** 复现 Sable 同时提供通用世界和服务端世界入口，避免真实受力观察被误判为接口歧义。 */
public final class NativeApiDispatchTest {
    public static void main(String[] args) {
        // 单人世界也属于服务端世界，必须找到它的物理容器，不能先选到通用父类入口。
        check("server".equals(container(new ServerWorld())), "服务端世界没有选择专用重载");
        check("server".equals(container(new IntegratedWorld())), "集成服世界没有继承服务端入口");
        check("client".equals(container(new ClientWorld())), "客户端观察选错世界入口");
        check("world".equals(container(new World())), "普通世界入口不可用");

        // 先收集全部候选再作判断；两个中间接口都匹配时，后面的具体设备仍能唯一决定原生调用。
        check("device".equals(NativeApi.call(null, DeviceApi.class.getName(), "read", new Device())),
                "具体设备重载被中间接口遮住");
        expectAmbiguous(new SharedDevice());
        expectAmbiguous(null);
        check(DeviceApi.invocations == 1, "歧义调用不应先触发任何设备操作");

        // 编译器生成的桥接入口不能使同一次原生读取变成两种方案。
        check("bridge".equals(NativeApi.call(new StringReader(), null, "read")), "桥接方法覆盖了实际读取");
        System.out.println("Native API dispatch regressions passed");
    }

    private static Object container(World world) {
        return NativeApi.call(null, ContainerApi.class.getName(), "getContainer", world);
    }

    private static void expectAmbiguous(Object device) {
        try {
            NativeApi.call(null, AmbiguousApi.class.getName(), "read", device);
            throw new AssertionError("无唯一原生入口时不应猜测操作对象");
        } catch (NativeApi.Unavailable expected) {
            check(expected.getMessage().startsWith("Ambiguous API:"), "未保留真实歧义原因");
        }
    }

    // 夹具保留原生世界的父子关系，不启动游戏也能重现服务端容器接口选择失败。
    public static class World {}
    public static class ServerWorld extends World {}
    public static final class IntegratedWorld extends ServerWorld {}
    public static final class ClientWorld extends World {}
    public static final class ContainerApi {
        public static String getContainer(World world) { return "world"; }
        public static String getContainer(ServerWorld world) { return "server"; }
        public static String getContainer(ClientWorld world) { return "client"; }
    }

    // 同时具有两个接口的设备只有在声明了更具体入口时才能自动选择，避免对错误机器施加副作用。
    public interface Source {}
    public interface Sink {}
    public static class SharedDevice implements Source, Sink {}
    public static final class Device extends SharedDevice {}
    public static final class DeviceApi {
        static int invocations;
        public static String read(Source source) { invocations++; return "source"; }
        public static String read(Sink sink) { invocations++; return "sink"; }
        public static String read(Device device) { invocations++; return "device"; }
    }
    public static final class AmbiguousApi {
        public static String read(Source source) { DeviceApi.invocations++; return "source"; }
        public static String read(Sink sink) { DeviceApi.invocations++; return "sink"; }
    }

    // 协变返回值会生成相同参数的桥接方法，原生适配仍应只执行实际实现一次。
    public interface Reader<T> { T read(); }
    public static final class StringReader implements Reader<String> {
        public String read() { return "bridge"; }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
