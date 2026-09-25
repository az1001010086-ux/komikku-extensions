#!/usr/bin/env bash
# 把 tools/make_repo.py 生成的产物推送到 repo 分支。
#
# 为什么用独立分支？raw.githubusercontent.com/<user>/<repo>/repo/index.min.json
# 里那一段是**分支名**，不是目录名。这样源码和产物互不干扰。
#
# 用法：
#   ./tools/publish.sh              # 生成产物并推送
#   ./tools/publish.sh --dry-run    # 只生成产物，不推送
#
# 前置：需要先配好凭据（见 README「推送凭据」小节）

set -euo pipefail

BRANCH="repo"
DIST="repo-dist"
DRY_RUN=0
[ "${1:-}" = "--dry-run" ] && DRY_RUN=1

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

echo "==> 1/4 生成产物"
if command -v python >/dev/null 2>&1; then
    PY=python
else
    PY="C:/Users/Administrator/.workbuddy/binaries/python/versions/3.13.12/python.exe"
fi
"$PY" tools/make_repo.py --verify-apk

if [ ! -f "$DIST/index.min.json" ]; then
    echo "!! 产物缺失：$DIST/index.min.json" >&2
    exit 1
fi

if [ "$DRY_RUN" = "1" ]; then
    echo "==> dry-run 模式，跳过推送"
    echo "    产物在 $DIST/"
    exit 0
fi

echo "==> 2/4 构建 $BRANCH 分支的树对象"

# 关键技术点：
#   用独立索引 + `git add --prefix=repo-dist/` 无法直接达成「去前缀」，
#   所以改用最可靠的办法 —— 临时切换工作树内容并提交。
#   为不动 main 的工作区，用 `git worktree` 隔离；产物目录本身被 gitignore，
#   因此必须用 `git add -f` 强制纳入。

WT=".git/publish-wt"

cleanup() {
    git worktree remove --force "$WT" 2>/dev/null || true
    git worktree prune 2>/dev/null || true
}
trap cleanup EXIT
cleanup   # 清掉可能残留的

# 以远端已有 repo 分支为起点（保持历史连续），没有则用孤立起点
if git ls-remote --exit-code --heads origin "$BRANCH" >/dev/null 2>&1; then
    git fetch -q origin "$BRANCH"
    BASE="FETCH_HEAD"
    echo "    基于远端已有 $BRANCH 分支（$(git log -1 --format=%h FETCH_HEAD) ）"
else
    BASE=""
    echo "    远端无 $BRANCH 分支，从空开始"
fi

git worktree add --detach "$WT" ${BASE:-HEAD} >/dev/null 2>&1

(
    cd "$WT"
    # 清空内容（保留 .git 和 worktree 元数据文件）
    find . -mindepth 1 -maxdepth 1 \
        ! -name '.git' \
        -exec rm -rf {} + 2>/dev/null || true
    # 铺入产物 → 即在分支根目录
    cp -r "$ROOT/$DIST/." .
    git add -Af .
    # 内容无变化时 commit 会失败（幂等重跑的正常情形），不能让它中断脚本
    if git diff --cached --quiet; then
        echo "    产物与 $BRANCH 分支当前内容一致，无需新提交"
        echo "    当前提交：$(git rev-parse --short HEAD)"
    else
        git commit -q -m "chore(repo): 更新扩展索引 $(date '+%Y-%m-%d %H:%M')"
        echo "    新提交：$(git rev-parse --short HEAD)  $(git rev-parse HEAD)"
    fi
    echo "    分支内容："
    git ls-tree -r --name-only HEAD | sed 's/^/      /'
)

COMMIT="$(git -C "$WT" rev-parse HEAD)"

echo "==> 3/4 推送到 origin/$BRANCH"
git push -f origin "$COMMIT:refs/heads/$BRANCH"

echo "==> 4/4 完成"
echo ""
echo "扩展源地址："
git remote get-url origin \
    | sed -E 's#^https://github.com/#https://raw.githubusercontent.com/#; s#\.git$##' \
    | sed "s#\$#/$BRANCH/index.min.json#"
