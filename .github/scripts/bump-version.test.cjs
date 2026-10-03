'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const bumpVersion = require('./bump-version.cjs');

// 用远端分支的可变状态回放重跑与并发合入，验证自动版本提交不会重复递增或覆盖新代码。
function fixture({ source = 'version=0.1.0\n', previous = source, head = 'source', race, denied } = {}) {
  const files = { source, before: previous };
  const commits = {};
  const outputs = {};
  const state = { head, writes: 0 };
  const github = {
    rest: {
      repos: { getContent: async ({ ref }) => ({ data: { content: Buffer.from(files[ref]).toString('base64') } }) },
      git: {
        getRef: async () => ({ data: { object: { sha: state.head } } }),
        getCommit: async ({ commit_sha }) => ({ data: commits[commit_sha] || { parents: [], message: '' } }),
      },
    },
    graphql: async (_query, { input }) => {
      assert.equal(input.expectedHeadOid, 'source');
      assert.equal(input.branch.branchName, 'main');
      assert.deepEqual(input.fileChanges.additions.map(file => file.path), ['gradle.properties']);
      if (denied) throw new Error('无写权限');
      if (race === 'newer') { state.head = 'newer'; throw new Error('分支已推进'); }
      state.writes++;
      state.head = 'version-commit';
      files[state.head] = Buffer.from(input.fileChanges.additions[0].contents, 'base64').toString('utf8');
      commits[state.head] = {
        parents: [{ sha: 'source' }], message: `${input.message.headline}\n\n${input.message.body}`,
        verification: { verified: true },
      };
      if (race === 'lost-response') throw new Error('响应丢失');
      return { createCommitOnBranch: { commit: { oid: state.head, signature: { isValid: true } } } };
    },
  };
  const context = { repo: { owner: 'owner', repo: 'repo' }, sha: 'source', ref: 'refs/heads/main',
    eventName: 'push', payload: { before: 'before' } };
  const core = { setOutput: (key, value) => { outputs[key] = value; }, notice: () => {} };
  return { run: () => bumpVersion({ github, context, core }), state, outputs, context };
}

test('递增补丁并保留注释、换行和其他属性，旧开发版本不能覆盖主分支进度', () => {
  assert.deepEqual(bumpVersion.prepare('# 模组\r\nversion=0.1.0\r\njava_version=21\r\n', 'version=0.1.9\n'),
    { version: '0.1.10', content: '# 模组\r\nversion=0.1.10\r\njava_version=21\r\n' });
  assert.equal(bumpVersion.prepare('version=0.2.0\n', 'version=0.1.99\n').version, '0.2.1');
  for (const invalid of ['version=0.1.x\n', 'version=01.1.0\n', 'version=0.1.0\nversion=0.1.1\n', '']) {
    assert.throws(() => bumpVersion.prepare(invalid));
  }
});

test('同一集成重跑复用签名提交，不重复增加版本', async () => {
  const f = fixture(); await f.run(); await f.run();
  assert.equal(f.state.writes, 1);
  assert.deepEqual(f.outputs, { commit: 'version-commit', version: '0.1.1', ready: 'true' });
});

test('旧事件和并发推进都不覆盖新的 main', async () => {
  for (const options of [{ head: 'newer' }, { race: 'newer' }]) {
    const f = fixture(options); await f.run();
    assert.equal(f.state.writes, 0); assert.equal(f.outputs.ready, 'false');
  }
});

test('提交后的响应丢失可恢复，权限错误仍明确失败', async () => {
  const f = fixture({ race: 'lost-response' }); await f.run();
  assert.equal(f.state.writes, 1); assert.equal(f.outputs.ready, 'true');
  await assert.rejects(fixture({ denied: true }).run(), /无写权限/);
});

test('开发分支和非推送事件不能增加版本', async () => {
  for (const patch of [{ ref: 'refs/heads/dev' }, { eventName: 'workflow_dispatch' }]) {
    const f = fixture(); Object.assign(f.context, patch);
    await assert.rejects(f.run(), /main/); assert.equal(f.state.writes, 0);
  }
});
