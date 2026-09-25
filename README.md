# komikku-extensions

个人 Komikku 图源扩展仓库。源码在 `main` 分支，构建产物在 `repo` 分支。

## 扩展源地址

```
https://raw.githubusercontent.com/az1001010086-ux/komikku-extensions/repo/index.min.json
```

在 Komikku 里：**设置 → 浏览 → 扩展 → 添加仓库** → 粘贴上面的地址 → 添加。

> URL 里 `repo` 那一段是**分支名**，不是目录名。用独立分支放产物，源码和分发互不干扰。

## 目录结构

```
main 分支（源码）
├── extensions/<lang>/<模块名>/     ← 扩展工程源码
│   ├── build.gradle.kts            ← keiyoushi DSL 配置
│   ├── src/.../<扩展名>.kt          ← @Source 注解的具体类
│   └── res/                        ← 图标等资源
├── tools/
│   ├── make_repo.py                ← 生成 repo-dist/ 产物
│   └── publish.sh                  ← 产物推送到 repo 分支
└── .gitignore

repo 分支（产物，由 publish.sh 自动生成）
├── index.min.json                  ← App 拉的入口（JSON 数组）
├── repo.json                        ← 商店元信息 + 签名指纹
├── apk/<apk 文件名>                 ← App 按 "$base/apk/$apk" 拼
└── icon/<包名>.png                  ← App 按 "$base/icon/$pkg.png" 拼
```

## 添加一个新扩展

1. 在 `extensions/<lang>/<模块名>/` 建工程（参考 `extensions/zh/tutorialdemo/`）
   - `build.gradle.kts` 用 `keiyoushi { }` DSL
   - 源码里 `@Source class Xxx(override val name: String, override val lang: String, override val id: Long, override val baseUrl: String)`
     —— **这四个必须声明为主构造参数**，否则 KSP 报 fatal
2. 构建：`./gradlew :src:<lang>:<模块>:assembleRelease --no-parallel`
3. 在 `tools/make_repo.py` 的 `EXTENSIONS` 列表里加一条
   - `code` 必须等于 APK 的 `versionCode`，`version` 必须等于 `versionName`
   - 不一致会导致 App 误判「有更新」
4. `./tools/publish.sh`

## 推送凭据

用 **Fine-grained PAT**（只授权本仓库、`Contents: Read and write`）。

配好后写入 `~/.git-credentials`（格式 `https://<user>:<token>@github.com`），
并让 git 用 `store` helper 读取：

```bash
git config --global credential.helper store
```

**为什么不用系统自带的 credential-manager？**
本机默认 helper 指向 WorkBuddy 便携版 Git 的目录
（`D:/WorkBuddy_Data/.workbuddy/binaries/PortableGit/versions/1.2.0/...`），
路径里写死了版本号 —— 便携版一升级换目录，helper 就失效，推送会突然要你重输密码。
`store` 是 git 内置的，不依赖任何外部路径。

## 签名与自动信任

`repo.json` 的 `meta.signingKeyFingerprint` 填的是**自己的签名指纹**（64 位小写 hex，无冒号）。

配对它有两个好处：
- 该指纹下签名的所有扩展，App **永久免手点「信任」**
- 改 `versionCode` 也不需要重新信任（判据是 `pkg:versionCode:signatureHash`，
  但指纹已验证 ⇒ 走的是指纹通路）

当前用的是 debug keystore 指纹：

```
c002a9a7b3562f5d897df90e4632799cfc4c18f7fd0f542a3aa3a251488d5848
```

> ⚠️ 若改用 release 签名，**必须**同步更新 `tools/make_repo.py` 里的 `SIGNING_KEY`，
> 否则 App 会把新签名的扩展当「未知来源」要求手点信任。

查指纹（`keytool` 只认 v1 会误报，必须用 apksigner）：

```bash
apksigner verify --print-certs <apk路径>
```

## 几个已验证的坑

| 坑 | 现象 | 正确做法 |
|---|---|---|
| `repo.json.index_v2` 填了值 | App 报 `Expected URL scheme` | 必须填 `null`（字段仍需显式写出） |
| APK 放 GitHub Release 而非目录 | 本机 Komikku 装不上 | 走 `apk/` 目录（App 按 `$base/apk/$apk` 硬拼） |
| `index.min.json` 里的 `name` 不带前缀 | 显示名异常 | 须为 `"Tachiyomi: <显示名>"`，App 会 `substringAfter` |
| 仓库设为 private | App 无法访问 | 必须 public（App 拉取时不带任何认证头） |
| `code` 与 APK 的 versionCode 不符 | 误判「有更新」 | 两者必须一致 |

## 参考

- 扩展制作完整教程：见本机 `komikku-插件扩展制作教程-2026-09-25.md`
- 加载契约依据：`ExtensionLoader.kt`、`TrustExtension.kt`、`NetworkLegacyExtension.kt`
