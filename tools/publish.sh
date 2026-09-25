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

echo "==> 1/4 生成产物"
python tools/make_repo.py --verify-apk

if [ ! -f "$DIST/index.min.json" ]; then
    echo "!! 产物缺失：$DIST/index.min.json" >&2
    exit 1
fi

if [ "$DRY_RUN" = "1" ]; then
    echo "==> dry-run 模式，跳过推送"
    echo "    产物在 $DIST/"
    exit 0
fi

echo "==> 2/4 把产物提交到 $BRANCH 分支"

# 用一条临时索引（index file）提交，不动当前工作区、不切分支
# 这样可以在 main 分支上直接推 repo 分支，避免来回 checkout
TMP_INDEX="$(mktemp -u).idx"
export GIT_INDEX_FILE="$TMP_INDEX"

# 若远端已有 repo 分支，以它为起点（保持历史连续）；否则从空开始
if git ls-remote --exit-code --heads origin "$BRANCH" >/dev/null 2>&1; then
    git fetch -q origin "$BRANCH"
    git read-tree FETCH_HEAD
    echo "    基于远端已有 $BRANCH 分支（$(git log -1 --format=%h FETCH_HEAD) ）"
else
    echo "    远端无 $BRANCH 分支，将新建"
fi

git add -f "$DIST"
TREE="$(git write-tree)"
PARENT=""
if git rev-parse --verify FETCH_HEAD >/dev/null 2>&1; then
    PARENT="-p FETCH_HEAD"
fi
COMMIT="$(git commit-tree "$TREE" $PARENT -m "chore(repo): 更新扩展索引 $(date '+%Y-%m-%d %H:%M')")"
echo "    新提交：$COMMIT"

unset GIT_INDEX_FILE
rm -f "$TMP_INDEX"

echo "==> 3/4 推送到 origin/$BRANCH"
git push origin "$COMMIT:refs/heads/$BRANCH"

echo "==> 4/4 完成"
echo ""
echo "扩展源地址："
git remote get-url origin | sed -E 's#^https://github.com/#https://raw.githubusercontent.com/#; s#\.git$##' \
    | sed "s#\$#/$BRANCH/index.min.json#"
