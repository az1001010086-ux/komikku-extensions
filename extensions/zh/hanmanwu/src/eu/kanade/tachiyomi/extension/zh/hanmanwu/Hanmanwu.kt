package eu.kanade.tachiyomi.extension.zh.hanmanwu

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
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
 *  - 登录：`/api/auth/login`（用户名 + 密码换 JWT）
 *  - 封面/图片：R2 图床，**无防盗链**（裸请求即 200）
 *
 * ⚠️⚠️ 本扩展的**唯一硬约束**：章节图片匿名**每天只能读 2 次**（按 IP 计，重复读同章也扣）。
 *   登录后无限制 ⇒ 本版实现了**自动登录**：在「扩展设置」里填一次账号密码，
 *   之后取图时会自动换取 token（30 天有效）并缓存到本机，全程无需再操作。
 *
 * ⚠️ HTTP 状态码**恒为 200**，业务状态在 body 的 `code` 字段（200 成功 / 403 额度耗尽 / 400 不存在）
 *   ⇒ 必须显式判 `code`，否则失败会静默变成"空图片列表"。
 *
 * ⚠️ 但有两个**例外**：额度耗尽时站点**会**返回 real HTTP 403，登录失败返回 real HTTP 401
 *   ⇒ 所有需要读 body 的请求都必须 `ensureSuccess = false`。
 */
@Source
class Hanmanwu(
    override val name: String,
    override val lang: String,
    override val id: Long,
    override val baseUrl: String,
) : KeiSource(),
    ConfigurableSource {

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
     * ⚠️ 非成功情形必须区分开（否则用户看到的只是"空章节"）：
     *  - `code == 403` ⇒ 匿名额度耗尽，或所带 token 已失效被降级为匿名
     *  - `code == 400` ⇒ 章节不存在
     *  - `code == 200` 但 `images` 为空 ⇒ 该章尚未爬取（`crawl_status == 0`）
     */
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapterIdOf(chapter)
        val url = "$baseUrl/api/chapters/$chapterId/read"

        val seenTokenVersion = tokenVersion
        var dto = fetchRead(url)

        // ★ 403 有两种成因，且响应体**完全一样**、无法区分：
        //   (a) 压根没登录（或没配账号）⇒ 匿名额度真的用完了；
        //   (b) 带了 token，但 token 已失效 ⇒ 服务端**降级为匿名**，于是撞上额度闸。
        //   ⇒ 只要配置了账号，就强制刷新一次 token 再重试（幂等，最多多一次登录请求）。
        if (dto.code == 403) {
            retryAfterRelogin(url, seenTokenVersion)?.let { dto = it }
        }

        when (dto.code) {
            403 -> throw IOException(needLoginMessage())
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

    /**
     * 读图请求。
     *
     * ★★ `ensureSuccess = false` 是**必须的**：站点在额度耗尽时会返回 **real HTTP 403**，
     *   若用默认的 `ensureSuccess = true`，`awaitSuccess()` 会先抛 `HttpException`，
     *   我们就永远读不到 body 里的 `code`/`message`，那条可读的中文提示也就永远显示不出来。
     */
    private suspend fun fetchRead(url: String): ListResponse<ReadData> = client.get(url, authHeaders(), ensureSuccess = false).parseAs()

    /**
     * 403 兜底：刷新 token 后用新 token 重试一次。
     *
     * @param seenTokenVersion 调用方发起请求时观察到的 token 版本号。
     * @return 重试后的响应；`null` 表示不重试（未配账号 / 处于冷却期）。
     */
    private suspend fun retryAfterRelogin(url: String, seenTokenVersion: Long): ListResponse<ReadData>? {
        if (!hasCredentials()) return null

        val shouldRetry = loginMutex.withLock {
            // 等锁期间别人已经把 token 刷新成可用的了 ⇒ 直接用新 token 重试，不必再登录一次。
            // ⚠️ 必须同时要求「有可用 token」：`clearToken()` 也会改版本号，
            //   若只看版本号，token 被清空的瞬间会误判成"已刷新"。
            if (tokenVersion != seenTokenVersion && currentToken() != null) return@withLock true

            // 冷却期内不重复登录：避免批量下载时对每一章都发一次登录请求。
            val now = System.currentTimeMillis()
            if (now - lastForceReloginAt < RELOGIN_COOLDOWN_MS) return@withLock false
            lastForceReloginAt = now

            clearToken()
            login() != null
        }

        return if (shouldRetry) fetchRead(url) else null
    }

    // ====================== 4. 登录 / token 管理 ======================

    /**
     * 偏好页。**这是本扩展唯一的用户可见配置入口**（宿主 `SourcePreferencesScreen`
     * 仅在 `source is ConfigurableSource` 时调用本方法）。
     *
     * ⚠️ 只能使用 `extensions-lib` 暴露的偏好控件子集（`EditTextPreference` /
     *   `ListPreference` / `CheckBoxPreference`…），**纯文本 `Preference` 不可用**
     *   （stub 里没有 `(Context)` 构造器）⇒ 说明文字放在 `dialogMessage` / `summary` 里。
     *
     * ⚠️ 宿主会把偏好页的 dataStore 指到 `source.sourcePreferences()`
     *   = `getSharedPreferences("source_$id", MODE_PRIVATE)`，
     *   与 [getPreferencesLazy] 拿到的**是同一个文件** ⇒ 两边读写完全一致。
     */
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_USERNAME
            title = PREF_USERNAME_TITLE
            dialogTitle = PREF_USERNAME_TITLE
            dialogMessage = PREF_ACCOUNT_HINT
            summary = PREF_ACCOUNT_SUMMARY
            setDefaultValue("")

            setOnBindEditTextListener { it.setHorizontallyScrolling(true) }

            // 账号一改，旧 token 立即作废（下次取图会自动用新账号登录）。
            setOnPreferenceChangeListener { _, _ ->
                clearToken()
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_PASSWORD
            title = PREF_PASSWORD_TITLE
            dialogTitle = PREF_PASSWORD_TITLE
            dialogMessage = PREF_PASSWORD_HINT
            summary = PREF_PASSWORD_SUMMARY
            setDefaultValue("")

            setOnPreferenceChangeListener { _, _ ->
                clearToken()
                true
            }
        }.also(screen::addPreference)
    }

    private val preferences: SharedPreferences by getPreferencesLazy()

    /** 每次成功写入 token 都自增，用于识别「token 是否已被别人刷新过」。 */
    @Volatile
    private var tokenVersion = 0L

    /** 上次「因 403 而强制重登」的时刻，用于冷却节流。 */
    @Volatile
    private var lastForceReloginAt = 0L

    /** 最近一次登录失败的原因（用于把可读文案带回给用户）。 */
    @Volatile
    private var lastLoginError: String? = null

    /** 保证同一时刻只有一次登录在飞。 */
    private val loginMutex = Mutex()

    private fun hasCredentials(): Boolean {
        val prefs = preferences
        return !prefs.getString(PREF_USERNAME, null).isNullOrBlank() &&
            !prefs.getString(PREF_PASSWORD, null).isNullOrBlank()
    }

    /** 取缓存中仍然有效的 token（本地按 29 天过期，比服务端的 30 天保守 1 天）。 */
    private fun currentToken(): String? {
        val prefs = preferences
        val token = prefs.getString(PREF_TOKEN, null)
        if (token.isNullOrBlank()) return null
        if (System.currentTimeMillis() >= prefs.getLong(PREF_TOKEN_EXP, 0L)) return null
        return token
    }

    /** 给请求头附上 `Authorization: Bearer <token>`；无可用 token 时原样返回。 */
    private suspend fun authHeaders(): Headers {
        val token = ensureToken() ?: return headers
        return headers.newBuilder().set("Authorization", "Bearer $token").build()
    }

    /** 确保有可用 token：优先用缓存，其次（有账号时）自动登录。 */
    private suspend fun ensureToken(): String? {
        currentToken()?.let { return it }
        if (!hasCredentials()) return null

        return loginMutex.withLock {
            // 双检：等锁期间可能已被其他协程登录成功。
            currentToken() ?: login()
        }
    }

    /**
     * 用偏好里的账号密码换 token，并缓存到本机。
     *
     * 调用方必须已持有 [loginMutex]。
     *
     * ⚠️ 登录失败返回 **real HTTP 401**（不是 body 里的 code）⇒ 必须 `ensureSuccess = false`，
     *   否则读不到 `{"code":401,"message":"用户名或密码错误"}`。
     */
    private suspend fun login(): String? {
        val prefs = preferences
        val username = prefs.getString(PREF_USERNAME, null)?.trim().orEmpty()
        val password = prefs.getString(PREF_PASSWORD, null).orEmpty()
        if (username.isEmpty() || password.isEmpty()) return null

        val dto = try {
            client.post(
                "$baseUrl/api/auth/login",
                headers,
                LoginRequest(username, password).toJsonRequestBody(),
                ensureSuccess = false,
            ).parseAs<ListResponse<LoginData>>()
        } catch (e: Exception) {
            lastLoginError = e.message ?: e.javaClass.simpleName
            return null
        }

        val token = dto.data?.token
        if (dto.code != 200 || token.isNullOrBlank()) {
            lastLoginError = dto.message.ifBlank { "登录失败（错误码 ${dto.code}）" }
            clearToken()
            return null
        }

        lastLoginError = null
        prefs.edit()
            .putString(PREF_TOKEN, token)
            .putLong(PREF_TOKEN_EXP, System.currentTimeMillis() + TOKEN_TTL_MS)
            .apply()
        tokenVersion++

        return token
    }

    private fun clearToken() {
        preferences.edit()
            .remove(PREF_TOKEN)
            .remove(PREF_TOKEN_EXP)
            .apply()
        tokenVersion++
    }

    /** 403 时的提示文案；若刚刚登录失败过，把原因一并带上（否则用户会一头雾水）。 */
    private fun needLoginMessage(): String {
        val err = lastLoginError
        return if (err.isNullOrBlank()) {
            MSG_NEED_LOGIN
        } else {
            "$MSG_NEED_LOGIN\n（自动登录失败：$err）"
        }
    }

    // ====================== 5. URL 组装 / 解析 ======================

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

    // ============================ 6. 筛选器 ============================

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
        Filter.Header("章节图片需登录站点账号：在「扩展设置」填一次账号密码即可"),
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
    private data class LoginRequest(
        val username: String,
        val password: String,
    )

    /** 登录成功时 `data` 里还带 `user` 对象，本扩展用不到（`ignoreUnknownKeys` 会忽略）。 */
    @Serializable
    private data class LoginData(
        val token: String = "",
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

        // ---- 偏好键 ----
        const val PREF_USERNAME = "hanmanwu_username"
        const val PREF_PASSWORD = "hanmanwu_password"

        /** token 与过期时刻只写不展示（不给它们建偏好项）。 */
        const val PREF_TOKEN = "hanmanwu_token"
        const val PREF_TOKEN_EXP = "hanmanwu_token_exp"

        /** 实测服务端 JWT 有效期 30 天（`exp - iat = 2592000`），本地保守取 29 天。 */
        const val TOKEN_TTL_MS = 29L * 24 * 60 * 60 * 1000

        /** 403 触发强制重登的冷却窗口，避免批量下载时逐章重复登录。 */
        const val RELOGIN_COOLDOWN_MS = 60_000L

        const val PREF_USERNAME_TITLE = "韩漫屋账号"
        const val PREF_ACCOUNT_SUMMARY = "填写后自动登录（无需再操作）"
        const val PREF_ACCOUNT_HINT =
            "章节图片需要登录后才能不限次数阅读。\n\n" +
                "填好「账号 + 密码」即可，取图时会自动登录并缓存令牌（约 30 天有效），" +
                "期间无需重复输入。\n\n" +
                "还没有账号？直接去 hmanwu.com 注册：只需用户名 + 密码，无需邮箱或验证码。"

        const val PREF_PASSWORD_TITLE = "韩漫屋密码"
        const val PREF_PASSWORD_SUMMARY = "仅保存在本机，用于自动登录"
        const val PREF_PASSWORD_HINT =
            "密码只写入本机扩展设置，用于向 hmanwu.com 换取访问令牌，不会上传到任何第三方。\n\n" +
                "修改账号或密码后，已缓存的令牌会立即作废，下次取图时自动用新凭据重新登录。"

        const val MSG_NEED_LOGIN =
            "今日免费阅读次数已用完（未登录每天仅 2 次）。请在「扩展设置 → 韩漫屋」里填写账号和密码，之后会自动登录。"

        const val MSG_NOT_CRAWLED =
            "该章节在站点侧尚无图片（未爬取）。请换一章，或稍后再试。"

        val COMIC_ID = Regex("""/comic/(\d+)""")
    }
}
