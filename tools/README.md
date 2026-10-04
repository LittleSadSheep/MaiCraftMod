# maicraft_cli：内嵌 MCP 端点直连调试工具

绕过宿主（IDE / CLI 代理）自带的 MCP 客户端会话，直接以 Streamable HTTP 调用游戏内嵌 MCP 端点。纯 Python 标准库，无第三方依赖。

## 什么时候用

- 宿主的 MCP 会话失效（`Unknown or expired MCP session`）且不自动重连时
- 宿主工具层对嵌套 JSON 参数序列化失真（goal 报 `must be an object` 一类）需要绕行时
- 实机验收中需要手工单发 perceive / task / 读聊天流等轻量排查时

## 用法

```bash
python tools/maicraft_cli.py init                                  # 强制重新握手
python tools/maicraft_cli.py call perceive '{"view":"situation"}'  # 内联 JSON 参数
python tools/maicraft_cli.py call execute @payload.json            # UTF-8 文件载荷
python tools/maicraft_cli.py call task '{"action":"list"}'
python tools/maicraft_cli.py read maicraft://chatflow              # 读 MCP 资源
python tools/maicraft_cli.py --raw call perceive '{"view":"surroundings"}'
```

默认输出会把 JSON-RPC 外层与 `result.content[0].text` 的嵌套 JSON 解包美化；`--raw` 打印完整响应。

## 配置

| 参数 | 环境变量 | 默认 |
| --- | --- | --- |
| `--endpoint` | `MAICRAFT_ENDPOINT` | `http://127.0.0.1:8766/mcp` |
| `--session-file` | `MAICRAFT_SESSION_FILE` | 系统临时目录下 `maicraft_cli_session_id.txt` |

会话标识缓存在本地文件；调用遇「会话过期」错误时自动重新 initialize 并重放一次当前调用。游戏重启后端点侧会话全部失效，脚本会自动握手，无需手工清理缓存。

## Windows 编码注意

Git Bash 里 curl / argv 会把中文按本地代码页（GBK）发出，服务器按 UTF-8 解读即成乱码。含非 ASCII 的参数一律写成 UTF-8 文件后用 `@文件路径` 传入；本脚本内部全程 UTF-8 读写，不经 argv 传中文。
