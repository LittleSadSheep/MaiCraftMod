// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.build;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.workers.WorkParameters;

/** 单个回归套件的运行参数：展示名、入口类、堆上限、超时上限、工作目录与类路径条目。 */
public interface RegressionSuiteParams extends WorkParameters {
    Property<String> getSuiteName();

    Property<String> getMainClass();

    Property<String> getHeap();

    /** 0 表示不限时，与 JavaExec 未配 timeoutSeconds 的行为一致。 */
    Property<Integer> getTimeoutSeconds();

    Property<String> getWorkingDir();

    /** 套件 JVM 的类路径条目（绝对路径），按分号拼接后作为 -cp 传入。 */
    ListProperty<String> getClasspathFiles();
}
