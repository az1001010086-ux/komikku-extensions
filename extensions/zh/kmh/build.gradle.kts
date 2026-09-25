import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Kmh"
    versionCode = 1
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
