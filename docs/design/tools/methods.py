# 粗略统计 Java 文件里每个方法的行数（按缩进 4 格的方法声明切分），用于找巨型方法。
# 用法（在仓库根目录）：python docs/design/tools/methods.py <文件或目录> [最少行数，默认 80]
import os
import re
import sys

# 方法声明：行首恰好缩进 4 格，以 { 结尾（单行签名），或以 ( / , 结尾（多行签名的第一行）。
DECL = re.compile(r'^    (?:@\w+\s+)*(?:(?:public|private|protected|static|final|synchronized|abstract|default)\s+)*'
                  r'(?:<[^>]+>\s+)?[\w<>\[\],.?]+(?:<[^()]*>)?\s+(\w+)\s*\((?:[^;]*\)\s*(?:throws [\w., ]+)?\s*\{|[^;)]*[(,])\s*$')
KEYWORDS = {'if', 'for', 'while', 'switch', 'catch', 'return', 'new', 'synchronized', 'else'}


def methods(path):
    lines = open(path, encoding='utf-8').read().split('\n')
    found = []
    for index, line in enumerate(lines):
        match = DECL.match(line)
        if match and match.group(1) not in KEYWORDS:
            found.append((index, match.group(1)))
    result = []
    for position, (start, name) in enumerate(found):
        end = found[position + 1][0] if position + 1 < len(found) else len(lines)
        result.append((end - start, name, start + 1))
    return result


def main():
    target = sys.argv[1]
    minimum = int(sys.argv[2]) if len(sys.argv) > 2 else 80
    files = [target] if target.endswith('.java') else [
        os.path.join(d, f) for d, _, fs in os.walk(target) for f in fs if f.endswith('.java')]
    rows = []
    for path in files:
        for size, name, line in methods(path):
            if size >= minimum:
                rows.append((size, os.path.relpath(path, target if os.path.isdir(target) else os.path.dirname(target)), name, line))
    for size, path, name, line in sorted(rows, reverse=True):
        print(f"{size:5} {path}:{line} {name}")
    print(f"共 {len(rows)} 个方法不少于 {minimum} 行")


if __name__ == '__main__':
    main()
