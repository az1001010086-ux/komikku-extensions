package eu.kanade.tachiyomi.extension.zh.kxmanhua

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
import keiyoushi.utils.attrOrNull
import keiyoushi.utils.textOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 开心看漫画（kxmanhua.com）。
 *
 * 站点为**纯服务端渲染**：列表、详情、章节、图片全部在首屏 HTML 里，
 * 不依赖 JS 渲染 ⇒ 直接用 Jsoup 解析即可（已用 curl 实测原始 HTML 确认）。
 *
 * 实测到的结构（2026-09-25）：
 *   列表页  /manga/library?page=N&orderby=<1|2|3>&type=<0..5>
 *   搜索    /manga/search?keyword=<kw>&page=N        ← 表单 method=GET action=/manga/search
 *   详情页  /manga/<mangaId>
 *             标题       h3
 *             封面       div.anime__details__pic[data-setbg]   （懒加载，写在 data-setbg 而非 src）
 *             作者       .anime__details__title span 内 "作者：xxx / yyy"
 *             简介       .anime__details__text p
 *             分类       .anime__details__widget a[href*="type="]
 *   章节页  /manga/<mangaId>/detail/<chapterId>
 *             章节列表   div.chapter_list > a[href]        （HTML 内倒序，最新的在前）
 *             图片       img[src|data-src] 已是完整 URL（img.imh99.top/webtoon/content/...）
 */
@Source
class KxManhua(
    override val name: String,
    override val lang: String,
    override val id: Long,
    override val baseUrl: String,
) : KeiSource() {

    // ============================ 1. 列表页 ============================

    /** 热门：按人气排序。orderby=2 实测为「最近更新」，1 为默认综合排序。 */
    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaList("$baseUrl/manga/library?page=$page&orderby=2")

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaList("$baseUrl/manga/library?page=$page&orderby=2")

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        // 站点搜索不支持分页参数（实测：带 page 也返回首页结果），
        // 因此仅第 1 页返回结果，后续页返回空以停止翻页。
        if (page > 1) return MangasPage(emptyList(), false)

        val url = "$baseUrl/manga/search".toHttpUrl()
            .newBuilder()
            .addQueryParameter("keyword", query)
            .build()
        return parseMangaList(url.toString())
    }

    private suspend fun parseMangaList(url: String): MangasPage {
        val doc = client.get(url, headers).asJsoup()

        val mangas = doc.select("div.product__item").mapNotNull { it.toSManga() }

        // 分页：站点是「页码式」而非「下一页式」，这里用「本页是否满 24 条」判断
        // （每页恒定 24 条，实测 page=1/2 均如此）
        val hasNextPage = mangas.size >= PAGE_SIZE

        return MangasPage(mangas, hasNextPage)
    }

    private fun Element.toSManga(): SManga? {
        // 标题与链接都在 <h6><a href="/manga/<id>" title="漫画xxx">名字</a></h6>
        val link = selectFirst("h6 a") ?: return null
        val href = link.attrOrNull("href") ?: return null
        val title = link.textOrNull() ?: link.attrOrNull("title") ?: return null

        // 封面走懒加载属性 data-setbg（外层 div.product__item__pic）
        val cover = selectFirst("div[data-setbg]")?.attrOrNull("data-setbg")

        return SManga.create().apply {
            url = href.toMangaSlug() ?: return null
            this.title = title
            thumbnail_url = cover
        }
    }

    // ====================== 2. 详情 + 章节列表 ======================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // 详情与章节同页返回 ⇒ 一次请求全拿到，不必按 flag 拆两次
        val doc = client.get(mangaUrl(manga), headers).asJsoup()

        if (fetchDetails) {
            doc.parseDetailsInto(manga)
        }

        return SMangaUpdate(manga, if (fetchChapters) doc.parseChapters() else chapters)
    }

    private fun Document.parseDetailsInto(manga: SManga) {
        selectFirst("div.anime__details__title h3")
            ?.textOrNull()
            ?.let { manga.title = it }

        // 「作者：Elise God  /  爆红王」——去掉前缀并保留原样
        selectFirst("div.anime__details__title span")
            ?.textOrNull()
            ?.takeIf { it.startsWith("作者") }
            ?.let { manga.author = it.substringAfter("：", it).trim() }

        // 简介在 .anime__details__text 下的 <p>，可能有多个（第一个常为空）
        select("div.anime__details__text > p")
            .mapNotNull { it.textOrNull() }
            .firstOrNull()
            ?.let { manga.description = it }

        // 分类链接形如 /manga/library?type=3
        manga.genre = select("div.anime__details__widget a[href*=type=]")
            .mapNotNull { it.textOrNull() }
            .distinct()
            .joinToString(", ")
            .takeUnless { it.isBlank() }

        manga.thumbnail_url = selectFirst("div.anime__details__pic")
            ?.attrOrNull("data-setbg")
            ?: manga.thumbnail_url

        // 「完结」/「连载」标记
        manga.status = when {
            selectFirst("div.epgreen")?.textOrNull() == "完结" -> SManga.COMPLETED
            selectFirst("div.ep")?.textOrNull() == "连载" -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    private fun Document.parseChapters(): List<SChapter> = select("div.chapter_list a[href]")
        .mapNotNull { a ->
            val href = a.attrOrNull("href") ?: return@mapNotNull null
            val chapterUrl = href.toChapterSlug() ?: return@mapNotNull null
            SChapter.create().apply {
                url = chapterUrl
                name = a.textOrNull() ?: return@mapNotNull null
            }
        }
        // 站点是「最新章在前」的倒序，反转成「第1话 → 最新话」便于阅读
        .reversed()

    // ========================== 3. 图片页 ==========================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val doc = client.get(chapterUrl(chapter), headers).asJsoup()

        // ★ 站点在章节页尾部插了 4 张广告图（/webtoon/ad-slider/*.png|gif）。
        //   若不过滤，用户会在章节末尾看到广告被当成漫画页。
        //   实测（2026-09-25）：真实漫画图**全部**在 /webtoon/content/ 路径下，
        //   广告在 /webtoon/ad-slider/ 下 —— 用路径白名单即可干净切分，
        //   不必依赖文档序比较（更稳、更易读）。
        return doc.select("img")
            .mapNotNull { img ->
                val src = img.attrOrNull("data-src") ?: img.attrOrNull("src") ?: return@mapNotNull null
                if (!src.startsWith("http")) return@mapNotNull null
                // 白名单：只保留漫画正文图
                if (!src.contains("/webtoon/content/")) return@mapNotNull null
                src
            }
            .distinct()
            .mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    // ====================== 4. URL 组装/解析 ======================

    /** `manga.url` 只存 mangaId（如 `2390`）。 */
    private fun mangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}"

    /** `chapter.url` 存 `<mangaId>/detail/<chapterId>`。 */
    private fun chapterUrl(chapter: SChapter): String = "$baseUrl/manga/${chapter.url}"

    override fun getMangaUrl(manga: SManga): String = mangaUrl(manga)

    override fun getChapterUrl(chapter: SChapter): String = chapterUrl(chapter)

    /**
     * 支持在搜索框粘贴本站链接。
     * 形如 https://kxmanhua.com/manga/2390 或 .../manga/2390/detail/84742
     */
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = url.toString().toMangaSlug() ?: return null
        return SManga.create().apply {
            this.url = slug
            title = slug
        }
    }

    // ============================ 工具 ============================

    /** 从 `/manga/2390` 或 `/manga/2390/detail/84742` 提取 `2390`。 */
    private fun String.toMangaSlug(): String? = MANGA_ID_REGEX.find(this)?.groupValues?.get(1)

    /** 从 `/manga/2390/detail/84742` 提取 `2390/detail/84742`。 */
    private fun String.toChapterSlug(): String? = CHAPTER_PATH_REGEX.find(this)?.let {
        "${it.groupValues[1]}/detail/${it.groupValues[2]}"
    }

    private companion object {
        const val PAGE_SIZE = 24

        val MANGA_ID_REGEX = Regex("""/manga/(\d+)""")
        val CHAPTER_PATH_REGEX = Regex("""/manga/(\d+)/detail/(\d+)""")
    }
}
