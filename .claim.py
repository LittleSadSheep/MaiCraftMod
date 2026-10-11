import sys
p = r'D:\STORAGE\Codes\MaiCraft\docs\tmp\worktree.md'
prefix = r'| `D:\STORAGE\Codes\MaiCraft-v1-compat` |'
mode = sys.argv[1]
s = open(p, encoding='utf-8').read()
lines = s.split('\n')
for i, l in enumerate(lines):
    if l.startswith(prefix):
        if mode == 'claim':
            if '| 空闲 |' not in l:
                raise SystemExit('busy: ' + l[:150])
            lines[i] = (prefix + ' 占用 | 大型项目架构重构 里程碑三 [22e6eb]／合入前验证 | 游离在 `v1-milestone3-compat-1` 的提交上，'
                        '只跑验证不改代码 | ' + sys.argv[2] + '跑三项验证，过了从 MaiCraft-v1-machine 快进推送 | — | 2026-10-11 |')
        else:
            lines[i] = (prefix + ' 空闲 | — | 游离在 `' + sys.argv[2] + '`（origin/v1） | — | — | '
                        '2026-10-11（上一任：[22e6eb] ' + sys.argv[3] + '） |')
        print(mode, 'ok')
open(p, 'w', encoding='utf-8').write('\n'.join(lines))
