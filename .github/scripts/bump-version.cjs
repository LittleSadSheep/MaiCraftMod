'use strict';

const HEADLINE = 'ci(version): 为主分支集成构建递增补丁版本';

// Mod 版本支持标准三段数字及可选预发布后缀（如 1.0.0-rc1），拒绝重复字段或非法格式。
const VERSION_PATTERN = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z]+(?:\.[0-9A-Za-z]+)*))?$/;

function readVersion(text) {
  const fields = [...text.matchAll(/^version=([^\r\n]*)/gm)];
  if (fields.length !== 1) {
    throw new Error('gradle.properties 必须包含唯一的 version=主版本.次版本.补丁版本[-预发布标识]');
  }
  const match = fields[0][1].match(VERSION_PATTERN);
  if (!match) {
    throw new Error('gradle.properties 必须包含唯一的 version=主版本.次版本.补丁版本[-预发布标识]');
  }
  return {
    raw: fields[0][1],
    major: BigInt(match[1]),
    minor: BigInt(match[2]),
    patch: BigInt(match[3]),
    prerelease: match[4] || null,
  };
}

function compareVersions(a, b) {
  if (a.major !== b.major) return a.major < b.major ? -1 : 1;
  if (a.minor !== b.minor) return a.minor < b.minor ? -1 : 1;
  if (a.patch !== b.patch) return a.patch < b.patch ? -1 : 1;
  if (a.prerelease === b.prerelease) return 0;
  // SemVer: 有预发布标识的版本优先级低于没有预发布标识的正式版本
  if (a.prerelease && !b.prerelease) return -1;
  if (!a.prerelease && b.prerelease) return 1;
  // 两个都有预发布标识时，提取末尾数字进行递增比较
  const matchA = a.prerelease.match(/^(.*?)(\d+)$/);
  const matchB = b.prerelease.match(/^(.*?)(\d+)$/);
  if (matchA && matchB && matchA[1] === matchB[1]) {
    const numA = BigInt(matchA[2]);
    const numB = BigInt(matchB[2]);
    if (numA !== numB) return numA < numB ? -1 : 1;
  }
  return a.prerelease.localeCompare(b.prerelease);
}

function bumpPrerelease(prerelease) {
  const match = prerelease.match(/^(.*?)(\d+)$/);
  if (match) {
    const nextNum = BigInt(match[2]) + 1n;
    return `${match[1]}${nextNum}`;
  }
  return `${prerelease}2`;
}

// 合入旧开发分支时保留主分支已递增的进度；预发布版本支持保留显式声明或递增预览号，正式版本递增补丁号。
function prepare(source, previous = source) {
  const sourceVer = readVersion(source);
  const prevVer = previous ? readVersion(previous) : sourceVer;

  let version;
  if (sourceVer.prerelease) {
    const cmp = compareVersions(sourceVer, prevVer);
    if (cmp > 0) {
      // source 显式指定了高于 previous 的新预发布版本（如从 0.1.10 跨到 1.0.0-rc1）
      version = sourceVer.raw;
    } else {
      // 在同一预发布系列上继续集成，递增预发布号（如 rc1 -> rc2）
      const nextPre = bumpPrerelease(prevVer.prerelease || sourceVer.prerelease);
      version = `${prevVer.major}.${prevVer.minor}.${prevVer.patch}-${nextPre}`;
    }
  } else if (prevVer.prerelease && sourceVer.major === prevVer.major &&
             sourceVer.minor === prevVer.minor && sourceVer.patch === prevVer.patch) {
    // 从预发布转正为正式版本（如 1.0.0-rc2 -> 1.0.0），保留首个正式版本号
    version = sourceVer.raw;
  } else {
    // 正式版本：保留原逻辑，取两者较大者并递增补丁号
    const candidates = [sourceVer, prevVer];
    candidates.sort(compareVersions);
    const chosen = candidates[1];
    version = `${chosen.major}.${chosen.minor}.${chosen.patch + 1n}`;
  }
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
