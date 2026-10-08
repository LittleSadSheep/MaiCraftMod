# 统计 org.maiwithu.maicraft 各包之间的 import 依赖，用于重构诊断与进度跟踪。
# 用法（在仓库根目录）：
#   python docs/refactor/tools/deps.py 1          顶层分组（core 下多展开一层）
#   python docs/refactor/tools/deps.py 2 intent   展开到两层，只看与 intent 有关的边
# 输出：引用次数  涉及文件数  来源分组 -> 目标分组
import collections
import os
import re
import sys

# 脚本位于 docs/refactor/tools，源码根相对仓库根定位，不依赖某台机器上的绝对路径。
REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
SOURCE_ROOT = os.path.join(REPO, 'common', 'src', 'main', 'java')
PACKAGE_ROOT = 'org/maiwithu/maicraft'
IMPORT = re.compile(r'^import (?:static )?org\.maiwithu\.maicraft\.([\w.]+?)\.[A-Z]', re.M)
PACKAGE = re.compile(r'^package ([\w.]+);', re.M)


def group(package, depth):
    parts = package.split('.')[3:]
    if not parts:
        return '(root)'
    # core 只是历史外壳，多展开一层才能看出 core.task、core.integration 等真实分区。
    if parts[0] == 'core' and len(parts) > 1:
        return '.'.join(parts[:min(len(parts), depth + 1)])
    return '.'.join(parts[:min(len(parts), depth)])


def main():
    depth = int(sys.argv[1]) if len(sys.argv) > 1 else 1
    focus = sys.argv[2] if len(sys.argv) > 2 else None
    edges = collections.Counter()
    files = collections.defaultdict(set)
    for directory, _, names in os.walk(os.path.join(SOURCE_ROOT, PACKAGE_ROOT)):
        for name in names:
            if not name.endswith('.java'):
                continue
            path = os.path.join(directory, name)
            source = open(path, encoding='utf-8').read()
            declared = PACKAGE.search(source)
            if declared is None:
                continue
            me = group(declared.group(1), depth)
            for imported in IMPORT.findall(source):
                target = group('org.maiwithu.maicraft.' + imported, depth)
                if target != me:
                    edges[(me, target)] += 1
                    files[(me, target)].add(path)
    for (source, target), count in sorted(edges.items(), key=lambda item: -item[1]):
        if focus and not (source.startswith(focus) or target.startswith(focus)):
            continue
        print(f"{count:5} {len(files[(source, target)]):4}f {source:28} -> {target}")


if __name__ == '__main__':
    main()
