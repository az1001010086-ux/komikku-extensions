package eu.kanade.tachiyomi.extension.zh.hanmanwu

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException

/**
 * 韩漫屋（hmanwu.com）。
 *
 * 站点是 **Vue3 + Vite 纯客户端 SPA**：首页 HTML 只是一个 5.4 KB 的空壳，
 * 数据全部走 **JSON REST API**（路径前缀 `/api`）⇒ 直接拼 query 参数即可，
 * 不需要任何 HTML/SSR 解析。
 *
 * 详细勘察见 `hmanwu-站点勘察报告-2026-09-25.md`。要点：
 *  - 列表/搜索/筛选：`/api/comics?page&page_size&q&category&tag&sort&crawl_status`
 *  - 详情+章节：`/api/comics/{id}/detail`（一次全拿）
 *  - 章节图片：`/api/chapters/{id}/read`
 *  - 封面/图片：R2 图床，**无防盗链**（裸请求即 200）
 *
 * ⚠️⚠️ 本扩展的**唯一硬约束**：章节图片匿名**每天只能读 2 次**（按 IP 计，重复读同章也扣）。
 *   登录（`/api/auth/login`）后无限制。本版本**先交付匿名版**，
 *   未登录时读图超限会给出可读错误提示，引导用户去插件设置里登录（登录支持留待下一版）。
 *
 * ⚠️ HTTP 状态码**恒为 200**，业务状态在 body 的 `code` 字段（200 成功 / 403 额度耗尽 / 400 不存在）
 *   ⇒ 必须显式判 `code`，否则失败会静默变成"空图片列表"。
 */
@Source
class Hanmanwu(
    override val name: String,
    override val lang: String,
    override val id: Long,
    override val baseUrl: String,
) : KeiSource() {

    // ============================ 1. 列表页 ============================

    /**
     * 人气榜（对应站点侧边栏「人气小榜」，`sort=views`）。
     */
    override suspend fun getPopularManga(page: Int): MangasPage = fetchList(page, query = null, sort = "views", filters = FilterList())

    /**
     * 最新上架（`sort=id`，即按 id 降序）。
     */
    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchList(page, query = null, sort = "id", filters = FilterList())

    /**
     * 搜索 / 筛选。
     *
     * ⚠️ 站点排序参数名是 **`sort`**（不是 `order`），取值 `id` / `views` / `chapters`
     *   （实测反查自 JS bundle：UI 上「最新 / 人气 / 章节最多」）。
     */
    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val sort = filters.filterIsInstance<SortFilter>()
            .firstOrNull()?.toUriPart() ?: "id"
        return fetchList(page, query.ifBlank { null }, sort, filters)
    }

    /**
     * 列表接口统一入口。
     *
     * 响应结构：
     * ```json
     * {"code":200,"message":"success","data":{
     *   "page":1,"page_size":30,"total":4189,
     *   "list":[{"id":6355,"title":"…","author":"…","category":"短篇",
     *            "tags":["巨乳",…],"status":1,"chapter_count":1,
     *            "cover_url":"https://pub-….r2.dev/6355/cover.jpg",
     *            "crawl_status":1,"view_count":2}]}}
     * ```
     */
    private suspend fun fetchList(
        page: Int,
        query: String?,
        sort: String,
        filters: FilterList,
    ): MangasPage {
        val builder = "$baseUrl/api/comics".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", PAGE_SIZE.toString())
            .addQueryParameter("sort", sort)

        // ★ 只在非空时追加：站点对空串参数与缺省参数处理一致，但显式省略更干净。
        query?.takeIf { it.isNotBlank() }
            ?.let { builder.addQueryParameter("q", it) }

        filters.filterIsInstance<CategoryInput>().firstOrNull()
            ?.state?.takeIf { it.isNotBlank() }
            ?.let { builder.addQueryParameter("category", it) }

        filters.filterIsInstance<TagInput>().firstOrNull()
            ?.state?.takeIf { it.isNotBlank() }
            ?.let { builder.addQueryParameter("tag", it) }

        val dto = client.get(builder.build().toString(), headers).parseAs<ListResponse<ListPage>>()
        dto.throwOnError()
        val data = dto.requireData()

        return MangasPage(
            mangas = data.list.map { it.toSManga() },
            hasNextPage = data.page * data.pageSize < data.total,
        )
    }

    // ====================== 2. 详情 + 章节列表 ======================

    /**
     * 详情与章节同一个接口返回 ⇒ 一次请求全拿到，不必按 flag 拆成两次。
     *
     * 响应：
     * ```json
     * {"code":200,"data":{
     *   "comic":{…同列表条目…,"description":"…"},
     *   "chapters":[{"id":43877,"title":"第1話","comic_id":6355,
     *                "chapter_num":1,"page_count":44,"is_free":1,
     *                "created_at":"2026-09-18T13:15:09Z","crawl_status":1}]}}
     * ```
     */
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val dto = client.get("$baseUrl/api/comics/${manga.url}/detail", headers)
            .parseAs<ListResponse<DetailData>>()
        dto.throwOnError()
        val data = dto.requireData()

        if (fetchDetails) {
            data.comic.applyTo(manga)
        }

        return SMangaUpdate(
            manga = manga,
            chapters = if (fetchChapters) data.chapters.map { it.toSChapter(manga.url) } else chapters,
        )
    }

    // ========================== 3. 图片页 ==========================

    /**
     * 章节图片。
     *
     * 响应：
     * ```json
     * {"code":200,"data":{
     *   "images":[{"id":2443601,"chapter_id":43877,"comic_id":6355,
     *              "page_num":1,
     *              "image_url":"https://pub-….r2.dev/6355/43877/2443601.jpg"}, …]}}
     * ```
     *
     * ⚠️ 三种非成功情形必须区分开（否则用户看到的只是"空章节"）：
     *  - `code == 403` ⇒ 匿名额度耗尽 ⇒ 提示去插件设置登录（**可读文案，不是解析失败**）
     *  - `code == 400` ⇒ 章节不存在
     *  - `code == 200` 但 `images` 为空 ⇒ 该章尚未爬取（`crawl_status == 0`）
     */
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapterIdOf(chapter)

        val dto = client.get("$baseUrl/api/chapters/$chapterId/read", headers)
            .parseAs<ListResponse<ReadData>>()

        when (dto.code) {
            403 -> throw IOException(MSG_NEED_LOGIN)
            400 -> throw IOException("章节不存在（可能已被站点下架）")

            else -> dto.throwOnError()
        }

        val images = dto.requireData().images
        if (images.isEmpty()) {
            throw IOException(MSG_NOT_CRAWLED)
        }

        return images
            .sortedBy { it.pageNum }
            .mapIndexed { i, img -> Page(i, imageUrl = img.imageUrl) }
    }

    // ====================== 4. URL 组装 / 解析 ======================

    /**
     * `manga.url` = comicId（纯数字）。
     */
    private fun mangaUrl(manga: SManga): String = "$baseUrl/comic/${manga.url}"

    /**
     * ⚠️ 本站的章节页没有独立路由（阅读是 `/comic/{id}` 内的弹层），
     *   所以 `chapter.url` 必须**同时携带 comicId 与 chapterId**，格式：
     *   ```
     *   "{comicId}/{chapterId}"
     *   ```
     *   （`SChapter` 自身不携带 manga 信息，若不这样存就无法还原 URL。）
     */
    private fun chapterIdOf(chapter: SChapter): String = chapter.url.substringAfterLast('/')

    override fun getMangaUrl(manga: SManga): String = mangaUrl(manga)

    override fun getChapterUrl(chapter: SChapter): String {
        val comicId = chapter.url.substringBefore('/')
        val chapterId = chapterIdOf(chapter)
        return "$baseUrl/comic/$comicId?chapter=$chapterId"
    }

    /**
     * 支持在搜索框粘贴本站链接：
     *  - `https://hmanwu.com/comic/6355`            → comicId = 6355
     *  - `https://hmanwu.com/comic/6355?chapter=…`  → comicId = 6355
     */
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val comicId = COMIC_ID.find(url.toString())?.groupValues?.get(1) ?: return null
        return SManga.create().apply {
            this.url = comicId
            title = comicId
        }
    }

    // ============================ 5. 筛选器 ============================

    /**
     * ⚠️ `KeiSource.getFilterList()` 是 **final**（框架内部用它做筛选数据缓存），
     *   子类只能覆写 `getFilterList(data: JsonElement?)`（KeiSource.kt:249）。
     */
    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("提示：搜索框留空时，下方筛选同样生效"),
        SortFilter(),
        CategoryInput(),
        TagInput(),
        Filter.Separator(),
        Filter.Header("章节图片需登录站点账号（登录支持开发中）"),
        Filter.Header("未登录时每天仅可免费阅读 2 次"),
    )

    private class SortFilter :
        Filter.Select<String>(
            "排序",
            arrayOf("最新上架", "人气热门", "章节最多"),
        ) {
        fun toUriPart(): String = when (state) {
            1 -> "views"
            2 -> "chapters"
            else -> "id"
        }
    }

    /**
     * 分类。站点共 **57 个**顶层分类（简繁并存，如「全彩/全彩」「长篇/長篇」），
     * 做下拉体验很差 ⇒ 改用文本输入，用户填任意分类名即可
     * （实测 `category=全彩` → total 4189→1201）。
     */
    private class CategoryInput : Filter.Text("分类（如 全彩 / WEBTOON）")

    private class TagInput : Filter.Text("标签（如 WEBTOON）")

    // ============================ 工具 ============================

    /**
     * 站点统一响应信封。
     *
     * ⚠️ `data` **必须可空**：错误响应（如 `{"code":403,"message":"…"}`）**完全没有 `data` 字段**，
     *   若声明为非空会直接反序列化失败，用户看到的是"NPE / 解析错误"，
     *   而不是我们要给出的可读提示。
     */
    @Serializable
    private data class ListResponse<T>(
        val code: Int = 0,
        val message: String = "",
        val data: T? = null,
    )

    /** ★ HTTP 恒 200 ⇒ 业务码在 body，必须显式判断。 */
    private fun ListResponse<*>.throwOnError() {
        if (code != 200) {
            throw IOException(message.ifBlank { "接口返回错误码 $code" })
        }
    }

    /** 取 `data`（`code == 200` 时理论上必存在，缺失则视为异常数据）。 */
    private fun <T> ListResponse<T>.requireData(): T = data ?: throw IOException("接口返回成功但缺少 data 字段")

    @Serializable
    private data class ListPage(
        val page: Int = 1,
        @SerialName("page_size")
        val pageSize: Int = 30,
        val total: Int = 0,
        val list: List<ComicDto> = emptyList(),
    )

    @Serializable
    private data class DetailData(
        val comic: ComicDto = ComicDto(),
        val chapters: List<ChapterDto> = emptyList(),
    )

    @Serializable
    private data class ReadData(
        val images: List<ImageDto> = emptyList(),
    )

    @Serializable
    private data class ComicDto(
        val id: Long = 0L,
        val title: String = "",
        val author: String = "",
        val description: String = "",
        val category: String = "",
        val tags: List<String> = emptyList(),
        val status: Int = 0,
        @SerialName("chapter_count")
        val chapterCount: Int = 0,
        @SerialName("cover_url")
        val coverUrl: String = "",
        @SerialName("crawl_status")
        val crawlStatus: Int = 0,
        @SerialName("view_count")
        val viewCount: Int = 0,
    ) {
        fun toSManga(): SManga = SManga.create().apply {
            url = id.toString()
            title = this@ComicDto.title
            thumbnail_url = coverUrl.takeIf { it.isNotBlank() }
        }

        /** 详情页的完整信息回填（比列表条目多了 description）。 */
        fun applyTo(manga: SManga) {
            manga.title = title.ifBlank { manga.title }
            manga.author = author.takeIf { it.isNotBlank() }
            manga.description = description.takeIf { it.isNotBlank() }
            manga.thumbnail_url = coverUrl.takeIf { it.isNotBlank() } ?: manga.thumbnail_url

            // 分类与标签合并展示（站点把两者分开存，App 只有一个 genre 字段）
            manga.genre = (category.split(",").map { it.trim() } + tags)
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
                .takeIf { it.isNotBlank() }

            // title 里带 「[完結]」等后缀，status 另给：1=已完结 / 0=连载中
            manga.status = if (status == 1) SManga.COMPLETED else SManga.ONGOING
        }
    }

    @Serializable
    private data class ChapterDto(
        val id: Long = 0L,
        val title: String = "",
        @SerialName("comic_id")
        val comicId: Long = 0L,
        @SerialName("chapter_num")
        val chapterNum: Int = 0,
        @SerialName("page_count")
        val pageCount: Int = 0,
        @SerialName("is_free")
        val isFree: Int = 0,
        @SerialName("created_at")
        val createdAt: String = "",
        @SerialName("crawl_status")
        val crawlStatus: Int = 0,
    ) {
        fun toSChapter(comicId: String): SChapter = SChapter.create().apply {
            // ★ 必须自带 comicId（见 chapterIdOf 的说明）
            url = "$comicId/$id"
            name = title.ifBlank { "第${chapterNum}话" }
            chapter_number = chapterNum.toFloat()
        }
    }

    @Serializable
    private data class ImageDto(
        val id: Long = 0L,
        @SerialName("page_num")
        val pageNum: Int = 0,
        @SerialName("image_url")
        val imageUrl: String = "",
    )

    private companion object {
        /** 每页条数（站点默认 30，实测 20/3 等任意值均生效）。 */
        const val PAGE_SIZE = 30

        const val MSG_NEED_LOGIN =
            "今日免费阅读次数已用完（未登录每天仅 2 次）。请在「扩展设置」里登录韩漫屋账号后重试。"

        const val MSG_NOT_CRAWLED =
            "该章节在站点侧尚无图片（未爬取）。请换一章，或稍后再试。"

        val COMIC_ID = Regex("""/comic/(\d+)""")
    }
}
