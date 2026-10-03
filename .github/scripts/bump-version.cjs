'use strict';

const HEADLINE = 'ci(version): 为主分支集成构建递增补丁版本';

// Mod 版本必须可明确递增，拒绝重复字段或非三段数字，避免两种加载器带着不同版本打包。
function readVersion(text) {
  const fields = [...text.matchAll(/^version=([^\r\n]*)/gm)];
  if (fields.length !== 1 || !/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(fields[0][1])) {
    throw new Error('gradle.properties 必须包含唯一的 version=主版本.次版本.补丁版本');
  }
  return fields[0][1].split('.').map(BigInt);
}

// 合入旧开发分支时保留主分支已递增的进度，再增加补丁号，防止版本倒退或重复。
function prepare(source, previous = source) {
  const candidates = [readVersion(source), readVersion(previous)];
  candidates.sort((a, b) => {
    for (let i = 0; i < 3; i++) if (a[i] !== b[i]) return a[i] < b[i] ? -1 : 1;
    return 0;
  });
  const [major, minor, patch] = candidates[1];
  const version = `${major}.${minor}.${patch + 1n}`;
  return { version, content: source.replace(/^version=[^\r\n]*/m, `version=${version}`) };
}

module.exports = async function bumpVersion({ github, context, core }) {
  if (context.ref !== 'refs/heads/main' || context.eventName !== 'push') {
    throw new Error('版本递增只允许由 main 分支推送触发');
  }
  const repo = context.repo;
  const sourceSha = context.sha;
  const before = context.payload.before;
  const readProperties = async ref => {
    const { data } = await github.rest.repos.getContent({ ...repo, path: 'gradle.properties', ref });
    return Buffer.from(data.content, 'base64').toString('utf8');
  };
  const [source, previous] = await Promise.all([
    readProperties(sourceSha),
    before && !/^0+$/.test(before) ? readProperties(before) : Promise.resolve(undefined),
  ]);
  const plan = prepare(source, previous);
  const body = `构建版本：${plan.version}\n集成提交：${sourceSha}`;
  const message = `${HEADLINE}\n\n${body}`;
  const head = async () => (await github.rest.git.getRef({ ...repo, ref: 'heads/main' })).data.object.sha;
  const ready = commit => {
    core.setOutput('commit', commit);
    core.setOutput('version', plan.version);
    core.setOutput('ready', 'true');
    core.notice(`本次集成使用版本 ${plan.version}，构建提交 ${commit}`);
  };
  // 同一次 CI 重跑时复用已签名的版本提交；主分支若已有新的集成，则让新工作流接手。
  const recover = async current => {
    const { data } = await github.rest.git.getCommit({ ...repo, commit_sha: current });
    if (data.parents.length === 1 && data.parents[0].sha === sourceSha && data.message.trimEnd() === message) {
      if (!data.verification.verified || await readProperties(current) !== plan.content) {
        throw new Error('已有版本提交的签名或文件内容不符合本次集成');
      }
      ready(current);
    } else {
      core.setOutput('ready', 'false');
      core.notice('main 已有更新，本次旧集成不再修改版本或启动构建');
    }
  };
  const current = await head();
  if (current !== sourceSha) return recover(current);
  try {
    // GitHub 负责签名；仅在 main 仍指向本次集成时原子回写，避免覆盖并发合入的代码。
    const result = await github.graphql(`mutation($input: CreateCommitOnBranchInput!) {
      createCommitOnBranch(input: $input) { commit { oid signature { isValid } } }
    }`, { input: {
      branch: { repositoryNameWithOwner: `${repo.owner}/${repo.repo}`, branchName: 'main' },
      expectedHeadOid: sourceSha,
      message: { headline: HEADLINE, body },
      fileChanges: { additions: [{ path: 'gradle.properties', contents: Buffer.from(plan.content).toString('base64') }] },
    } });
    const commit = result.createCommitOnBranch.commit;
    if (!commit.signature?.isValid) throw new Error('自动版本提交未通过 GitHub 签名验证');
    ready(commit.oid);
  } catch (error) {
    // 网络中断可能发生在提交已创建之后；先核实远端结果，再决定复用或如实报错。
    const latest = await head();
    if (latest === sourceSha) throw error;
    await recover(latest);
  }
};
module.exports.prepare = prepare;
