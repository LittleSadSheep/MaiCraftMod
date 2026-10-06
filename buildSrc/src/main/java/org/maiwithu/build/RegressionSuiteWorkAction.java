// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.build;

import java.io.File;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import org.gradle.workers.WorkAction;

/**
 * 在 Gradle 守护进程的 worker 线程里孵化一个套件 JVM 并等待其结束。
 * 套件仍是独立 JVM（真实工作目录、独立堆、显式类路径），与逐个 JavaExec 的语义一致；
 * worker 只负责并发调度（--max-workers）、逐套件超时与失败点名。
 * 超时的套件进程被强制销毁；失败按套件名包装重抛，Gradle 汇总时能定位到套件。
 */
public abstract class RegressionSuiteWorkAction implements WorkAction<RegressionSuiteParams> {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Override
    public void execute() {
        String name = getParameters().getSuiteName().get();
        String mainClass = getParameters().getMainClass().get();
        String heap = getParameters().getHeap().get();
        int timeoutSeconds = getParameters().getTimeoutSeconds().get();
        String classpath = String.join(";", getParameters().getClasspathFiles().get());
        File workingDir = new File(getParameters().getWorkingDir().get());
        String java = ProcessHandle.current().info().command()
                .orElseThrow(() -> new IllegalStateException("无法确定当前 JVM 可执行文件，无法孵化套件进程"));

        System.out.printf("[%s] %s 开始%n", CLOCK.format(LocalTime.now()), name);
        long startedAt = System.nanoTime();
        Process process;
        try {
            process = new ProcessBuilder(java, "-Xmx" + heap, "-cp", classpath, mainClass)
                    .directory(workingDir)
                    .redirectErrorStream(true)
                    .start();
        } catch (java.io.IOException e) {
            throw new RuntimeException(name + " 无法启动套件进程: " + e, e);
        }
        // 与 JavaExec 的空输入流语义对齐：立即关闭 stdin，读输入的线程立刻拿到 EOF，不会挂住。
        try {
            process.getOutputStream().close();
        } catch (java.io.IOException e) {
            throw new RuntimeException(name + " 无法关闭套件进程输入流: " + e, e);
        }
        Thread pump = new Thread(() -> {
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                reader.lines().forEach(System.out::println);
            } catch (java.io.IOException ignored) {
                // 进程被终止时管道断裂属预期，输出丢弃即可。
            }
        }, "suite-log-" + name);
        pump.start();
        boolean exited;
        try {
            if (timeoutSeconds > 0) {
                exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            } else {
                process.waitFor();
                exited = true;
            }
        } catch (InterruptedException e) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException(name + " 的等待被中断，已终止套件进程", e);
        }
        long seconds = (System.nanoTime() - startedAt) / 1_000_000_000L;
        if (!exited) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new RuntimeException(name + " 超过 " + timeoutSeconds + "s 未结束，已强制终止");
        }
        try {
            pump.join(10_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (process.exitValue() != 0) {
            throw new RuntimeException(name + " 失败（" + seconds + "s，退出码 " + process.exitValue() + "）");
        }
        System.out.printf("[%s] %s 通过（%ds）%n", CLOCK.format(LocalTime.now()), name, seconds);
    }
}
