# 统计某个能力在 SemanticAbilityCatalog 中契约正文的字符数，用于比较契约精简前后的长度。
# 用法（在仓库根目录）：python docs/refactor/tools/contract_len.py maicraft:sleep
import os
import re
import sys

# 相对仓库根定位契约目录；能力换芯后契约移到资源文件，届时这个脚本随旧目录一起退役。
REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
SOURCE = os.path.join(REPO, 'common', 'src', 'main', 'java', 'org', 'maiwithu', 'maicraft',
                      'intent', 'SemanticAbilityCatalog.java')
STRING = re.compile(r'"((?:[^"\\]|\\.)*)"')

src = open(SOURCE, encoding='utf-8').read()
ability = sys.argv[1]
start = src.index('case "' + ability + '" -> contract(')
end = src.index('targets(', start)
pieces = [s for s in STRING.findall(src[start:end]) if s != ability]
print(len(''.join(pieces)))
