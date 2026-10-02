// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 使用真实 SQLite 回放重连、并发保存和半途写入失败，防止数据库回执冒称任务已保存。 */
public final class MemoryDatabaseTest {
    public static void main(String[] args) throws Exception {
        Path workspace = Path.of("").toRealPath();
        Path directory = Files.createTempDirectory(workspace, "sqlite-memory-");
        try {
            Path file = directory.resolve(MemoryDatabase.FILE_NAME);
            var database = new MemoryDatabase(file);
            check(database.read("state", "world", 100) == null && !Files.exists(file), "只读空记忆不能创建数据库");
            database.write("state", "world", "任务一", Map.of(), false);
            database.write("machines", "world", "机器一", Map.of("blueprint", "完整蓝图"), false);
            database.write("state", "other-world", "另一世界", Map.of(), false);
            check(new MemoryDatabase(file).read("state", "world", 100).equals("任务一"), "重启必须从真实数据库恢复");
            check(database.read("machines", "world", 100, 100, (json, docs) -> docs.apply("blueprint")).equals("完整蓝图"), "机器蓝图独立归档");
            check(database.read("state", "other-world", 100).equals("另一世界"), "世界身份不能混用");
            // 导入旧 JSON 时已有数据库记录优先，不能用旧快照和旧图纸覆盖较新的施工进度。
            database.write("state", "world", "旧任务", Map.of("stale", "旧图纸"), true);
            check(database.read("state", "world", 100).equals("任务一"), "重复迁移覆盖了新进度");
            check(database.read("state", "world", 100, 100, (json, docs) -> docs.apply("stale")) == null, "重复迁移泄漏旧图纸");
            fails(() -> database.read("state", "world", 5));
            // 人为让第二条写入失败，数据库必须同时撤回已经写入的目录与前一张图纸。
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file); var sql = connection.createStatement()) {
                sql.execute("CREATE TRIGGER reject_document BEFORE INSERT ON memory_records WHEN NEW.document_key='fail' "
                        + "BEGIN SELECT RAISE(ABORT,'fixture'); END");
            }
            var documents = new LinkedHashMap<String, String>(); documents.put("first", "暂存图纸"); documents.put("fail", "不能入库");
            fails(() -> database.write("machines", "world", "半份机器", documents, false));
            check(database.read("machines", "world", 100).equals("机器一"), "失败事务留下了半份目录");
            check(database.read("machines", "world", 100, 100, (json, docs) -> docs.apply("first")) == null, "失败事务留下了孤立图纸");
            fails(() -> database.write("machines", "world", "错误修订", Map.of("blueprint", "不同蓝图"), false));
            check(database.read("machines", "world", 100).equals("机器一"), "不可变图纸冲突没有回滚目录");
            // 任务与机器后台线程同时保存时，各自世界和用途仍独立，忙碌等待不能吞掉任何成功回执。
            CompletableFuture.allOf(writeInBackground(file, "state"), writeInBackground(file, "machines")).get(20, TimeUnit.SECONDS);
            for (String scope : new String[]{"state", "machines"})
                check(database.read(scope, "parallel", 100).equals("9"), "并发保存丢失最后一次进度");
            // 新安装的首个世界也可能同时产生任务和机器记忆，首次建库不能互相阻塞或误判版本。
            Path fresh = directory.resolve("first-world.sqlite");
            var together = new CountDownLatch(2);
            CompletableFuture.allOf(writeInBackground(fresh, "state", together), writeInBackground(fresh, "machines", together)).get(20, TimeUnit.SECONDS);
            for (String scope : new String[]{"state", "machines"})
                check(new MemoryDatabase(fresh).read(scope, "parallel", 100).equals("9"), "并发首次建库丢失记忆");
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file); var sql = connection.createStatement()) {
                sql.execute("PRAGMA user_version=99");
            }
            fails(() -> database.read("state", "world", 100));
            fails(() -> database.write("state", "world", "不兼容写入", Map.of(), false));
            System.out.println("MemoryDatabaseTest: passed");
        } finally {
            // 清理仅限本次回归创建的目录；游戏存档、旧 JSON 与正式数据库都不参与测试。
            if (!directory.toRealPath().startsWith(workspace)) throw new AssertionError("fixture escaped workspace");
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static CompletableFuture<Void> writeInBackground(Path file, String scope) {
        return writeInBackground(file, scope, new CountDownLatch(0));
    }
    private static CompletableFuture<Void> writeInBackground(Path file, String scope, CountDownLatch together) {
        return CompletableFuture.runAsync(() -> {
            try {
                together.countDown();
                if (!together.await(5, TimeUnit.SECONDS)) throw new AssertionError("并发写入未同时启动");
                var database = new MemoryDatabase(file);
                for (int i = 0; i < 10; i++) database.write(scope, "parallel", Integer.toString(i), Map.of(), false);
            } catch (IOException failure) { throw new AssertionError(failure); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        });
    }
    private interface Operation { void run() throws Exception; }
    private static void fails(Operation action) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("存储失败不能当成空记忆或保存成功");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
