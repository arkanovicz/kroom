// authoring.js - in-place editing of kroom content blocks
// Part of kroom-webapp-authoring. Needs domhelper.js, api.js and lib/diff-match-patch.
//
// The wrapper template ships only the block and its edit button; every piece of editor markup below is
// built here, so a visitor who cannot edit downloads nothing of it.
//
// The preview is the page itself: the server re-renders the page this block sits in, with what you are
// typing standing in for the stored block, and the preview tab shows that block's part of it.
// Same layout, same context, same renderer — nothing here guesses what markdown becomes.

(function () {

    const HEARTBEAT_DELAY = 700;           // idle before the lock is refreshed

    // Every word the editor says, error codes of the edit API included. kroom translates nothing: an
    // application overrides them — server side (AuthoringConfig.strings, emitted ahead of this script), or
    // client side, before or after it: Object.assign(kroomAuthoring.strings, { preview: 'aperçu' }).
    const strings = Object.assign({
        edit: 'edit',
        markdown: 'markdown', preview: 'preview', history: 'history',
        submit: 'submit', cancel: 'cancel',
        bold: 'bold (Ctrl+B)', italic: 'italic (Ctrl+I)', heading: 'heading', link: 'link (Ctrl+K)',
        bullets: 'bulleted list', numbers: 'numbered list', quote: 'quote', code: 'code',
        // what a formatting button writes when nothing is selected
        boldText: 'bold', italicText: 'italic', linkText: 'text', codeText: 'code',
        otherBlock: 'finish the block you are editing first',
        saving: 'saving\u2026',
        lockLost: 'lock lost: {message}',
        conflict: 'This block changed while you were editing',
        yours: 'yours', theirs: 'theirs', keepMine: 'keep mine', takeTheirs: 'take theirs',
        keptMine: 'editing against their revision \u2014 submitting now overwrites it',
        revision: '{time} \u2014 {author}', current: '(current)', unknownAuthor: 'unknown',
        noRevision: 'no revision yet', restore: 'restore', back: 'back',
        restored: '{time} restored \u2014 submit to write it',
        // the edit API's errors: each said through `error`, by its code when the table has it
        error: 'Error: {message}',
        lockHeld: 'block held by {owner}', notYourLock: 'the lock on {path} is not yours',
        stale: '{path} changed since you started editing', notAuthenticated: 'not authenticated',
        forbidden: 'not allowed to edit {path}', noHistory: 'this content store keeps no history',
        noSuchRevision: 'no such revision: {rev}', noPage: 'no page at {page}',
        pageMissing: 'a block is rendered within the page it is in: page missing',
        invalidPath: 'invalid content path', broken: '{message}'
    }, window.kroomAuthoring?.strings);
    window.kroomAuthoring = Object.assign(window.kroomAuthoring || {}, { strings });

    const t = (key, args = {}) => (strings[key] ?? key).replace(/\{(\w+)\}/g, (_, name) => args[name] ?? '');

    /** An API error in the application's words when its code has some, in the server's otherwise. */
    const reason = (err) => err.data?.code && strings[err.data.code] !== undefined
        ? t(err.data.code, err.data.args) : err.message;
    const said = (err) => t('error', { message: reason(err) });

    // one block at a time: { block, root, path, rev, textarea, editor, body, timer, previewed }
    let session = null;

    // --- plumbing --------------------------------------------------------------------------------

    // api.js roots every call at /api/, so the authoring prefix is expressed relative to it
    function apiRoot(block) {
        const prefix = block.data('api') || '/api/content';
        if (!prefix.startsWith('/api/')) {
            console.error(`authoring.js: apiPrefix must live under /api/ (api.js base), got ${prefix}`);
            return null;
        }
        return prefix.slice(5) + '/';
    }

    const encodePath = (path) => path.split('/').map(encodeURIComponent).join('/');

    function element(tag, className, text) {
        const el = document.createElement(tag);
        if (className) el.className = className;
        if (text !== undefined) el.textContent = text;
        return el;
    }

    function status(text) {
        if (session) $('.kroom-status', session.editor).text(text || '');
    }

    /** Transient word to an author whose block never opened. */
    let noticeTimer = null;

    function notice(block, text) {
        const said = block.querySelector('.kroom-notice')
            || block.insertBefore(element('p', 'kroom-notice'), block.firstChild);
        said.textContent = text;
        clearTimeout(noticeTimer);
        noticeTimer = setTimeout(() => said.remove(), 6000);
    }

    // --- diff ------------------------------------------------------------------------------------

    /** Two texts, side by side: what the left lacks, what the right adds, the rest shared. */
    function diffPanes(leftPane, rightPane, left, right) {
        const dmp = new diff_match_patch();
        const diffs = dmp.diff_main(left, right);
        dmp.diff_cleanupSemantic(diffs);
        leftPane.clear();
        rightPane.clear();
        // a diff is array-LIKE (diff_match_patch.Diff), not iterable: read it by index, never destructure
        diffs.forEach(diff => {
            const op = diff[0], text = diff[1];
            if (op >= 0) rightPane.appendChild(element('span', op > 0 ? 'diff-insert' : 'diff-equal', text));
            if (op <= 0) leftPane.appendChild(element('span', op < 0 ? 'diff-delete' : 'diff-equal', text));
        });
    }

    function dialog(title) {
        const dlg = element('dialog', 'kroom-dialog');
        const article = dlg.appendChild(element('article'));
        const header = article.appendChild(element('header'));
        header.appendChild(element('strong', null, title));
        article.appendChild(element('div', 'kroom-dialog-body'));
        article.appendChild(element('footer'));
        document.body.appendChild(dlg);
        dlg.on('close', () => dlg.remove());
        dlg.showModal();
        return dlg;
    }

    function button(label, className, onClick) {
        const btn = element('button', className, label);
        btn.type = 'button';
        btn.on('click', onClick);
        return btn;
    }

    /** A left/right diff view in [pane], with its two column captions. */
    function diffView(pane, leftTitle, rightTitle, left, right) {
        pane.clear();
        const columns = element('div', 'kroom-diff');
        const leftCol = columns.appendChild(element('section'));
        const rightCol = columns.appendChild(element('section'));
        leftCol.appendChild(element('h6', null, leftTitle));
        rightCol.appendChild(element('h6', null, rightTitle));
        const leftPre = leftCol.appendChild(element('pre'));
        const rightPre = rightCol.appendChild(element('pre'));
        diffPanes(leftPre, rightPre, left, right);
        pane.appendChild(columns);
    }

    // --- editing ---------------------------------------------------------------------------------

    /** Take the block, then open it on what the lock answered. */
    async function edit(block) {
        if (session) {
            if (session.block !== block) notice(block, t('otherBlock'));
            return;
        }
        const root = apiRoot(block);
        if (!root) return;
        const path = encodePath(block.data('content'));
        try {
            const held = await api.postJson(root + 'lock/' + path);
            build(block, root, path, held);
        } catch (err) {
            notice(block, said(err));
        }
    }

    function build(block, root, path, held) {
        const body = block.querySelector('.kroom-block-body');
        const editor = element('div', 'kroom-editor');
        block.insertBefore(editor, body);

        const bar = editor.appendChild(element('nav', 'kroom-editor-bar'));
        const tabs = bar.appendChild(element('div', 'kroom-tabs'));
        tabs.attr('role', 'tablist');
        tabs.appendChild(button(t('markdown'), 'kroom-tab', () => tab('source'))).data('tab', 'source');
        tabs.appendChild(button(t('preview'), 'kroom-tab', () => tab('preview'))).data('tab', 'preview');
        tabs.appendChild(button(t('history'), 'kroom-tab', () => tab('history'))).data('tab', 'history');
        const format = bar.appendChild(element('div', 'kroom-format'));
        FORMATS.forEach(f => {
            const btn = format.appendChild(button(f.label, `kroom-format-${f.name}`, () => f.apply(session.textarea)));
            btn.title = t(f.name);
            btn.attr('aria-label', t(f.name));
        });

        const source = editor.appendChild(element('div', 'kroom-pane'));
        source.attr('role', 'tabpanel').data('pane', 'source');
        const textarea = source.appendChild(element('textarea', 'kroom-source'));
        textarea.value = held.body;
        textarea.spellcheck = false;

        // the preview starts as the published rendering, and is re-rendered by the server when shown
        const preview = editor.appendChild(element('div', 'kroom-pane kroom-preview'));
        preview.attr('role', 'tabpanel').data('pane', 'preview');
        preview.appendChild(body);

        const past = editor.appendChild(element('div', 'kroom-pane kroom-past'));
        past.attr('role', 'tabpanel').data('pane', 'history');

        const tools = editor.appendChild(element('nav', 'kroom-editor-tools'));
        [['\u2713', 'submit', submit], ['\u2717', 'cancel', cancel]].forEach(([glyph, name, onClick]) => {
            const btn = tools.appendChild(button(glyph, `kroom-${name}${name === 'cancel' ? ' secondary' : ''}`, onClick));
            btn.title = t(name);
            btn.attr('aria-label', t(name));
        });
        tools.appendChild(element('span', 'kroom-status'));

        session = { block, root, path, rev: held.rev, textarea, editor, body, timer: null, previewed: held.body };
        block.addClass('kroom-editing');
        textarea.on('input', typed);
        textarea.on('keydown', shortcut);
        tab('source');
    }

    /** Show one pane; the preview re-renders on the way in, if the text moved since it last did. */
    function tab(name) {
        const editor = session.editor;
        editor.querySelectorAll('.kroom-tab').forEach(b => b.attr('aria-selected', String(b.data('tab') === name)));
        editor.querySelectorAll('.kroom-pane').forEach(p => { p.hidden = p.data('pane') !== name; });
        $('.kroom-format', editor).hidden = name !== 'source';
        // history submits nothing: restoring goes back to the markdown tab first
        editor.querySelectorAll('.kroom-editor-tools button').forEach(b => { b.hidden = name === 'history'; });
        if (name !== 'source') {
            // the other panes take the height the source had, so switching does not jump the page
            $(`.kroom-pane[data-pane="${name}"]`, editor).style.minHeight = `${session.textarea.offsetHeight}px`;
            if (name === 'preview') refresh();
            else history();
        } else {
            grow(session.textarea);
            session.textarea.focus();
        }
    }

    function fill(text) {
        session.textarea.value = text;
        typed();
        tab('source');
    }

    function grow(textarea) {
        textarea.style.height = 'auto';
        textarea.style.height = `${textarea.scrollHeight}px`;
    }

    /** Typing means the lock is alive; say so once you pause. */
    function typed() {
        grow(session.textarea);
        clearTimeout(session.timer);
        session.timer = setTimeout(heartbeat, HEARTBEAT_DELAY);
    }

    function heartbeat() {
        const held = session;
        if (held) api.postJson(held.root + 'lock/' + held.path).catch(err => status(t('lockLost', { message: reason(err) })));
    }

    /** Re-render the page around this block, with the textarea standing in for what the store holds. */
    async function refresh() {
        const held = session;
        const preview = $('.kroom-preview', held.editor);
        const body = held.textarea.value;
        // nothing new to show, or already on its way
        if (body === held.previewed || body === held.rendering) return;
        held.rendering = body;
        preview.addClass('kroom-stale');
        try {
            // the whole page comes back — the block's own header bindings only exist in that render — but
            // only its body reaches the DOM, and only when it actually differs
            const answer = await api.postJson(held.root + 'preview/' + held.path,
                { page: window.location.pathname, body });
            if (session !== held) return;                     // the editor moved on while we rendered
            held.previewed = body;
            const rendered = new DOMParser().parseFromString(answer.page, 'text/html')
                .querySelector(`.kroom-block[data-content="${held.block.data('content')}"] .kroom-block-body`);
            const shown = $('.kroom-block-body', preview);
            if (rendered && shown && shown.innerHTML !== rendered.innerHTML) shown.innerHTML = rendered.innerHTML;
            preview.removeClass('kroom-stale');
            status('');
        } catch (err) {
            status(said(err));
        } finally {
            if (held.rendering === body) held.rendering = null;
        }
    }

    // --- formatting ------------------------------------------------------------------------------

    /**
     * Replace [start, end) with [text], then select [selStart, selEnd) relative to [start]. Through
     * execCommand when the browser still has it: that edit lands on the textarea's own undo stack.
     */
    function replace(textarea, start, end, text, selStart = text.length, selEnd = selStart) {
        textarea.focus();
        textarea.setSelectionRange(start, end);
        let native = false;
        try { native = document.execCommand('insertText', false, text); } catch (_) { /* gone */ }
        if (!native) {
            textarea.setRangeText(text, start, end);
            typed();
        }
        textarea.setSelectionRange(start + selStart, start + selEnd);
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

    function link(textarea) {
        const { value, selectionStart: s, selectionEnd: e } = textarea;
        const selected = value.slice(s, e);
        if (/^https?:\/\/\S+$/.test(selected)) {
            const text = t('linkText');
            return replace(textarea, s, e, `[${text}](${selected})`, 1, 1 + text.length);
        }
        const label = selected || t('linkText');
        replace(textarea, s, e, `[${label}](https://)`, label.length + 3, label.length + 11);
    }

    function code(textarea) {
        const { value, selectionStart: s, selectionEnd: e } = textarea;
        if (value.slice(s, e).includes('\n')) wrap(textarea, '```\n', '', '\n```');
        else wrap(textarea, '`', t('codeText'));
    }

    const FORMATS = [
        { name: 'bold', label: 'B', key: 'b', apply: ta => wrap(ta, '**', t('boldText')) },
        { name: 'italic', label: 'I', key: 'i', apply: ta => wrap(ta, '_', t('italicText')) },
        { name: 'heading', label: 'H', apply: ta => prefix(ta, '## ') },
        { name: 'link', label: '\u{1F517}', key: 'k', apply: link },
        { name: 'bullets', label: '\u2022', apply: ta => prefix(ta, '- ') },
        { name: 'numbers', label: '1.', apply: ta => prefix(ta, { pattern: /^\d+\. /, make: i => `${i + 1}. ` }) },
        { name: 'quote', label: '\u201C', apply: ta => prefix(ta, '> ') },
        { name: 'code', label: '</>', apply: code }
    ];

    function shortcut(event) {
        if (!(event.ctrlKey || event.metaKey) || event.altKey || event.shiftKey) return;
        const format = FORMATS.find(f => f.key === event.key.toLowerCase());
        if (!format) return;
        event.preventDefault();
        format.apply(session.textarea);
    }

    async function submit() {
        const held = session;
        status(t('saving'));
        try {
            // the page comes along: the server renders it with this body, and refuses one that breaks it
            await api.postJson(held.root + held.path,
                { page: window.location.pathname, rev: held.rev, body: held.textarea.value });
            // the block's html only exists as part of a page render, and the submit answers a rev, not html
            session = null;
            location.reload();
        } catch (err) {
            if (err.status === 409 && err.data && err.data.theirs) conflict(err.data.theirs);
            else status(said(err));
        }
    }

    /** Someone wrote the block while it was open. Show both, and adopt their revision either way. */
    function conflict(theirs) {
        const dlg = dialog(t('conflict'));
        diffView($('.kroom-dialog-body', dlg), t('yours'), t('theirs'), session.textarea.value, theirs.body);
        const footer = $('footer', dlg);
        footer.appendChild(button(t('keepMine'), 'kroom-keep', () => {
            session.rev = theirs.rev;
            dlg.close();
            status(t('keptMine'));
        }));
        footer.appendChild(button(t('takeTheirs'), 'secondary', () => {
            session.rev = theirs.rev;
            dlg.close();
            fill(theirs.body);
        }));
    }

    function cancel() {
        const held = session;
        api.deleteJson(held.root + 'lock/' + held.path).catch(err => console.warn(err.message));
        restore();
    }

    function restore() {
        clearTimeout(session.timer);
        session.block.insertBefore(session.body, session.editor);
        session.editor.remove();
        session.block.removeClass('kroom-editing');
        session = null;
    }

    // --- history ---------------------------------------------------------------------------------

    /** What the store remembers of this block, asked again each time the tab is shown. */
    async function history() {
        const held = session;
        const pane = $('.kroom-past', held.editor);
        let log;
        try {
            log = await api.getJson(held.root + 'history/' + held.path);
        } catch (err) {
            pane.clear();
            pane.appendChild(element('p', 'kroom-empty', said(err)));
            return;
        }
        if (session !== held) return;
        pane.clear();
        if (log.length === 0) return pane.appendChild(element('p', 'kroom-empty', t('noRevision')));
        const list = pane.appendChild(element('ul', 'kroom-revisions'));
        log.forEach(revision => {
            const label = t('revision', { time: new Date(revision.time).toLocaleString(), author: revision.author || t('unknownAuthor') })
                + (revision.rev === held.rev ? ` ${t('current')}` : '');
            list.appendChild(element('li')).appendChild(
                button(label, 'kroom-revision secondary outline', () => revisit(revision)));
        });
    }

    /** One revision against what you are writing; restoring it is an edit like any other. */
    async function revisit(revision) {
        const held = session;
        const pane = $('.kroom-past', held.editor);
        let past;
        try {
            past = await api.getJson(`${held.root}${held.path}?rev=${encodeURIComponent(revision.rev)}`);
        } catch (err) {
            return status(said(err));
        }
        if (session !== held) return;
        const when = new Date(revision.time).toLocaleString();
        diffView(pane, when, t('yours'), past.body, held.textarea.value);
        const tools = pane.appendChild(element('nav', 'kroom-past-tools'));
        tools.appendChild(button(t('restore'), 'kroom-restore', () => {
            fill(past.body);
            status(t('restored', { time: when }));
        }));
        tools.appendChild(button(t('back'), 'kroom-back secondary', history));
    }

    // --- wiring ----------------------------------------------------------------------------------

    // the wrapper template carries no words: the edit handle is named here, with the rest
    function name(root) {
        root.querySelectorAll('.kroom-block-tools .kroom-edit').forEach(btn => {
            btn.title = t('edit');
            btn.attr('aria-label', t('edit'));
        });
    }
    if (document.readyState === 'loading') document.on('DOMContentLoaded', () => name(document));
    else name(document);

    document.on('click', event => {
        const btn = event.target.closest('.kroom-block-tools button');
        if (!btn) return;
        const block = btn.closest('.kroom-block');
        if (btn.hasClass('kroom-edit')) edit(block);
    });

    // leaving with the block open loses the text; the lock itself needs no goodbye, it expires
    window.on('beforeunload', event => {
        if (!session) return;
        event.preventDefault();
        event.returnValue = '';
    });

})();
