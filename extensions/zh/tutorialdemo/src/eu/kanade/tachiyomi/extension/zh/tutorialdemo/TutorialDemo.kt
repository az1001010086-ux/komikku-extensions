package eu.kanade.tachiyomi.extension.zh.tutorialdemo

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
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Instant

/**
 * 教程示例扩展。
 *
 * ★★ 具体类（非 abstract）的硬性契约，由 KSP 强制校验（SourceProcessor.validateConcreteSource）：
 *   1. `override val name` / `override val lang` / `override val id` **必须**声明为主构造参数，
 *      否则 fatal 编译错误（KSP 只显式校验后两个，`name` 会以「not abstract and does not
 *      implement abstract member」的形式在 kotlinc 阶段报出）；
 *   2. `baseUrl` 走 DSL 的静态 URL ⇒ 也声明为构造参数（mirror/自定义 baseUrl 才需要 abstract）；
 *   3. **不要**声明 `versionId`（归 DSL 所有）。
 *
 * 生成器（SourceProcessor.buildConcreteSource）会按构造参数逐个注入：
 *   TutorialDemo(name = "教程示例", lang = "zh", id = <MD5 推导值>, baseUrl = "https://example.com")
 */
@Source
class TutorialDemo(
    override val name: String,
    override val lang: String,
    override val id: Long,
    override val baseUrl: String,
) : KeiSource() {

    // ============================ 1. 列表页 ============================

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaList("$baseUrl/popular?page=$page")

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaList("$baseUrl/latest?page=$page")

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage = parseMangaList("$baseUrl/search?q=$query&page=$page")

    private suspend fun parseMangaList(url: String): MangasPage {
        val doc = client.get(url, headers).asJsoup()
        val mangas = doc.select("div.manga-list div.item").mapNotNull { it.toSManga() }
        val hasNextPage = doc.selectFirst("a.next") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun Element.toSManga(): SManga? {
        val link = selectFirst("a.title") ?: return null
        val href = link.attrOrNull("href") ?: return null
        val name = link.textOrNull() ?: return null

        return SManga.create().apply {
            // url 建议只存 slug / 相对路径，完整 URL 由 getMangaUrl() 拼
            url = href.removePrefix("/")
            title = name
            thumbnail_url = selectFirst("img")?.let { it.attrOrNull("data-src") ?: it.attrOrNull("src") }
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
        val doc = client.get(getMangaUrl(manga), headers).asJsoup()

        if (fetchDetails) {
            doc.parseDetailsInto(manga)
        }

        return SMangaUpdate(manga, if (fetchChapters) doc.parseChapters() else chapters)
    }

    private fun Document.parseDetailsInto(manga: SManga) {
        manga.title = selectFirst("h1.title")?.textOrNull() ?: manga.title
        manga.author = selectFirst(".author")?.textOrNull() ?: manga.author
        manga.description = selectFirst(".summary")?.textOrNull() ?: manga.description
        manga.genre = select(".tags a").joinToString(", ") { it.text() }
        manga.status = SManga.ONGOING
        manga.thumbnail_url = selectFirst(".cover img")?.attrOrNull("src") ?: manga.thumbnail_url
    }

    private fun Document.parseChapters(): List<SChapter> = select("#chapter-list li a").mapNotNull { a ->
        val href = a.attrOrNull("href")?.removePrefix("/") ?: return@mapNotNull null
        SChapter.create().apply {
            url = href
            name = a.textOrNull() ?: return@mapNotNull null
            date_upload = Instant.tryParse(a.attrOrNull("data-time"))
        }
    }

    // ========================== 3. 图片页 ==========================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val doc = client.get(getChapterUrl(chapter), headers).asJsoup()
        return doc.select("#reader img").mapIndexed { index, img ->
            Page(index, imageUrl = img.attrOrNull("data-src") ?: img.attrOrNull("src"))
        }
    }

    // ====================== 4. 可选：URL 相关 ======================

    /** "在 WebView 中打开"用；默认是 baseUrl + manga.url，往往不是真实路径，所以覆写。 */
    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/chapter/${chapter.url}"

    /**
     * 搜索框里粘贴本站链接时走这条路。
     * 不实现的话，URL 搜索会抛异常 —— 建议实现。
     */
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = url.pathSegments.lastOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return SManga.create().apply {
            this.url = slug
            title = slug
        }
    }
}
