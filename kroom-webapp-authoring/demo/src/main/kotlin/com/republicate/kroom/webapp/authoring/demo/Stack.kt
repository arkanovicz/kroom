package com.republicate.kroom.webapp.authoring.demo

/**
 * What blocks may read: `$stack`, the libraries this site stands on, and the topics its pages are about.
 * Plain read-only data, so the editor's completion has something typed to unfold — `$stack.projects`,
 * `%foreach($p in $topic.projects)`, `$stack.byName.skorm.summary`.
 */
class Stack(val projects: List<Project>, private val topics: List<Topic>) {
    val byName: Map<String, Project> = projects.associateBy { it.name }

    /** The topic a `/topics/{topic}` page is about — one nobody described yet is a bare name. */
    fun topic(name: String): Topic = topics.find { it.name == name } ?: Topic(name, name.replaceFirstChar { it.uppercase() }, emptyList())
}

class Project(val name: String, val summary: String, val url: String, val dependsOn: List<Project> = emptyList())

class Topic(val name: String, val title: String, val projects: List<Project>)

val STACK: Stack = run {
    val kson = Project("essential-kson", "JSON for Kotlin multiplatform.", "https://github.com/arkanovicz/essential-kson")
    val kddl = Project("kddl", "A database schema defined once; documentation, SQL scripts and code generated from it.",
        "https://github.com/arkanovicz/kddl")
    val skorm = Project("skorm", "Simple Kotlin object-relational mapping: multiplatform, coroutines-enabled.",
        "https://github.com/arkanovicz/skorm", listOf(kson, kddl))
    val velocity = Project("velocity", "Apache Velocity, ported to Kotlin multiplatform: templates compiled or interpreted.",
        "https://velocity.apache.org")
    val kroom = Project("kroom", "Real-time rooms over server-sent events, and the web stack this site runs on.",
        "/index", listOf(kson, kddl, velocity))
    Stack(
        projects = listOf(kson, kddl, skorm, velocity, kroom),
        topics = listOf(
            Topic("authoring", "Authoring", listOf(kroom, velocity)),
            Topic("themes", "Themes", listOf(kroom, velocity)),
            Topic("plugins", "Plugins", listOf(kroom)),
        )
    )
}
