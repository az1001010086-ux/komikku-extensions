import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Kmh"
    // ★ 2026-09-25：1 → 2。
    //   原因：修正了「章节/图片恒为空」的致命解析 bug（extractJsonObjects 收到被截断的串）。
    //   App 判「有更新」的唯一判据是 availableExt.versionCode > installedExt.versionCode
    //   （ExtensionApi.kt:85）⇒ **必须递增 versionCode，仅改内容不生效**。
    //   最终 APK versionCode = 106000 + 2 = 106002。
    versionCode = 2
    // ⚠️ 站点定位为成人韩漫，按 manifest 契约必须标 NSFW（=2）。
    //    contentWarning: SAFE=0 / MIXED=1 / NSFW=2
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        name = "K漫画"
        lang = "zh"
        baseUrl = "https://kmh001.com"
    }
}
