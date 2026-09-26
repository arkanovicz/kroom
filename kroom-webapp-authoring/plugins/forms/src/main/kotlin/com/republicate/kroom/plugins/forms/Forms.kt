package com.republicate.kroom.plugins.forms

import com.republicate.kroom.webapp.authoring.AdminEntry
import com.republicate.kroom.webapp.authoring.Plugin
import com.republicate.kroom.webapp.authoring.Roles
import com.republicate.kroom.webapp.authoring.Setting
import com.republicate.kroom.webapp.authoring.Setting.Type.NUMBER
import com.republicate.kroom.webapp.authoring.Site
import com.republicate.kroom.webapp.authoring.htmlEscape
import com.republicate.kroom.webapp.core.respondError
import com.republicate.kroom.webapp.core.respondJson
import com.republicate.kroom.webapp.core.respondSuccess
import com.republicate.kroom.webapp.session.userSession
import com.republicate.kson.Json
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.net.URI
import kotlin.time.Duration.Companion.days

/**
 * A contact form an author drops into any block — `$forms.contact()`, or `$forms.contact("membership")` to
 * sort the messages — the way a WordPress shortcode is typed into a post. Visitors post it anonymously; the
 * messages are records of this plugin, listed in the admin bar for whoever holds `forms.read` (editors, by
 * default), and forgotten after the retention the admin sets.
 *
 * Spam is met with a honeypot: a field people never see and robots fill, answered as a success and dropped.
 * Each message is also mailed to `notify`, when set and the site has a mailer ([Site.mailer], the mail plugin):
 * off the request, a failed send only logged — the message is kept either way.
 *
 * [strings] are the form's words, overridable as the editor's are.
 */
class Forms(strings: Map<String, String> = emptyMap()) : Plugin {
    override val id = "forms"
    override val name = "Forms"
    override val description = "A contact form blocks can call; its messages kept, listed and expired"
    override val settings = listOf(
        Setting("thanks", "Thank-you message", default = "Thank you, your message was sent."),
        Setting("notify", "Tell", help = "An address each message is mailed to — needs a mailer (the mail plugin)"),
        Setting("retentionDays", "Keep messages for (days)", default = "365", type = NUMBER)
    )

    companion object {
        const val READ = "forms.read"
        private const val MAX = 5000
    }

    private val words = mapOf(
        "name" to "Name", "email" to "Email", "message" to "Message", "send" to "Send",
        "error" to "Your message could not be sent: please fill every field."
    ) + strings

    /** What a block is handed as `$forms`. */
    inner class Tool internal constructor(private val site: Site) {
        fun contact(): String = contact("contact")

        fun contact(topic: String): String {
            val thanks = site.settings(this@Forms)["thanks"].orEmpty()
            // one line: a blank line would end markdown's html block halfway through the form
            return """<form class="kroom-form" method="post" action="/forms/contact" data-thanks="${htmlEscape(thanks)}" data-error="${htmlEscape(words["error"]!!)}">""" +
                """<input type="hidden" name="topic" value="${htmlEscape(topic)}">""" +
                """<label>${htmlEscape(words["name"]!!)} <input name="name" required maxlength="200"></label>""" +
                """<label>${htmlEscape(words["email"]!!)} <input name="email" type="email" required maxlength="200"></label>""" +
                """<label>${htmlEscape(words["message"]!!)} <textarea name="message" required maxlength="$MAX"></textarea></label>""" +
                """<label aria-hidden="true" style="position:absolute;left:-10000px">Website <input name="website" tabindex="-1" autocomplete="off"></label>""" +
                """<button type="submit">${htmlEscape(words["send"]!!)}</button></form>"""
        }
    }

    override fun install(site: Site) {
        site.grant(Roles.EDITOR, READ)
        site.tool("forms", Tool(site), blocks = true)
        site.admin(AdminEntry("forms", "Messages", icon = "M4 6h16v12H4zM4 7l8 6 8-6", table = "/api/forms/messages", permission = READ))
        site.every(1.days) { purge(site, System.currentTimeMillis()) }

        // sent with fetch, the form stays on its page and says thanks; without scripts, the post comes back to it
        site.foot { SCRIPT }

        site.routes {
            post("/forms/contact") {
                val form = call.receiveParameters()
                val json = call.request.accept()?.contains("application/json") == true
                val back = sameSite(call.request.headers[HttpHeaders.Referrer], call.request.host())
                if (form["website"].isNullOrEmpty()) {
                    val fields = listOf("name", "email", "message").associateWith { form[it]?.trim().orEmpty() }
                    if (fields.values.any { it.isEmpty() || it.length > MAX } || '@' !in fields["email"]!!)
                        return@post respondError(words["error"]!!, code = "invalid")
                    val topic = form["topic"]?.take(100) ?: "contact"
                    site.records(this@Forms, "messages").add(Json.MutableObject().apply {
                        set("time", System.currentTimeMillis())
                        set("topic", topic)
                        fields.forEach { (key, value) -> set(key, value) }
                        set("page", back)
                    })
                    notify(site, topic, fields, back)
                }
                if (json) respondSuccess() else call.respondRedirect(back)
            }

            get("/api/forms/messages") {
                if (!site.can(call.userSession, READ))
                    return@get respondError("not allowed to read messages", HttpStatusCode.Forbidden, "forbidden")
                val columns = listOf("time", "topic", "name", "email", "message", "page")
                respondJson {
                    set("columns", Json.MutableArray().apply { columns.forEach { push(it) } })
                    set("rows", Json.MutableArray().apply {
                        site.records(this@Forms, "messages").list(200).values.forEach { message ->
                            push(Json.MutableArray().apply {
                                columns.forEach { column ->
                                    push(if (column == "time") java.time.Instant.ofEpochMilli(message.getLong("time") ?: 0).toString() else message.getString(column))
                                }
                            })
                        }
                    })
                }
            }
        }
    }

    private fun notify(site: Site, topic: String, fields: Map<String, String>, page: String) {
        val to = site.settings(this)["notify"]?.trim().orEmpty().takeIf { it.isNotEmpty() } ?: return
        val mailer = site.mailer ?: return logger.warn("forms: notify is set, but the site has no mailer")
        site.application.launch {
            try {
                mailer.send(to, "[$topic] ${fields["name"]}", "${fields["name"]} <${fields["email"]}> wrote, on $page:\n\n${fields["message"]}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("forms: could not mail {} — the message is kept", to, e)
            }
        }
    }

    /** Forget what is older than the retention the admin set; answers how many were dropped. */
    fun purge(site: Site, now: Long): Int {
        val days = site.settings(this)["retentionDays"]?.toLongOrNull() ?: return 0
        val cutoff = now - days * 86_400_000
        val messages = site.records(this, "messages")
        return messages.list(Int.MAX_VALUE).count { (id, message) -> (message.getLong("time") ?: 0) < cutoff && messages.delete(id) }
    }

    /** The referring page, if it is ours — a form never sends anyone elsewhere. */
    private fun sameSite(referrer: String?, host: String): String {
        val uri = referrer?.let { runCatching { URI(it) }.getOrNull() } ?: return "/"
        return if (uri.host == null || uri.host == host) (uri.rawPath ?: "/").ifEmpty { "/" } else "/"
    }
}

private val logger = LoggerFactory.getLogger("kroom.plugins.forms")

private val SCRIPT = """<script>
document.addEventListener('submit', async (e) => {
    const form = e.target.closest('form.kroom-form');
    if (!form) return;
    e.preventDefault();
    const resp = await fetch(form.action, { method: 'POST', body: new URLSearchParams(new FormData(form)), headers: { Accept: 'application/json' } });
    const said = document.createElement('p');
    said.className = resp.ok ? 'kroom-form-thanks' : 'kroom-form-error';
    said.textContent = resp.ok ? form.dataset.thanks : form.dataset.error;
    if (resp.ok) form.replaceWith(said); else form.prepend(said);
});
</script>"""
