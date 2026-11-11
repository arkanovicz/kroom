package com.republicate.kroom.webapp.authoring


/**
 * A theme for testing themes: every layout of the contract (`article`, `wide`, `gallery`), each showing `$top`
 * and `$east` when a page writes them, in a look nobody would mistake for another — a switch shows at a glance. Its layouts say which
 * one rendered (`data-layout`), so a test can tell a fallback from the real thing.
 */
class DummyTheme : Theme {
    override val id = "dummy"
    override val name = "Dummy"
    override val description = "Every layout, plainly — for testing themes end to end"
    override val layouts = setOf("article", "wide", "gallery")
    override val settings = listOf(
        Setting.text("accent", "Accent colour", default = "#b45309", help = "Any CSS colour")
    )
}
