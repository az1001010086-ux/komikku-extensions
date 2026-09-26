import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hanmanwu"
    // v1.6.2 = 登录版（v1.6.1 为匿名版）。
    // ★ versionCode 决定「更新是否被识别」：App 侧判据是
    //   `availableExt.versionCode > installedExt.versionCode`（ExtensionApi.kt:85）
    //   ⇒ 必须严格递增，否则用户永远收不到更新。
    versionCode = 2
    // ⚠️ 站点定位为成人韩漫，按 manifest 契约必须标 NSFW（=2）。
    //    contentWarning: SAFE=0 / MIXED=1 / NSFW=2
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        name = "韩漫屋"
        lang = "zh"
        baseUrl = "https://hmanwu.com"
    }
}
