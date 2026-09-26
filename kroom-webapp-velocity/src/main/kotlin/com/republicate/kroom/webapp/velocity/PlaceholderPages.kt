package com.republicate.kroom.webapp.velocity

import com.republicate.kroom.PathTemplate
import io.ktor.server.application.Application
import io.ktor.server.request.path
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

/**
 * Pages whose path carries a placeholder — `pages/club/_code_.html`, or `pages/club/_code_/index.html` —
 * mounted as the routes they describe: `/club/{code}`. The bound values land in the page's context, which
 * is where the blocks it includes read them from too, so a page and its content agree on what `$code` is.
 *
 * Discovered at mount time: the classpath offers no listing to walk per request, and a placeholder
 * directory cannot be probed by name the way [servePage] probes a concrete one. A literal segment still
 * wins over a placeholder — ktor's own route scoring, not a rule reinvented here.
 */
fun Route.placeholderPages(prefix: String = "pages", extension: String = "html") {
    val velocity = application.velocity
    for (template in catalog(velocity, prefix, extension)) {
        val path = PathTemplate(template)
        if (path.isConcrete) continue
        get(route(path.pattern, prefix, extension)) {
            // concrete beats placeholder: `/city/lyon` takes `pages/city/lyon.html` when one exists. Asked
            // here rather than left to ktor, which scores a param route ABOVE the tailcard [pages] mounts.
            if (call.servePage(call.request.path().trimStart('/'), prefix, extension)) return@get
            // the template keeps its placeholders — they are its identity, and what its blocks expand from
            call.respondVelocity(template, call.parameters.names().associateWith { call.parameters[it]!! })
        }
    }
}

/**
 * The page a URL resolves to, and the values its path binds: `/club/13Ma` → `pages/club/_code_.html` with
 * `code = 13Ma`. A concrete template wins, as it does when serving. For whoever needs to render a page
 * outside its own route — an editor previewing a block in the page it belongs to.
 */
fun Application.resolvePage(
    url: String,
    prefix: String = "pages",
    extension: String = "html"
): Pair<String, Map<String, String>>? {
    val path = url.trim('/').substringBefore('?')
    if (!velocity.routable(path.split('/'))) return null
    val concrete = "${prefix.trimEnd('/')}/$path.${extension.trimStart('.')}"
    if (velocity.engine.resourceExists(concrete)) return concrete to emptyMap()
    for (template in catalog(velocity, prefix, extension)) {
        val values = PathTemplate(template).match(concrete)
            ?: PathTemplate(template).match("${prefix.trimEnd('/')}/$path/index.${extension.trimStart('.')}")
        if (values != null) return template to values
    }
    return null
}

/**
 * Every page template under [prefix], with the route it is served at: `pages/login.html` → `/login`,
 * `pages/club/_code_.html` → `/club/{code}`. The same scan [placeholderPages] mounts from — for whoever lists
 * a site's pages (an admin menu, a sitemap).
 */
fun Application.pageCatalog(prefix: String = "pages", extension: String = "html"): Map<String, String> =
    catalog(velocity, prefix, extension).sorted().associateWith { route(PathTemplate(it).pattern, prefix, extension).ifEmpty { "/" } }

/** Scanned once per (plugin, prefix): a jar's entries do not change under a running server. */
private val catalogs = ConcurrentHashMap<String, List<String>>()

private fun catalog(velocity: VelocityPlugin, prefix: String, extension: String): List<String> =
    catalogs.computeIfAbsent("${System.identityHashCode(velocity)}/$prefix.$extension") {
        // what [servePage] would serve: partials (`header.inc.html`, `inc/…`) sit beside pages, and are none
        templateCatalog(velocity.templatePath, velocity.devDir, prefix, extension).filter {
            velocity.routable(it.removePrefix("${prefix.trimEnd('/')}/").removeSuffix(".${extension.trimStart('.')}").split('/'))
        }
    }

/** `pages/club/_code_/index.html` → `/club/{code}`; `pages/club/_code_.html` → the same. */
private fun route(pattern: String, prefix: String, extension: String): String =
    "/" + pattern.removePrefix("${prefix.trimEnd('/')}/").removeSuffix(".$extension").removeSuffix("/index")

/**
 * The template names under [prefix], spelled as the engine's loaders resolve them — read from the same
 * places those loaders read: the classpath under [root], and the dev directory when one is configured.
 */
internal fun templateCatalog(root: String?, devDir: File?, prefix: String, extension: String): List<String> {
    val relative = prefix.trim('/')
    val resourceDir = listOfNotNull(root?.trim('/')?.takeIf { it.isNotEmpty() }, relative).joinToString("/")
    val names = LinkedHashSet<String>()
    val suffix = ".${extension.trimStart('.')}"

    devDir?.resolve(relative)?.let { dir -> collectFiles(dir, relative, suffix, names) }

    val loader = Thread.currentThread().contextClassLoader ?: PathTemplate::class.java.classLoader
    for (url in loader.getResources(resourceDir)) when (url.protocol) {
        "file" -> collectFiles(File(URLDecoder.decode(url.path, "UTF-8")), relative, suffix, names)
        "jar" -> {
            val jar = URLDecoder.decode(url.path.substringAfter("file:").substringBefore("!"), "UTF-8")
            JarFile(jar).use { file ->
                file.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("$resourceDir/") && it.name.endsWith(suffix) }
                    .forEach { names.add("$relative/${it.name.removePrefix("$resourceDir/")}") }
            }
        }
    }
    return names.toList()
}

private fun collectFiles(dir: File, relative: String, suffix: String, into: MutableSet<String>) {
    if (!dir.isDirectory) return
    dir.walkTopDown().filter { it.isFile && it.name.endsWith(suffix) }.forEach {
        into.add("$relative/${it.relativeTo(dir).invariantSeparatorsPath}")
    }
}

private val File.invariantSeparatorsPath: String get() = path.replace(File.separatorChar, '/')
