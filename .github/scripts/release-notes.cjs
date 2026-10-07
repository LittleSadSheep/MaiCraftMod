'use strict';

const fs = require('node:fs');

const UNRELEASED = '未发布';
const CHANGELOG = 'CHANGELOG.md';

// 版本号决定 Release 的标签和标题，必须是递增后的三段数字，不接受标签前缀等其他写法。
function assertVersion(version) {
  if (typeof version !== 'string' || !/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(version)) {
    throw new Error(`发布版本必须是 主版本.次版本.补丁版本，收到 ${String(version)}`);
  }
  return version;
}

// 更新日志用二级标题分隔版本段落，标题形如“## [0.1.10] - 2026-10-08”，未发布段落只写“## [未发布]”。
// 段落正文取到下一个二级标题之前，三级标题（机器、睡眠等领域）留在正文里作为分组。
function sections(changelog) {
  const headings = [...changelog.matchAll(/^## \[([^\]\r\n]+)\][^\r\n]*$/gm)];
  return headings.map((heading, index) => ({
    name: heading[1].trim(),
    body: changelog.slice(
      heading.index + heading[0].length,
      index + 1 < headings.length ? headings[index + 1].index : changelog.length,
    ).trim(),
  }));
}

// “暂无。”这类占位正文不算内容，避免把空段落当成发布说明发出去。
function filled(section) {
  if (!section) return false;
  const text = section.body.replace(/\s+/g, '');
  return text !== '' && !/^暂无。?$/.test(text);
}

// 发布说明优先取 CI 实际产出的版本段落：维护者按约定已把“未发布”改成版本号，这里就对得上。
// 忘了改标题时退回“未发布”段落；两者都没有就如实说明本次没有日志条目，不拿上一版的内容顶替，
// 免得玩家在 Release 里看到已经发布过的变化，还以为本次改了什么。
function extract(changelog, version) {
  assertVersion(version);
  const found = sections(changelog);
  const exact = found.find(section => section.name === version && filled(section));
  if (exact) return { source: `[${version}]`, body: exact.body };
  const unreleased = found.find(section => section.name === UNRELEASED && filled(section));
  if (unreleased) return { source: `[${UNRELEASED}]`, body: unreleased.body };
  return {
    source: '空',
    body: '本次发布没有对应的更新日志条目；两个加载器的安装包见下方附件。',
  };
}

if (require.main === module) {
  const [version, target, file = CHANGELOG] = process.argv.slice(2);
  if (!target) throw new Error('用法：node .github/scripts/release-notes.cjs <版本> <输出文件> [更新日志路径]');
  const notes = extract(fs.readFileSync(file, 'utf8'), version);
  fs.writeFileSync(target, `${notes.body}\n`);
  // 说明取自哪一段写进运行日志；维护者改错了标题能直接从 CI 回执看出来。
  console.log(`${file} 的 ${notes.source} 段落已写入 ${target}`);
}

module.exports = { extract, sections, assertVersion };
