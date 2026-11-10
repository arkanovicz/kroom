package com.republicate.kroom.plugins.gallery

import com.republicate.kroom.webapp.authoring.AuthoringAssets
import com.republicate.kroom.webapp.authoring.Plugin
import com.republicate.kroom.webapp.authoring.Roles
import com.republicate.kroom.webapp.authoring.Site
import com.republicate.kroom.webapp.authoring.authoring
import com.republicate.kroom.webapp.authoring.htmlEscape
import com.republicate.kroom.webapp.core.receiveJsonObject
import com.republicate.kroom.webapp.core.respondError
import com.republicate.kroom.webapp.core.respondJson
import com.republicate.kroom.webapp.core.respondSuccess
import com.republicate.kroom.webapp.images.Images
import com.republicate.kroom.webapp.images.images
import com.republicate.kroom.webapp.session.userSession
import com.republicate.kson.Json
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import org.apache.velocity.engine.SafeText

/**
 * Photo galleries. A gallery is a record of this plugin — its pictures in order, each with a caption per
 * language — shown wherever a block calls `$gallery.show("k7f3a")`: a grid of squares, a slideshow on click,
 * edited in place (add, reorder, caption, remove) by whoever holds `gallery.edit`. A page born with the
 * `gallery` layout gets a gallery of its own, its call seeded in its content block. The record's id is the
 * reference, never the page: a gallery follows its block through a rename, and two pages may show one.
 *
 * The pictures are the site's media. Renditions come from [images] when the application hands one over —
 * kroom-webapp-images over the media directory, on a path of its own since `/img` is the static files'
 * (`Images(local = File("data/media"), path = "/pictures", …)`; the plugin mounts its route): the grid's squares
 * at [THUMB], the show at [SLIDE], the original to zoom and download — and are the originals otherwise, heavy
 * but whole. Pictures only; videos are for later. [strings] are the words of the grid and the show, the
 * application's to translate.
 */
class Gallery(private val images: Images? = null, strings: Map<String, String> = emptyMap()) : Plugin {
    override val id = "gallery"
    override val name = "Gallery"
    override val description = "Photo galleries any block can call: a grid, a slideshow, edited in place"

    companion object {
        const val EDIT = "gallery.edit"
        const val THUMB = 300
        const val SLIDE = 900
        private const val GALLERIES = "galleries"
        private const val API = "/api/gallery"
        private const val MAX_CAPTION = 1000
        private val SHOWN = AttributeKey<Boolean>("kroom.gallery.shown")
    }

    private val words = mapOf(
        "add" to "Add photos", "drop" to "or drop or paste them here", "delete" to "Remove from the gallery",
        "confirmDelete" to "Remove this picture from the gallery?", "caption" to "Caption", "close" to "Close",
        "previous" to "Previous", "next" to "Next", "play" to "Play", "pause" to "Pause", "zoom" to "Zoom",
        "download" to "Download", "of" to "{n} / {total}", "uploading" to "uploading {name}…", "failed" to "{name}: {message}"
    ) + strings

    /** One picture of a gallery: a media name, and what each language says under it. */
    private class Item(val media: String, val captions: Map<String, String>)

    private lateinit var site: Site

    override fun install(site: Site) {
        this.site = site
        site.grant(Roles.EDITOR, EDIT)
        site.grant(Roles.AUTHOR, EDIT)
        site.requestTool("gallery", blocks = true) { call -> Tool(site, call) }
        // a page that is a gallery: one made for it, its call where the content goes
        site.seed("gallery") { page -> "\$gallery.show(\"${create(site, page.path)}\")" }
        // the grid's and the show's files, on the pages that showed a gallery, for whoever reads them
        site.foot { call -> if (call.attributes.getOrNull(SHOWN) == true) tags(site, call) else null }

        site.routes {
            images?.let { images(it) }
            route(API) {
                // a gallery for a block to call, empty; `{"page": "/photos"}` notes where it goes
                post {
                    if (!allowed(site)) return@post
                    val asked = runCatching { receiveJsonObject() }.getOrNull()
                    val id = create(site, asked?.getString("page"))
                    respondJson(view(site, call, id, site.records(this@Gallery, GALLERIES).get(id)!!))
                }
                get("/{id}") {
                    val id = call.parameters["id"]!!
                    val record = site.records(this@Gallery, GALLERIES).get(id) ?: return@get call.respond(HttpStatusCode.NotFound)
                    respondJson(view(site, call, id, record))
                }
                // the gallery as the editor arranged it: its pictures in order, each a media name and its captions
                put("/{id}") {
                    if (!allowed(site)) return@put
                    val id = call.parameters["id"]!!
                    val records = site.records(this@Gallery, GALLERIES)
                    val record = records.get(id) ?: return@put call.respond(HttpStatusCode.NotFound)
                    val asked = receiveJsonObject().getArray("items")
                        ?: return@put respondError("items expected", code = "galleryInvalid", args = mapOf("message" to "items expected"))
                    val items = ArrayList<Item>()
                    for (entry in asked) {
                        val json = entry as? Json.Object ?: return@put respondError("an item is not an object", code = "galleryInvalid", args = mapOf("message" to "item"))
                        val media = json.getString("media")?.takeIf { site.storage.media.get(it)?.type?.startsWith("image/") == true }
                            ?: return@put respondError("not a picture of this site: ${json.getString("media")}", code = "galleryMedia", args = mapOf("media" to json.getString("media")))
                        val captions = json.getObject("captions")?.entries.orEmpty()
                            .filter { (lang, _) -> lang in site.languages }
                            .associate { (lang, text) -> lang to text.toString().take(MAX_CAPTION) }
                            .filterValues { it.isNotEmpty() }
                        items += Item(media, captions)
                    }
                    val updated = Json.MutableObject().apply {
                        record.entries.forEach { (k, v) -> set(k, v) }
                        set("items", Json.MutableArray().apply { items.forEach { push(it.toJson()) } })
                        set("updated", System.currentTimeMillis())
                    }
                    records.put(id, updated)
                    respondJson(view(site, call, id, updated))
                }
                delete("/{id}") {
                    if (!allowed(site)) return@delete
                    if (!site.records(this@Gallery, GALLERIES).delete(call.parameters["id"]!!)) return@delete call.respond(HttpStatusCode.NotFound)
                    respondSuccess()
                }
            }
        }
    }

    /** `$gallery` in a page or a block. */
    inner class Tool internal constructor(private val site: Site, private val call: ApplicationCall) {
        /** The gallery [id] as a grid the show opens from — one html block, no blank line, so markdown keeps it whole. */
        fun show(id: String): SafeText {
            val record = site.records(this@Gallery, GALLERIES).get(id)
                ?: return SafeText("""<div class="kroom-gallery kroom-gallery-missing" data-gallery="${htmlEscape(id)}"></div>""" + "\n")
            call.attributes.put(SHOWN, true)
            val lang = site.language(call)
            val edit = site.can(call.userSession, EDIT)
            val authoring = site.application.authoring
            val out = StringBuilder()
            out.append("""<div class="kroom-gallery" data-gallery="${htmlEscape(id)}" data-api="$API" data-media="${authoring.apiPrefix}/media" data-lang="$lang"""")
            if (edit) out.append(""" data-edit="1"""")
            out.append('>')
            for (item in items(record)) {
                val caption = htmlEscape(caption(site, item, lang))
                out.append("\n<figure data-media=\"${htmlEscape(item.media)}\" data-captions=\"${htmlEscape(captionsJson(item))}\">")
                out.append("""<a href="${slide(item.media)}" data-full="${full(item.media)}"><img src="${thumb(item.media)}" alt="$caption" loading="lazy"></a>""")
                out.append("<figcaption>$caption</figcaption></figure>")
            }
            // the closing newline: markdown's html block runs to a blank line, the author's next line stands apart
            out.append("\n</div>\n")
            return SafeText(out)
        }
    }

    // --- the record ----------------------------------------------------------------------------------

    private fun create(site: Site, page: String?): String = site.records(this, GALLERIES).add(Json.MutableObject().apply {
        page?.let { set("page", it) }
        set("items", Json.MutableArray())
        set("updated", System.currentTimeMillis())
    })

    override fun holds(site: Site): String? {
        val galleries = site.records(this, GALLERIES).list(Int.MAX_VALUE).values
        if (galleries.isEmpty()) return null
        val pictures = galleries.sumOf { items(it).size }
        return "${galleries.size} ${if (galleries.size == 1) "gallery" else "galleries"}, $pictures ${if (pictures == 1) "picture" else "pictures"}"
    }

    private fun items(record: Json.Object): List<Item> = record.getArray("items")?.mapNotNull { entry ->
        val json = entry as? Json.Object ?: return@mapNotNull null
        val media = json.getString("media") ?: return@mapNotNull null
        Item(media, json.getObject("captions")?.entries?.associate { (k, v) -> k to v.toString() }.orEmpty())
    }.orEmpty()

    private fun Item.toJson() = Json.MutableObject().apply {
        set("media", media)
        set("captions", Json.MutableObject().apply { captions.forEach { (k, v) -> set(k, v) } })
    }

    private fun captionsJson(item: Item) = Json.MutableObject().apply { item.captions.forEach { (k, v) -> set(k, v) } }.toString()

    /** What [lang] says under the picture, else the site's default language, else the first that says anything. */
    private fun caption(site: Site, item: Item, lang: String): String =
        item.captions[lang] ?: item.captions[site.settings()["lang"]] ?: item.captions.values.firstOrNull() ?: ""

    /** The gallery as the editor's script reads it back: the stored items, each with its caption resolved and its three URLs. */
    private fun view(site: Site, call: ApplicationCall, id: String, record: Json.Object): Json.MutableObject {
        val lang = site.language(call)
        return Json.MutableObject().apply {
            set("id", id)
            record.getString("page")?.let { set("page", it) }
            set("updated", record.getLong("updated"))
            set("items", Json.MutableArray().apply {
                items(record).forEach { item ->
                    push(item.toJson().apply {
                        set("caption", caption(site, item, lang))
                        set("thumb", thumb(item.media)); set("slide", slide(item.media)); set("full", full(item.media))
                    })
                }
            })
        }
    }

    // --- the pictures' URLs: renditions when there is an Images, the originals otherwise --------------------

    // read when a page renders: authoring is installed after the plugins register
    private fun full(media: String) = "${site.application.authoring.mediaPrefix}/$media"
    private fun thumb(media: String) = images?.url(media, THUMB) ?: full(media)
    private fun slide(media: String) = images?.url(media, SLIDE) ?: full(media)

    private fun tags(site: Site, call: ApplicationCall): String {
        val version = AuthoringAssets.VERSION
        return listOfNotNull(
            """<link rel="stylesheet" href="/css/gallery.css?v=$version">""",
            AuthoringAssets().stringTags("kroomGallery", words),
            if (site.can(call.userSession, EDIT)) """<script src="/lib/sortablejs/Sortable.js?v=$version"></script>""" else null,
            """<script src="/js/gallery.js?v=$version"></script>"""
        ).joinToString("\n")
    }

    /** Whether the caller may edit galleries, having answered 401/403 when not. */
    private suspend fun RoutingContext.allowed(site: Site): Boolean {
        val session = call.userSession
        when {
            session == null -> respondError("not authenticated", HttpStatusCode.Unauthorized, "notAuthenticated")
            !site.can(session, EDIT) -> respondError("not allowed: $EDIT", HttpStatusCode.Forbidden, "notAllowed", mapOf("permission" to EDIT))
            else -> return true
        }
        return false
    }
}
