package eu.kanade.tachiyomi.extension.zh.kmh

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.textOrNull
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import java.util.Base64

/**
 * K漫画（kmh001.com）。
 *
 * 站点是 Next.js App Router（RSC）应用，但**数据全部 SSR 进 HTML**，
 * 因此与普通 jsoup 站点一样解析即可，无需执行 JS。
 *
 * 详细站点结构见 `kmh001-站点勘察报告-2026-09-25.md`。要点：
 *  - 列表页 `/home`（48 部，分页无效）、`/complete?page=N`（每页 12 部，可翻页）
 *  - 搜索页 `/search?keyword=`（SSR，约 24 部，无分页）
 *  - 详情页 `/comic/<comicId>`（SSR，章节在 RSC payload 的 JSON 里）
 *  - 阅读页 `/chapter/<chapterId>`（SSR，图片在 RSC payload 的 `"images"` 数组里）
 *  - 封面 URL 可由标题直接推导：`https://img.kmh.pics/<base64url(标题)>-cover.jpg`
 *    （HTML 里的 `<img src>` 是懒加载占位 `/images/loading.webp`，**不可用**）
 *  - `/weekly`（连载）、`/category`（分类）为客户端渲染，curl 拿不到 ⇒ 本扩展不使用
 *
 * ⚠️ 章节 `ordinal` 在站点侧已是**升序**（第1话在前），与 kxmanhua 相反。
 */
@Source
class Kmh(
    override val name: String,
    override val lang: String,
    override val id: Long,
    override val baseUrl: String,
) : KeiSource() {

    private val imageCdn = "https://img.kmh.pics"

    /**
     * ⚠️ 站点资源（封面 + 章节图）有 **Referer 防盗链**：
     * 不带 `Referer: https://kmh001.com/` 时图床/CDN 返回 **404**，
     * 带上则 200（实测 `img.kmh.pics` 32KB jpeg / `new.niaopic.com` 200 jpeg）。
     * 因此所有请求统一带本站 Referer。
     */
    private val imgHeaders = Headers.Builder()
        .add("Referer", "$baseUrl/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36")
        .build()

    // ============================ 1. 列表页 ============================

    /**
     * 热门 / 最新。
     *
     * 第 1 页用首页（含「今日更新」+「完本」两区，48 部，内容更丰富）；
     * 后续页用 `/complete` 翻页（实测每页 12 部，p1~p40+ 有效）。
     */
    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (page <= 1) "$baseUrl/home" else "$baseUrl/complete?page=$page"
        return parseMangaList(url, page)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        // 站点搜索为 SSR 单页结果，无分页参数 ⇒ 仅第 1 页返回。
        if (page > 1) return MangasPage(emptyList(), false)

        val url = "$baseUrl/search".toHttpUrl()
            .newBuilder()
            .addQueryParameter("keyword", query)
            .build()
        return parseMangaList(url.toString(), page)
    }

    /**
     * 列表页解析。`/home`、`/complete`、`/search` 三者条目结构一致：
     *
     * ```html
     * <a href="/comic/<id>">
     *   <div class="relative">
     *     <img src="/images/loading.webp"/>            <!-- 懒加载占位，勿用 -->
     *     <h4>第7话 无套插进来了♥</h4>                  <!-- 最新章节 -->
     *   </div>
     *   <h3 class="font-bold truncate">欲望入门课</h3>   <!-- 标题 -->
     * </a>
     * ```
     *
     * `/home` 首屏同时含「今日更新」与「完本」两区，故对 slug 去重。
     * 封面不由 HTML 取（是占位图），而是**按标题推导**（见 [coverUrl]）。
     */
    private suspend fun parseMangaList(url: String, page: Int): MangasPage {
        val doc = client.get(url, imgHeaders).asJsoup()

        val seen = LinkedHashSet<String>()
        val mangas = doc.select("a[href^=/comic/]").mapNotNull { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val slug = href.removePrefix("/comic/").substringBefore('/').takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val title = a.selectFirst("h3")?.textOrNull() ?: return@mapNotNull null

            if (!seen.add(slug)) return@mapNotNull null

            SManga.create().apply {
                this.url = slug
                this.title = title
                thumbnail_url = coverUrl(title)
            }
        }

        // /home 恒 48 部且分页无效 ⇒ 只在用 /complete 翻页时判断下一页。
        // 实测 /complete 每页 12 部、超出末页返回 0 部。
        val hasNextPage = page <= 1 || mangas.size >= COMPLETE_PAGE_SIZE
        return MangasPage(mangas, hasNextPage)
    }

    // ====================== 2. 详情 + 章节列表 ======================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // 详情与章节同页返回 ⇒ 一次请求全拿到，不必按 flag 拆两次
        val doc = client.get(mangaUrl(manga), imgHeaders).asJsoup()

        if (fetchDetails) {
            doc.parseDetailsInto(manga)
        }

        return SMangaUpdate(manga, if (fetchChapters) doc.parseChapters() else chapters)
    }

    /**
     * 详情解析。
     *
     * 详情页是 SSR，字段各自有稳定的 `<span class>`，实测结构（`d1.html`）：
     *
     * ```html
     * <span class="text-xl font-bold">欲望入门课</span>                    <!-- 标题 -->
     * <span class="truncate">作者：kortoon&amp;俊斯&amp;隔壁坏哥哥</span>   <!-- 作者 -->
     * <span>更新：第7话</span>
     * <p class="text-sm text-gray-600">爱文对性充满了好奇…</p>              <!-- 简介 -->
     * <span class="p-1 leading-8 text-white rounded whitespace-nowrap"
     *       style="background-color:#CC9966">堕落 &gt;</span>              <!-- 分类（可多个） -->
     * <span class="p-1 …" style="…">20岁 &gt;</span>
     * ```
     *
     * ⚠️ 教训：初版用「整页纯文本按行扫描」，但站点把可见文本**内联在全站
     *    共用的 `<script>` 引导代码里**（详情页首个超长文本行就是一段 `function(){}`），
     *    导致按行定位全部失效（作者/分类解析为 null）。**必须用选择器**。
     */
    private fun Document.parseDetailsInto(manga: SManga) {
        // 标题
        selectFirst("span.text-xl.font-bold")
            ?.textOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { manga.title = it }

        // 作者：`<span class="truncate">作者：A&B&C</span>`（`&amp;` 由 jsoup 自动还原）
        selectFirst(AUTHOR_SPAN)
            ?.textOrNull()
            ?.substringAfter("：", "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { manga.author = it }

        // 简介
        selectFirst("p.text-sm.text-gray-600")
            ?.textOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { manga.description = it }

        // 分类：多个彩色标签，文本形如 `堕落 >`
        select(GENRE_SPAN)
            .map { it.text().replace(">", "").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
            ?.let { manga.genre = it }

        // 封面兜底（列表页已给，但直接跳详情时可能为空）
        if (manga.thumbnail_url.isNullOrBlank()) {
            manga.thumbnail_url = coverUrl(manga.title.ifBlank { manga.url })
        }
    }

    /**
     * 章节列表。
     *
     * 数据在 RSC payload 里，形如：
     * ```json
     * {"comicID":"…","data":[
     *   {"_id":"<chapterId>","title":"好想知道做爱的感觉","subtitle":"第1话",
     *    "ordinal":1,"updateAt":"2026-09-23T16:00:00.000Z"}, …]}
     * ```
     *
     * ⚠️ `ordinal` 在站点侧已是升序（第1话在前）⇒ **不做 reversed**（与 kxmanhua 相反）。
     *    这里仍按 `ordinal` 显式排序，避免站点偶发改序。
     */
    private fun Document.parseChapters(): List<SChapter> {
        val payload = rscPayload() ?: return emptyList()
        val match = CHAPTER_DATA.find(payload) ?: return emptyList()
        // ★ 必须从匹配到的 `[` 的**真实位置**起截取到结尾，再交给 extractJsonObjects。
        //   ❌ 曾经的致命 bug：传 `match.groupValues[1]`（那只是捕获组里的单个 `"["` 字符）
        //      ⇒ extractJsonObjects 找不到配对 `]` ⇒ 恒空 ⇒ App 里永远没有章节。
        //   ⚠️ 这个 bug 当初没被脚本抓到，因为脚本用的是 `payload.find(...)` 真实下标、
        //      与 Kotlin 实现**不一致**。教训：验证脚本必须与 .kt **逐句同构**。
        val arrayText = payload.substring(match.range.first)

        return extractJsonObjects(arrayText)
            .mapNotNull { obj ->
                val chapterId = CHAPTER_ID_FIELD.find(obj)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                val subtitle = SUBTITLE_FIELD.find(obj)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                val title = TITLE_FIELD.find(obj)?.groupValues?.get(1)
                val ordinal = ORDINAL_FIELD.find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: 0

                SChapter.create().apply {
                    url = chapterId
                    name = if (title.isNullOrBlank()) subtitle else "$subtitle $title"
                    chapter_number = ordinal.toFloat()
                } to ordinal
            }
            .sortedBy { it.second }
            .map { it.first }
    }

    // ========================== 3. 图片页 ==========================

    /**
     * 章节图片。
     *
     * RSC payload 里：
     * ```json
     * "images":[
     *   {"sourceID":"…","sourceName":"NNHANMAN",
     *    "url":"https://new.niaopic.com/…jpg","sortIndex":1}, …]
     * ```
     *
     * ⚠️ 同一页有多条**镜像**记录（实测 606 条 / `sortIndex` 1~303 ⇒ 每 index 2 个源）。
     *    备用域名（`www.jjmhw8.top`）实测 404 ⇒ 侦察确认 `new.niaopic.com` 在前，
     *    按 `sortIndex` **保留首条**即可干净去重。
     */
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val doc = client.get(chapterUrl(chapter), imgHeaders).asJsoup()
        val payload = doc.rscPayload() ?: return emptyList()

        val match = IMAGES_ARRAY.find(payload) ?: return emptyList()
        // ★ 同 parseChapters：必须从 `[` 的真实位置起截取（勿传捕获组）。
        val arrayText = payload.substring(match.range.first)

        return extractJsonObjects(arrayText)
            .mapNotNull { obj ->
                val url = URL_FIELD.find(obj)?.groupValues?.get(1)
                    ?.replace("\\/", "/")
                    ?.takeIf { it.startsWith("http") && !it.contains(AD_HOST) }
                    ?: return@mapNotNull null
                val index = SORT_INDEX_FIELD.find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                index to url
            }
            .distinctBy { it.first }
            .sortedBy { it.first }
            .mapIndexed { i, (_, url) -> Page(i, imageUrl = url) }
    }

    // ====================== 4. URL 组装/解析 ======================

    /** `manga.url` 只存 comicId。 */
    private fun mangaUrl(manga: SManga): String = "$baseUrl/comic/${manga.url}"

    /** `chapter.url` 只存 chapterId。 */
    private fun chapterUrl(chapter: SChapter): String = "$baseUrl/chapter/${chapter.url}"

    override fun getMangaUrl(manga: SManga): String = mangaUrl(manga)

    override fun getChapterUrl(chapter: SChapter): String = chapterUrl(chapter)

    /** 支持在搜索框粘贴本站链接，形如 `https://kmh001.com/comic/<id>`。 */
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = MANGA_SLUG.find(url.toString())?.groupValues?.get(1) ?: return null
        return SManga.create().apply {
            this.url = slug
            title = slug
        }
    }

    // ============================ 工具 ============================

    /**
     * 封面 URL 推导。
     *
     * ★ 精确公式（2026-09-25 修订，实测全站命中）：
     * ```
     * https://img.kmh.pics/<base64url(标题 + "-cover")>.jpg
     * ```
     * 即**把 `标题 + "-cover"` 整体做 UTF-8 base64url 编码**（`+`→`-`、`/`→`_`、去 `=` 填充），
     * 再拼 `.jpg`。
     *
     * 实例：`欲望入门课` → `5qyy5pyb5YWl6Zeo6K--LWNvdmVy.jpg`
     * （解码回去正是 `欲望入门课-cover`）。
     *
     * ⚠️ 教训：初版误写成「只编码标题，再拼字面量 `-cover.jpg`」，
     *    得 `5qyy5pyb5YWl6Zeo6K---cover.jpg`（多一个 `-`、缺少 `LWNvdmVy` 段）⇒ 全站 404。
     *    **`-cover` 是参与 base64 的原文的一部分，不是后缀字面量。**
     *
     * HTML 里的 `<img src>` 是懒加载占位（`/images/loading.webp`），
     * 因此**不能**从 HTML 取封面，只能由此推导。
     */
    private fun coverUrl(title: String): String {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$title-cover".toByteArray(Charsets.UTF_8))
        return "$imageCdn/$encoded.jpg"
    }

    /**
     * 取出页面里全部 `self.__next_f.push([1,"…"])` 块并解码拼接。
     *
     * Next.js RSC 的数据以 **JSON 字符串字面量**内联在 `<script>` 里，
     * 需要还原 `\"` `\\` `\uXXXX` 等转义后才能当作 JSON 解析。
     */
    private fun Document.rscPayload(): String? {
        val html = outerHtml()
        val blocks = NEXT_PUSH.findAll(html).toList()
        if (blocks.isEmpty()) return null
        return buildString {
            for (b in blocks) {
                append(decodeJsString(b.groupValues[1]))
            }
        }
    }

    /** 还原 JS 字符串字面量里的转义。 */
    private fun decodeJsString(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i + 1 >= raw.length) {
                sb.append(c)
                i++
                continue
            }
            when (val n = raw[i + 1]) {
                '"' -> {
                    sb.append('"')
                    i += 2
                }
                '\\' -> {
                    sb.append('\\')
                    i += 2
                }
                '/' -> {
                    sb.append('/')
                    i += 2
                }
                'n' -> {
                    sb.append('\n')
                    i += 2
                }
                't' -> {
                    sb.append('\t')
                    i += 2
                }
                'r' -> {
                    sb.append('\r')
                    i += 2
                }
                'b' -> {
                    sb.append('\b')
                    i += 2
                }
                'f' -> {
                    sb.append('\u000C')
                    i += 2
                }
                'u' -> {
                    val code = if (i + 6 <= raw.length) {
                        raw.substring(i + 2, i + 6).toIntOrNull(16)
                    } else {
                        null
                    }
                    if (code != null) {
                        sb.append(code.toChar())
                        i += 6
                    } else {
                        sb.append(n)
                        i += 2
                    }
                }
                else -> {
                    sb.append(n)
                    i += 2
                }
            }
        }
        return sb.toString()
    }

    /**
     * 从 `text` 里第一个 `[` 起按括号平衡取出数组内容，再按顶层 `{}` 拆成各对象。
     *
     * 之所以手工扫描而不用正则：JSON 字符串里可能含 `[` `]` `{` `}`，
     * 正则无法正确配对（站点简介里就有中文括号与引号）。
     */
    private fun extractJsonObjects(text: String): List<String> {
        val start = text.indexOf('[')
        if (start < 0) return emptyList()

        var depth = 0
        var inString = false
        var escaped = false
        var end = -1

        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        if (end < 0) return emptyList()

        val body = text.substring(start + 1, end)
        val objects = mutableListOf<String>()
        var objDepth = 0
        var objStart = -1
        var inStr = false
        var esc = false

        for (i in body.indices) {
            val c = body[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> {
                    if (objDepth == 0) objStart = i
                    objDepth++
                }
                '}' -> {
                    objDepth--
                    if (objDepth == 0 && objStart >= 0) {
                        objects.add(body.substring(objStart, i + 1))
                        objStart = -1
                    }
                }
            }
        }
        return objects
    }

    private companion object {
        /** `/complete` 每页条目数（实测）。 */
        const val COMPLETE_PAGE_SIZE = 12

        /** 备用图床域名（实测 404，过滤掉）。 */
        const val AD_HOST = "jjmhw8"

        /** 匹配 `self.__next_f.push([1,"…"])` 中的字符串字面量（含转义）。 */
        val NEXT_PUSH = Regex(
            """self\.__next_f\.push\(\[1,("(?:[^"\\]|\\.)*")\]\)""",
            RegexOption.DOT_MATCHES_ALL,
        )

        /** 章节数组：定位到 `"comicID":"…","data":[` 之后的 `[`。 */
        val CHAPTER_DATA = Regex("""\"comicID\":\"[a-f0-9]{24}\",\"data\":(\[)""")

        /** 图片数组：`"images":[`。 */
        val IMAGES_ARRAY = Regex("""\"images\":(\[)""")

        val CHAPTER_ID_FIELD = Regex(""""_id":"([A-Za-z0-9]+)"""")
        val TITLE_FIELD = Regex(""""title":"((?:[^"\\]|\\.)*)"""")
        val SUBTITLE_FIELD = Regex(""""subtitle":"((?:[^"\\]|\\.)*)"""")
        val ORDINAL_FIELD = Regex(""""ordinal":(\d+)""")
        val URL_FIELD = Regex(""""url":"((?:[^"\\]|\\.)*)"""")
        val SORT_INDEX_FIELD = Regex(""""sortIndex":(\d+)""")

        val MANGA_SLUG = Regex("""/comic/([A-Za-z0-9]+)""")

        /** 详情页作者：`<span class="truncate">作者：…</span>`。 */
        const val AUTHOR_SPAN = "span.truncate"

        /** 详情页分类标签：彩色圆角 `span`（`style` 里带背景色）。 */
        const val GENRE_SPAN = "span.p-1.leading-8.text-white.rounded.whitespace-nowrap"
    }
}
