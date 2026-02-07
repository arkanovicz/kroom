package com.republicate.kroom.webapp.l10n

import org.apache.velocity.Template
import org.apache.velocity.exception.ResourceNotFoundException
import org.apache.velocity.runtime.directive.Parse

/**
 * Velocity directive that parses and translates an included template.
 *
 * Usage: #translate('path/to/template.html')
 *
 * Like #parse, but applies translation to the included content.
 * Requires an active Translator in Translator.current ThreadLocal.
 */
class TranslateDirective : Parse() {

    override fun getName(): String = "translate"

    @Throws(ResourceNotFoundException::class)
    override fun getTemplate(path: String, encoding: String): Template {
        val template = super.getTemplate(path, encoding)
            ?: throw ResourceNotFoundException("Template not found: $path")
        val translator = Translator.current.get()
            ?: return template  // No translator, return untranslated
        return translator.translate(path, template)
    }
}
