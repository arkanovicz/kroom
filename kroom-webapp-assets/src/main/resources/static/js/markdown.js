// markdown.js - the markdown toolbar: buttons and shortcuts that format a textarea's selection
// Part of kroom-webapp-assets (after domhelper.js)
//
// Usage:
//   textarea.before(kroomMarkdown.toolbar(textarea));
//   textarea.on('keydown', e => kroomMarkdown.shortcut(e, textarea));
//
// Every edit goes through replace(), which the textarea hears as `input`. The block editor (authoring.js)
// is built on it, and any other editor of markdown text may be.

(function () {
    if (window.kroomMarkdown) return;

    const STOCK = { en: {
        bold: 'bold (Ctrl+B)', italic: 'italic (Ctrl+I)', heading: 'heading', link: 'link (Ctrl+K)',
        bullets: 'bulleted list', numbers: 'numbered list', quote: 'quote', code: 'code',
        // what a button writes when nothing is selected
        boldText: 'bold', italicText: 'italic', linkText: 'text', codeText: 'code'
    }, fr: {
        bold: 'gras (Ctrl+B)', italic: 'italique (Ctrl+I)', heading: 'titre', link: 'lien (Ctrl+K)',
        bullets: 'liste à puces', numbers: 'liste numérotée', quote: 'citation', code: 'code',
        // what a button writes when nothing is selected
        boldText: 'gras', italicText: 'italique', linkText: 'texte', codeText: 'code'
    } };

    /** The stock language asked for, or the browser's first one that is stocked; English otherwise. */
    function pickLanguage(asked) {
        const wanted = asked && asked !== 'auto' ? [asked] : (navigator.languages || [navigator.language]);
        return wanted.map(l => String(l).toLowerCase().split('-')[0]).find(l => STOCK[l]) || 'en';
    }

    const language = pickLanguage();
    const strings = Object.assign({}, STOCK.en, STOCK[language]);

    /** The words a toolbar says: the stock ones in the language asked for, under what the caller gives. */
    const wordsOf = (options = {}) => Object.assign({}, STOCK.en,
        STOCK[options.language ? pickLanguage(options.language) : language], options.strings);

    // one stroked path each on a 24px grid, in the text's colour; the stroke width a CSS variable (--kroom-icon-stroke)
    const icons = {
        bold: 'M7 5h5.5a3.5 3.5 0 0 1 0 7H7zM7 12h6.5a3.5 3.5 0 0 1 0 7H7z',
        italic: 'M11 5h7M6 19h7M14.5 5l-5 14',
        heading: 'M4 5v14M14 5v14M4 12h10M18 14l2-1.5V19',
        link: 'M10 14a4 4 0 0 0 5.66 0l3.17-3.17a4 4 0 0 0-5.66-5.66L12 6.34M14 10a4 4 0 0 0-5.66 0l-3.17 3.17a4 4 0 0 0 5.66 5.66L12 17.66',
        bullets: 'M5 6.5h.01M5 12h.01M5 17.5h.01M10 6.5h10M10 12h10M10 17.5h10',
        numbers: 'M4 5.5l1.5-1V10M4 14.8a1.5 1.5 0 0 1 3 .2c0 1.3-3 2.4-3 4.5h3M11 7h9M11 12h9M11 17h9',
        quote: 'M9.5 7C6.5 8 5 10 5 13v4h4.5v-4.5H5M19 7c-3 1-4.5 3-4.5 6v4H19v-4.5h-4.5',
        code: 'M8 7l-5 5 5 5M16 7l5 5-5 5M13.5 5l-3 14'
    };

    const SVG = 'http://www.w3.org/2000/svg';

    /** A pictogram, drawn from [own] when it has the name, from the stock otherwise. */
    function icon(name, own) {
        const svg = document.createElementNS(SVG, 'svg');
        svg.setAttribute('viewBox', '0 0 24 24');
        svg.setAttribute('class', 'kroom-icon');
        svg.setAttribute('aria-hidden', 'true');
        svg.appendChild(document.createElementNS(SVG, 'path')).setAttribute('d', own?.[name] ?? icons[name]);
        return svg;
    }

    // --- editing ---------------------------------------------------------------------------------

    /**
     * Replace [start, end) with [text], then select [selStart, selEnd) relative to [start]. Through
     * execCommand when the browser still has it: that edit lands on the textarea's own undo stack.
     */
    function replace(textarea, start, end, text, selStart = text.length, selEnd = selStart) {
        textarea.focus();
        textarea.setSelectionRange(start, end);
        let native = false;
        try { native = document.execCommand('insertText', false, text); } catch (_) { /* gone */ }
        if (!native) textarea.setRangeText(text, start, end);
        textarea.setSelectionRange(start + selStart, start + selEnd);
        // heard as typing either way
        if (!native) textarea.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText' }));
    }

    /** `**bold**` around the selection, or off it when it already wears it. */
    function wrap(textarea, mark, placeholder, close = mark) {
        const { value, selectionStart: s, selectionEnd: e } = textarea;
        if (value.slice(s - mark.length, s) === mark && value.slice(e, e + close.length) === close) {
            return replace(textarea, s - mark.length, e + close.length, value.slice(s, e), 0, e - s);
        }
        const inner = value.slice(s, e) || placeholder;
        replace(textarea, s, e, mark + inner + close, mark.length, mark.length + inner.length);
    }

    /** A prefix on every line the selection touches — or off them all, when they all have it. The selection stays on the text. */
    function prefix(textarea, marker) {
        const { value, selectionStart: s, selectionEnd: e } = textarea;
        const start = s > 0 ? value.lastIndexOf('\n', s - 1) + 1 : 0;
        const found = value.indexOf('\n', e > s && value[e - 1] === '\n' ? e - 1 : e);
        const end = found < 0 ? value.length : found;
        const lines = value.slice(start, end).split('\n');
        const pattern = typeof marker === 'string' ? new RegExp('^' + marker.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')) : marker.pattern;
        const on = lines.every(line => pattern.test(line));
        const text = lines.map((line, i) => on ? line.replace(pattern, '')
            : (typeof marker === 'string' ? marker : marker.make(i)) + line).join('\n');
        const first = text.split('\n')[0].length - lines[0].length;   // what the first line gained or lost
        const selStart = Math.max(0, s - start + first);
        replace(textarea, start, end, text, selStart, Math.max(selStart, e - start + text.length - (end - start)));
    }

    function link(textarea, words = strings) {
        const { value, selectionStart: s, selectionEnd: e } = textarea;
        const selected = value.slice(s, e);
        if (/^https?:\/\/\S+$/.test(selected)) {
            const text = words.linkText;
            return replace(textarea, s, e, `[${text}](${selected})`, 1, 1 + text.length);
        }
        const label = selected || words.linkText;
        replace(textarea, s, e, `[${label}](https://)`, label.length + 3, label.length + 11);
    }

    function code(textarea, words = strings) {
        const { value, selectionStart: s, selectionEnd: e } = textarea;
        if (value.slice(s, e).includes('\n')) wrap(textarea, '```\n', '', '\n```');
        else wrap(textarea, '`', words.codeText);
    }

    // apply(textarea, words): words name what a button writes when nothing is selected
    const formats = [
        { name: 'bold', key: 'b', apply: (ta, words = strings) => wrap(ta, '**', words.boldText) },
        { name: 'italic', key: 'i', apply: (ta, words = strings) => wrap(ta, '_', words.italicText) },
        { name: 'heading', apply: ta => prefix(ta, '## ') },
        { name: 'link', key: 'k', apply: link },
        { name: 'bullets', apply: ta => prefix(ta, '- ') },
        { name: 'numbers', apply: ta => prefix(ta, { pattern: /^\d+\. /, make: i => `${i + 1}. ` }) },
        { name: 'quote', apply: ta => prefix(ta, '> ') },
        { name: 'code', apply: code }
    ];

    const resolve = (target) => typeof target === 'function' ? target() : target;

    /** The formats as icon buttons, acting on [target]: a textarea, or a function answering the one to act on (or none). */
    function toolbar(target, options = {}) {
        const words = wordsOf(options);
        const bar = document.createElement('div');
        bar.className = 'kroom-format';
        formats.concat(options.extra || []).forEach(format => {
            const btn = bar.appendChild(document.createElement('button'));
            btn.type = 'button';
            btn.className = `kroom-format-${format.name}`;
            btn.appendChild(icon(format.name, options.icons));
            btn.title = words[format.name] ?? format.name;
            btn.setAttribute('aria-label', btn.title);
            // the textarea keeps the focus, and the selection the button acts on
            btn.addEventListener('mousedown', event => event.preventDefault());
            btn.addEventListener('click', () => {
                const textarea = resolve(target);
                if (textarea) format.apply(textarea, words);
            });
        });
        return bar;
    }

    /** Ctrl/Cmd + a format's key: true when handled (its default prevented). */
    function shortcut(event, textarea, extra = [], options = {}) {
        if (!(event.ctrlKey || event.metaKey) || event.altKey || event.shiftKey) return false;
        const format = formats.concat(extra).find(f => f.key && f.key === event.key.toLowerCase());
        const target = format && resolve(textarea);
        if (!target) return false;
        event.preventDefault();
        format.apply(target, wordsOf(options));
        return true;
    }

    window.kroomMarkdown = { formats, replace, wrap, prefix, link, code, toolbar, shortcut, strings, stock: STOCK, language, icons, icon };
})();
