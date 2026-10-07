'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { extract } = require('./release-notes.cjs');

// 用真实更新日志的形状做夹具：未发布占位、本次版本段落、上一版段落，以及段落之间的说明文字。
const changelog = `# 更新日志

MaiCraft 按版本记录玩家和 Agent 能感受到的变化。

## [未发布]

暂无。

## [0.1.10] - 2026-10-08

### 机器

- 拨拉杆不再需要先拍快照。

### 睡眠

- 床被挡住先换位置再试。

## [0.1.9] - 2026-10-07

### 机器

- 上一版的变化。
`;

test('取 CI 实际产出版本的段落，去掉标题且不带上下个版本的内容', () => {
  const notes = extract(changelog, '0.1.10');
  assert.equal(notes.source, '[0.1.10]');
  assert.match(notes.body, /^### 机器/);
  assert.match(notes.body, /- 拨拉杆不再需要先拍快照。/);
  assert.match(notes.body, /床被挡住先换位置再试。/);
  assert.doesNotMatch(notes.body, /0\.1\.10|0\.1\.9|上一版的变化/);
});

test('维护者还没改标题时，退回“未发布”段落', () => {
  const pending = changelog.replace('## [未发布]\n\n暂无。', '## [未发布]\n\n### 旅行\n\n- 新的变化。');
  const notes = extract(pending, '0.1.11');
  assert.equal(notes.source, '[未发布]');
  assert.equal(notes.body, '### 旅行\n\n- 新的变化。');
});

test('没有对应段落时如实说明，不拿上一版内容顶替', () => {
  const notes = extract(changelog, '0.1.11');
  assert.equal(notes.source, '空');
  assert.match(notes.body, /没有对应的更新日志条目/);
  assert.doesNotMatch(notes.body, /上一版的变化/);
});

test('占位正文、空日志和非法版本号', () => {
  assert.equal(extract('## [未发布]\n\n暂无。\n', '0.1.10').source, '空');
  assert.equal(extract('', '0.1.10').source, '空');
  for (const invalid of ['0.1', 'v0.1.10', '0.1.x', '01.1.0', '', undefined]) {
    assert.throws(() => extract(changelog, invalid), /发布版本/);
  }
});

test('CRLF 日志与带附加文字的标题仍能取出正文', () => {
  const text = '# 更新日志\r\n\r\n## [未发布]\r\n\r\n暂无。\r\n\r\n## [0.1.10] - 2026-10-08 (回补)\r\n\r\n- 换行符不影响。\r\n';
  assert.equal(extract(text, '0.1.10').body, '- 换行符不影响。');
});
