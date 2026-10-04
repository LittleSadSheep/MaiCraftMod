package org.maiwithu.maicraft.core.integration.physics.flight;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.util.UUID;
import java.util.Comparator;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;

/** 重启式新建读取器仍取同一架飞机声明，其他世界和维度不能拿到这份操纵映射。 */
public final class AircraftProfileStoreTest {
    public static void run() throws Exception {
        var directory=Files.createTempDirectory("maicraft-flight-profile-").toAbsolutePath().normalize();
        var identity=new StateIdentity("a".repeat(64),directory);var id=UUID.randomUUID();
        var profile=AircraftProfile.parse(JsonParser.parseString("{\"kind\":\"airship\",\"seat_position\":{\"x\":0,\"y\":0,\"z\":0},\"typewriter_position\":{\"x\":1,\"y\":0,\"z\":0},\"keys\":{\"power\":\"w\",\"lift\":\"space\"}}").getAsJsonObject());
        try {
            new AircraftProfileStore(identity).save("minecraft:overworld",id,profile).join();
            var loaded=new AircraftProfileStore(identity).read("minecraft:overworld").join().get(id);
            check(loaded!=null&&loaded.keys().equals(profile.keys())&&loaded.seat().equals(profile.seat()),"重建读取器后声明丢失");
            check(new AircraftProfileStore(identity).read("minecraft:the_nether").join().isEmpty(),"维度串用了飞控声明");
            check(new AircraftProfileStore(new StateIdentity("b".repeat(64),directory)).read("minecraft:overworld").join().isEmpty(),"世界串用了飞控声明");
        } finally {
            // 只清除此测试刚创建的临时目录，校验每个规范路径仍在该目录内。
            try(var paths=Files.walk(directory)) {
                for(var path:paths.sorted(Comparator.reverseOrder()).toList()) {
                    if(!path.toAbsolutePath().normalize().startsWith(directory))throw new IllegalStateException("test cleanup escaped its directory");
                    Files.delete(path);
                }
            }
        }
        System.out.println("AircraftProfileStoreTest: passed");
    }
    private static void check(boolean yes,String why){if(!yes)throw new AssertionError(why);}
}
