// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.build;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.workers.WorkerExecutor;

/**
 * 并发执行回归套件；并发度由 --max-workers 控制，配 1 即串行基线。
 * worker 以非隔离模式跑在 Gradle 守护进程线程里，动作内用 ProcessBuilder 孵化每个套件 JVM：
 * 真实工作目录、独立堆、显式类路径与逐套件超时，语义与逐个 JavaExec 相同
 * （worker API 的进程隔离不支持设置工作目录，故不用它承载套件本身）。
 * 超时上限大的套件先提交，让最重的运行压住尾延迟。
 * 常规入口仍是 check 与逐套件任务，本任务只服务并行提速验证，显式调用才会运行。
 */
public abstract class ParallelRegressionCheckTask extends DefaultTask {
    /** 套件配置：键为 regressionSuites 的套件名，值为同名配置项的字符串化映射（entry/heap/directory/timeoutSeconds）。 */
    @Internal
    public abstract MapProperty<String, Map<String, String>> getSuites();

    /** 套件 JVM 的类路径：test 运行时类路径 + Minecraft 资源工件。 */
    @Internal
    public abstract ConfigurableFileCollection getSuiteClasspath();

    /** 套件工作目录的根目录。 */
    @Internal
    public abstract DirectoryProperty getWorkingRoot();

    /** 只执行名单内的套件（逗号分隔的套件名），留空执行全部。 */
    @Internal
    public abstract Property<String> getSuiteFilter();

    @Inject
    protected abstract WorkerExecutor getWorkerExecutor();

    @TaskAction
    public void run() {
        Map<String, Map<String, String>> suites = getSuites().get();
        Set<String> names = new LinkedHashSet<>(suites.keySet());
        String filter = getSuiteFilter().get();
        if (!filter.isBlank()) {
            Set<String> wanted = new LinkedHashSet<>(Arrays.asList(filter.split(",")));
            wanted.removeIf(String::isBlank);
            List<String> unknown = wanted.stream().filter(n -> !suites.containsKey(n)).toList();
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException("maicraftSuiteFilter 里有未登记的套件: " + unknown);
            }
            names.retainAll(wanted);
        }
        if (names.isEmpty()) {
            throw new IllegalStateException("parallelRegressionCheck 没有可执行的套件");
        }
        List<String> ordered = new ArrayList<>(names);
        ordered.sort((a, b) -> Integer.compare(timeoutOf(suites.get(b)), timeoutOf(suites.get(a))));
        List<String> classpathFiles = getSuiteClasspath().getFiles().stream()
                .map(File::getAbsolutePath).toList();
        WorkerExecutor workers = getWorkerExecutor();
        for (String name : ordered) {
            Map<String, String> cfg = suites.get(name);
            File workingDir = getWorkingRoot().dir(cfg.get("directory")).get().getAsFile();
            workingDir.mkdirs();
            workers.noIsolation().submit(RegressionSuiteWorkAction.class, params -> {
                params.getSuiteName().set(name);
                params.getMainClass().set(cfg.get("entry"));
                params.getHeap().set(cfg.getOrDefault("heap", "512m"));
                params.getTimeoutSeconds().set(timeoutOf(cfg));
                params.getWorkingDir().set(workingDir.getAbsolutePath());
                params.getClasspathFiles().set(classpathFiles);
            });
        }
    }

    /** 未配 timeoutSeconds 的套件返回 0（不限时），排序时视作最重。 */
    private static int timeoutOf(Map<String, String> cfg) {
        String raw = cfg.get("timeoutSeconds");
        return raw == null ? 0 : Integer.parseInt(raw);
    }
}
