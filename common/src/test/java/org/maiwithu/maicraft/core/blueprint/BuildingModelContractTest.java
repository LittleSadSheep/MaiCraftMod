// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.util.Properties;
import org.maiwithu.maicraft.core.build.BuildingBudgets;
import org.maiwithu.maicraft.intent.Goal;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import static org.maiwithu.maicraft.core.blueprint.BuildingModelTestData.*;

/** 设计契约可重复发现；格式/预算变化不会冒充旧校验，失败编辑也不能覆盖已保存的作者模型。 */
public final class BuildingModelContractTest {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--fingerprint")) {
            var value = BuildingModelContract.current();
            System.out.println("building-contract-fingerprint="+value.revision()+"/"+value.designSchemaRevision()); return;
        }
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var current = BuildingModelContract.current();
        var again = BuildingModelContract.describe(BuildingBudgets.current());
        check(current.revision().equals(again.revision()) && current.schemaText().equals(again.schemaText()),"相同定义必须生成相同契约和Schema正文");
        stableAcrossProcesses();
        check(current.designSchemaRevision().equals(BuildingModelContract.digest(current.schemaText())),"设计格式指纹必须对应实际返回的完整正文");
        var copy = current.schema(); copy.addProperty("tampered",true);
        check(!current.schema().has("tampered"),"调用者修改返回对象不能改变固定URI下的Schema正文");
        var properties = new Properties(); properties.setProperty("maxObjects","31");
        var smaller = BuildingModelContract.describe(BuildingBudgets.fromProperties(properties));
        check(!current.designSchemaRevision().equals(smaller.designSchemaRevision()) && !current.revision().equals(smaller.revision()),"对象预算影响格式上限和能力契约");
        properties.clear(); properties.setProperty("maxVoxelWork","1");
        var work = BuildingModelContract.describe(BuildingBudgets.fromProperties(properties));
        check(current.designSchemaRevision().equals(work.designSchemaRevision()) && !current.revision().equals(work.revision()),"体素预算改变编译契约但不伪造格式变化");
        var expected = new JsonObject(); expected.addProperty(BuildingModelContract.EXPECTED_CAPABILITY,current.revision());
        expected.addProperty(BuildingModelContract.EXPECTED_SCHEMA,current.designSchemaRevision());
        BuildingModelContract.checkExpected(expected);
        var outdated = expected.deepCopy(); outdated.addProperty(BuildingModelContract.EXPECTED_CAPABILITY,"old");
        rejects(() -> BuildingModelContract.checkExpected(outdated),"过期版本不能进入设计保存流程");
        storedRevisions(current,expected);
        // 输出真实Schema供独立JSON Schema实现核验，文件只写入指定的临时验证目录。
        String output = System.getProperty("maicraft.building.contract.output");
        if (output != null) {
            var directory = Path.of(output); Files.createDirectories(directory);
            Files.writeString(directory.resolve("schema.json"),current.schemaText());
            Files.writeString(directory.resolve("index.json"),current.index().toString());
        }
        System.out.println("BuildingModelContractTest: immutable schema, revisions and atomic scene persistence passed");
    }

    private static void stableAcrossProcesses() throws Exception {
        // 独立 JVM 会重新随机化不可变集合的迭代顺序；真正重启后仍应返回同一份格式和编译凭据。
        var baseline = BuildingModelContract.describe(BuildingBudgets.defaults());
        String expected = "building-contract-fingerprint="+baseline.revision()+"/"+baseline.designSchemaRevision();
        var directory = Files.createTempDirectory("building-contract-process-");
        var arguments = directory.resolve("java.args");
        String classpath = System.getProperty("java.class.path").replace("\\","\\\\").replace("\"","\\\"");
        Files.writeString(arguments,"-cp\n\""+classpath+"\"\n"+BuildingModelContractTest.class.getName()+"\n--fingerprint\n");
        String binary = Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        for (int attempt = 0; attempt < 3; attempt++) {
            var output = directory.resolve("probe-"+attempt+".log");
            var process = new ProcessBuilder(binary,"@"+arguments).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(15,TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("独立契约指纹进程未及时完成"); }
            String text = Files.readString(output);
            check(process.exitValue() == 0 && text.lines().anyMatch(expected::equals),"跨进程契约指纹必须稳定: "+text);
        }
    }

    private static void storedRevisions(BuildingModelContract.Snapshot current,JsonObject expected) throws Exception {
        var root = Files.createTempDirectory("building-contract-scenes-"); String world = "d".repeat(64), dimension = "minecraft:overworld";
        var store = new BuildingSceneStore(root,world); var anchor = new Goal.WorldPosition(0,64,0,dimension);
        var source = scene(mesh("Wall","panel",new double[]{1,1,.5},new int[]{2,2,1},"Body"));
        var first = store.save(source,anchor); BuildingModelContract.checkScene(first,expected);
        check(first.capabilityRevision().equals(current.revision()) && first.designSchemaRevision().equals(current.designSchemaRevision()),"新设计必须记录实际编译版本");
        var reopened = new BuildingSceneStore(root,world).load(first.sceneId(),dimension);
        check(reopened.equals(first),"重开仓库后保持版本、位置和作者模型");
        var revision = store.update(first.sceneId(),dimension,json("{\"objects\":[{\"name\":\"Wall\",\"material\":\"Glass\"}]}"));
        check(revision.parentSceneId().equals(first.sceneId()) && !revision.sceneId().equals(first.sceneId()),"修改只发布直接子版本");
        check(store.load(first.sceneId(),dimension).scene().equals(source),"成功修改也不能覆盖旧模型");
        var directory = root.resolve("build-scenes").resolve(world); var path = directory.resolve(first.sceneId()+".json");
        String intact = Files.readString(path);
        rejects(() -> store.update(first.sceneId(),dimension,json("{\"objects\":[{\"name\":\"Wall\",\"dimensions\":[2,0,1]}]}")),"无效尺寸必须整体拒绝");
        check(Files.readString(path).equals(intact),"失败编辑必须逐字保留旧文件");
        try (var files = Files.list(directory)) { check(files.count() == 2,"失败编辑不能发布部分新场景"); }

        // 只在本测试新建的文件中模拟无凭据旧记录；仍允许读取，严格调用须明确要求重新校验。
        var legacy = json(intact); legacy.remove("capability_revision"); legacy.remove("design_schema_revision"); Files.writeString(path,legacy.toString());
        var old = store.load(first.sceneId(),dimension); BuildingModelContract.checkScene(old,new JsonObject());
        rejects(() -> BuildingModelContract.checkScene(old,expected),"无版本记录不能满足严格预览或施工要求");
        var revalidated = store.save(old.scene(),old.anchor()); BuildingModelContract.checkScene(revalidated,expected);
        check(!revalidated.sceneId().equals(old.sceneId()) && Files.readString(path).equals(legacy.toString()),"重新校验另存编号，不能改写旧记录");
        legacy.addProperty("capability_revision","old"); legacy.addProperty("design_schema_revision",current.designSchemaRevision()); Files.writeString(path,legacy.toString());
        var stale = store.load(first.sceneId(),dimension); rejects(() -> BuildingModelContract.checkScene(stale,expected),"记录与当前编译契约不同须重校验");
    }
}
