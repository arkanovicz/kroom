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
        saving: 'saving\u2026',
        lockLost: 'lock lost: {message}',
        conflict: 'This block changed while you were editing',
        yours: 'yours', theirs: 'theirs', keepMine: 'keep mine', takeTheirs: 'take theirs',
        keptMine: 'editing against their revision \u2014 submitting now overwrites it',
        revision: '{time} \u2014 {author}', current: '(current)', unknownAuthor: 'unknown',
        noRevision: 'no revision yet', restore: 'restore', back: 'back',
        draft: '{time} \u2014 unsaved draft', draftBase: 'before it',
        draftBack: 'your unsaved draft is back \u2014 submit to write it',
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

    // The editor's pictograms: one stroked path each on a 24px grid, in the text's colour — overridable
    // like the words (kroomAuthoring.icons), their stroke width a CSS variable (--kroom-icon-stroke).
    const icons = Object.assign({
        edit: 'M4 20h4L19 9l-4-4L4 16v4zM13 7l4 4',
        bold: 'M7 5h5.5a3.5 3.5 0 0 1 0 7H7zM7 12h6.5a3.5 3.5 0 0 1 0 7H7z',
        italic: 'M11 5h7M6 19h7M14.5 5l-5 14',
        heading: 'M4 5v14M14 5v14M4 12h10M18 14l2-1.5V19',
        link: 'M10 14a4 4 0 0 0 5.66 0l3.17-3.17a4 4 0 0 0-5.66-5.66L12 6.34M14 10a4 4 0 0 0-5.66 0l-3.17 3.17a4 4 0 0 0 5.66 5.66L12 17.66',
        bullets: 'M5 6.5h.01M5 12h.01M5 17.5h.01M10 6.5h10M10 12h10M10 17.5h10',
        numbers: 'M4 5.5l1.5-1V10M4 14.8a1.5 1.5 0 0 1 3 .2c0 1.3-3 2.4-3 4.5h3M11 7h9M11 12h9M11 17h9',
        quote: 'M9.5 7C6.5 8 5 10 5 13v4h4.5v-4.5H5M19 7c-3 1-4.5 3-4.5 6v4H19v-4.5h-4.5',
        code: 'M8 7l-5 5 5 5M16 7l5 5-5 5M13.5 5l-3 14',
        submit: 'M4.5 12.5l4.5 4.5L19.5 6.5',
        cancel: 'M6 6l12 12M18 6L6 18'
    }, window.kroomAuthoring?.icons);

    window.kroomAuthoring = Object.assign(window.kroomAuthoring || {}, { strings, icons });

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

    const SVG = 'http://www.w3.org/2000/svg';

    function icon(name) {
        const svg = document.createElementNS(SVG, 'svg');
        svg.setAttribute('viewBox', '0 0 24 24');
        svg.setAttribute('class', 'kroom-icon');
        svg.setAttribute('aria-hidden', 'true');
        svg.appendChild(document.createElementNS(SVG, 'path')).setAttribute('d', icons[name]);
        return svg;
    }

    /** A button showing a pictogram, named by the strings table for tooltips and screen readers. */
    function iconButton(name, className, onClick) {
        const btn = button(undefined, className, onClick);
        btn.appendChild(icon(name));
        btn.title = t(name);
        btn.attr('aria-label', t(name));
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
        if (session) return;             // one block at a time: the others show no handle meanwhile
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
        FORMATS.forEach(f => format.appendChild(iconButton(f.name, `kroom-format-${f.name}`, () => f.apply(session.textarea))));

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
        tools.appendChild(iconButton('submit', 'kroom-submit', submit));
        tools.appendChild(iconButton('cancel', 'kroom-cancel secondary', cancel));
        tools.appendChild(element('span', 'kroom-status'));

        session = {
            block, root, path, rev: held.rev, textarea, editor, body, timer: null, previewed: held.body,
            stored: held.body, startRev: held.rev, draft: drafts.read(block), kept: true
        };
        block.addClass('kroom-editing');
        document.documentElement.addClass('kroom-busy');
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
        keep();
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
        { name: 'bold', key: 'b', apply: ta => wrap(ta, '**', t('boldText')) },
        { name: 'italic', key: 'i', apply: ta => wrap(ta, '_', t('italicText')) },
        { name: 'heading', apply: ta => prefix(ta, '## ') },
        { name: 'link', key: 'k', apply: link },
        { name: 'bullets', apply: ta => prefix(ta, '- ') },
        { name: 'numbers', apply: ta => prefix(ta, { pattern: /^\d+\. /, make: i => `${i + 1}. ` }) },
        { name: 'quote', apply: ta => prefix(ta, '> ') },
        { name: 'code', apply: code }
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
            drafts.drop(held.block);
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
            keep();
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
        drafts.drop(held.block);
        restore();
    }

    function restore() {
        clearTimeout(session.timer);
        session.block.insertBefore(session.body, session.editor);
        session.editor.remove();
        session.block.removeClass('kroom-editing');
        document.documentElement.removeClass('kroom-busy');
        session = null;
    }

    // --- history ---------------------------------------------------------------------------------

    /** The draft, if any, then what the store remembers of this block — asked again each time the tab is shown. */
    async function history() {
        const held = session;
        const pane = $('.kroom-past', held.editor);
        let log = [], failure = null;
        try {
            log = await api.getJson(held.root + 'history/' + held.path);
        } catch (err) {
            failure = said(err);
        }
        if (session !== held) return;
        pane.clear();
        const list = pane.appendChild(element('ul', 'kroom-revisions'));
        if (held.draft) {
            const draft = held.draft;
            list.appendChild(element('li')).appendChild(button(t('draft', { time: new Date(draft.time).toLocaleString() }),
                'kroom-revision kroom-draft secondary outline', () => redraft(draft)));
        }
        if (failure || (!held.draft && log.length === 0)) pane.appendChild(element('p', 'kroom-empty', failure || t('noRevision')));
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

    /** A draft against the revision it started from: what it changes. Restoring it takes that revision back. */
    async function redraft(draft) {
        const held = session;
        const pane = $('.kroom-past', held.editor);
        let base = held.stored;
        if (draft.rev !== held.startRev) {
            try {
                base = (await api.getJson(`${held.root}${held.path}?rev=${encodeURIComponent(draft.rev)}`)).body;
            } catch (_) { /* out of reach: shown against the block as stored */ }
        }
        if (session !== held) return;
        const when = new Date(draft.time).toLocaleString();
        diffView(pane, t('draftBase'), t('draft', { time: when }), base, draft.body);
        const tools = pane.appendChild(element('nav', 'kroom-past-tools'));
        if (draft.body !== held.textarea.value) tools.appendChild(button(t('restore'), 'kroom-restore', () => {
            held.rev = draft.rev;           // a draft older than the block meets the ordinary conflict on submit
            fill(draft.body);
            status(t('restored', { time: when }));
        }));
        tools.appendChild(button(t('back'), 'kroom-back secondary', history));
    }

    // --- drafts ----------------------------------------------------------------------------------

    // What is typed outlives the page: kept as it is typed, under the author and the block, with the rev it
    // started from — so a draft the block moved past meets the ordinary conflict on submit. Submit and
    // cancel drop it; a browser that refuses to store it keeps the leave-page warning instead.
    const drafts = {
        key: (block) => `kroom.draft:${block.data('user')}:${block.data('content')}`,
        read(block) {
            try { return JSON.parse(localStorage.getItem(this.key(block))); } catch (_) { return null; }
        },
        write(block, draft) {
            try { localStorage.setItem(this.key(block), JSON.stringify(draft)); return true; } catch (_) { return false; }
        },
        drop(block) {
            try { localStorage.removeItem(this.key(block)); } catch (_) { /* nothing was kept */ }
        }
    };

    function keep() {
        const held = session;
        if (held.textarea.value === held.stored && held.rev === held.startRev) {
            drafts.drop(held.block);
            held.draft = null;
            held.kept = true;
        } else {
            held.draft = { rev: held.rev, body: held.textarea.value, time: Date.now() };
            held.kept = drafts.write(held.block, held.draft);
        }
    }

    /**
     * A draft whose lock is still ours is a page reloaded mid-edit: open it again, as it was. An older one
     * (its lock gone) opens nothing — it waits in the history tab of its block.
     */
    async function resume() {
        const found = [...document.querySelectorAll('.kroom-block[data-user]')]
            .map(block => ({ block, draft: drafts.read(block) }))
            .filter(found => found.draft)
            .sort((a, b) => b.draft.time - a.draft.time);
        for (const { block, draft } of found) {
            const root = apiRoot(block);
            if (!root) continue;
            let now;
            try {
                now = await api.getJson(root + encodePath(block.data('content')));
            } catch (_) { continue; }
            if (now.lock?.owner !== block.data('user')) continue;
            await edit(block);
            if (!session) return;
            session.rev = draft.rev;
            fill(draft.body);
            status(t('draftBack'));
            return;
        }
    }

    // --- wiring ----------------------------------------------------------------------------------

    // the wrapper template carries no words and no picture: the edit handle is dressed here, with the rest
    // (a wrapper of the application's that draws its own keeps it)
    function ready() {
        dress(document);
        resume();
    }

    function dress(root) {
        root.querySelectorAll('.kroom-block-tools .kroom-edit').forEach(btn => {
            if (!btn.textContent.trim() && !btn.firstElementChild) btn.appendChild(icon('edit'));
            btn.title = t('edit');
            btn.attr('aria-label', t('edit'));
        });
    }
    if (document.readyState === 'loading') document.on('DOMContentLoaded', ready);
    else ready();

    document.on('click', event => {
        const btn = event.target.closest('.kroom-block-tools button');
        if (!btn) return;
        const block = btn.closest('.kroom-block');
        if (btn.hasClass('kroom-edit')) edit(block);
    });

    // leaving loses nothing a draft keeps; the lock itself needs no goodbye, it expires
    window.on('beforeunload', event => {
        if (!session || session.kept) return;
        event.preventDefault();
        event.returnValue = '';
    });

})();
