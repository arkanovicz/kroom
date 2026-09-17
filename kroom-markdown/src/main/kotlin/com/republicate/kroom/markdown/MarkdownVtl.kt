package com.republicate.kroom.markdown

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.Lexer
import org.apache.velocity.engine.LexerSource
import com.republicate.kroom.markdown.parser.MarkdownLexer

/**
 * VTL for Markdown documents: directives are written `%if`, `%foreach`, `%end`, comments `%* … *%`, and
 * `#` means nothing to the engine — it is a heading, rendered as the literal text it is.
 *
 * This object IS the configuration: it carries the lexer generated for these sigils together with the
 * sigils themselves, so an application names one thing (`parser.lexer.class = …MarkdownVtl`) and the
 * engine asks it what it lexes. There is deliberately no second place to state the characters.
 *
 * `$` is unchanged — references are `$name` / `${expr}` as everywhere else.
 */
object MarkdownVtl : LexerSource {
    override val directiveChar: Char = '%'
    override val blockCallChar: Char = '@'
    override val commentChar: Char = '*'
    override fun lexer(stream: CharStream): Lexer = MarkdownLexer(stream)
}
