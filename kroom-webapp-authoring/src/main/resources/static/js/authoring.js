// authoring.js - in-place editing of kroom content blocks
// Part of kroom-webapp-authoring. Needs domhelper.js, api.js and lib/diff-match-patch.
//
// The wrapper template ships only the block and its two buttons; every piece of editor markup below is
// built here, so a visitor who cannot edit downloads nothing of it.
//
// The preview is the page itself: the server re-renders the page this block sits in, with what you are
// typing standing in for the stored block, and the fold below the textarea shows that block's part of it.
// Same layout, same context, same renderer — nothing here guesses what markdown becomes.

(function () {

    const PREVIEW_DELAY = 700;             // idle before the preview is re-rendered (and the lock refreshed)
    const FOLD_KEY = 'kroom.authoring.preview';

    // one block at a time: { block, root, path, rev, textarea, editor, body, timer }
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

    const remember = {
        get(key, fallback) {
            try {
                const value = localStorage.getItem(key);
                return value === null ? fallback : value === 'true';
            } catch (_) { return fallback; }
        },
        set(key, value) {
            try { localStorage.setItem(key, String(value)); } catch (_) { /* private window */ }
        }
    };

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

    /** A left/right diff view inside a dialog, with its two column captions. */
    function diffView(dlg, leftTitle, rightTitle, left, right) {
        const pane = $('.kroom-dialog-body', dlg);
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

    /** Take the block, then open it on what the lock answered. [seed] overrides the stored body. */
    async function edit(block, seed) {
        if (session) {
            if (session.block !== block) return notice(block, 'finish the block you are editing first');
            if (seed !== undefined) fill(seed);
            return;
        }
        const root = apiRoot(block);
        if (!root) return;
        const path = encodePath(block.data('content'));
        try {
            const held = await api.postJson(root + 'lock/' + path);
            build(block, root, path, held, seed);
        } catch (err) {
            notice(block, err.message);
        }
    }

    function build(block, root, path, held, seed) {
        const body = block.querySelector('.kroom-block-body');
        const editor = element('div', 'kroom-editor');

        // the preview: the published rendering to start with, re-rendered from the server as you type
        const preview = element('details', 'kroom-preview');
        preview.open = remember.get(FOLD_KEY, true);
        preview.appendChild(element('summary', null, 'preview'));
        // unfolding shows what the server last rendered; it only costs a render if you typed meanwhile
        preview.on('toggle', () => {
            remember.set(FOLD_KEY, preview.open);
            if (preview.open && preview.hasClass('kroom-stale')) refresh();
        });
        block.insertBefore(editor, body);
        preview.appendChild(body);
        editor.appendChild(preview);

        const textarea = editor.appendChild(element('textarea', 'kroom-source'));
        textarea.value = seed !== undefined ? seed : held.body;
        textarea.spellcheck = false;

        const tools = editor.appendChild(element('nav', 'kroom-editor-tools'));
        tools.appendChild(button('\u2713', 'kroom-submit', submit));
        tools.appendChild(button('\u2717', 'kroom-cancel secondary', cancel));
        tools.appendChild(element('span', 'kroom-status'));

        session = { block, root, path, rev: held.rev, textarea, editor, body, timer: null, previewed: textarea.value };
        block.addClass('kroom-editing');
        textarea.on('input', typed);
        grow(textarea);
        textarea.focus();
        if (seed !== undefined) {
            preview.addClass('kroom-stale');
            status('loaded from history \u2014 submit to write it');
            refresh();
        }
    }

    function fill(text) {
        session.textarea.value = text;
        typed();
        session.textarea.focus();
    }

    function grow(textarea) {
        textarea.style.height = 'auto';
        textarea.style.height = `${textarea.scrollHeight}px`;
    }

    /** Typing means two things: the preview is behind, and the lock is alive. Both settle once you pause. */
    function typed() {
        grow(session.textarea);
        $('.kroom-preview', session.editor).addClass('kroom-stale');
        clearTimeout(session.timer);
        session.timer = setTimeout(refresh, PREVIEW_DELAY);
    }

    /** Re-render the page around this block, with the textarea standing in for what the store holds. */
    async function refresh() {
        const held = session;
        if (!held) return;
        const preview = $('.kroom-preview', held.editor);
        const body = held.textarea.value;
        // folded, or nothing new to show: the lock still needs its heartbeat, the renderer does not
        if (!preview.open || body === held.previewed) {
            preview.removeClass('kroom-stale');
            api.postJson(held.root + 'lock/' + held.path).catch(err => status(`lock lost: ${err.message}`));
            return;
        }
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
            status(err.message);
        }
    }

    async function submit() {
        const held = session;
        status('saving\u2026');
        try {
            await api.postJson(held.root + held.path, { rev: held.rev, body: held.textarea.value });
            // the block's html only exists as part of a page render, and the submit answers a rev, not html
            session = null;
            location.reload();
        } catch (err) {
            if (err.status === 409 && err.data && err.data.theirs) conflict(err.data.theirs);
            else status(err.message);
        }
    }

    /** Someone wrote the block while it was open. Show both, and adopt their revision either way. */
    function conflict(theirs) {
        const dlg = dialog('This block changed while you were editing');
        diffView(dlg, 'yours', 'theirs', session.textarea.value, theirs.body);
        const footer = $('footer', dlg);
        footer.appendChild(button('keep mine', 'kroom-keep', () => {
            session.rev = theirs.rev;
            dlg.close();
            status('editing against their revision \u2014 submitting now overwrites it');
        }));
        footer.appendChild(button('take theirs', 'secondary', () => {
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

    async function history(block) {
        const root = apiRoot(block);
        if (!root) return;
        const path = encodePath(block.data('content'));
        let log;
        try {
            log = await api.getJson(root + 'history/' + path);
        } catch (err) {
            return notice(block, err.status === 404 ? 'this content store keeps no history' : err.message);
        }
        const dlg = dialog(block.data('name') || block.data('content'));
        const pane = $('.kroom-dialog-body', dlg);
        if (log.length === 0) pane.appendChild(element('p', null, 'no revision yet'));
        const list = pane.appendChild(element('ul', 'kroom-revisions'));
        log.forEach(revision => {
            const line = list.appendChild(element('li'));
            line.appendChild(button(
                `${new Date(revision.time).toLocaleString()} \u2014 ${revision.author || 'unknown'}`,
                'kroom-revision secondary outline',
                () => show(dlg, block, root, path, revision)
            ));
        });
        $('footer', dlg).appendChild(button('close', 'secondary', () => dlg.close()));
    }

    /** One revision against the block as it stands now; restoring it is an edit like any other. */
    async function show(dlg, block, root, path, revision) {
        const [past, now] = await Promise.all([
            api.getJson(`${root}${path}?rev=${encodeURIComponent(revision.rev)}`),
            api.getJson(root + path)
        ]);
        diffView(dlg, new Date(revision.time).toLocaleString(), 'now', past.body, now.body);
        const footer = $('footer', dlg);
        footer.clear();
        footer.appendChild(button('restore', 'kroom-restore', () => {
            dlg.close();
            edit(block, past.body);
        }));
        footer.appendChild(button('close', 'secondary', () => dlg.close()));
    }

    // --- wiring ----------------------------------------------------------------------------------

    document.on('click', event => {
        const btn = event.target.closest('.kroom-block-tools button');
        if (!btn) return;
        const block = btn.closest('.kroom-block');
        if (btn.hasClass('kroom-edit')) edit(block);
        else if (btn.hasClass('kroom-history')) history(block);
    });

    // leaving with the block open loses the text; the lock itself needs no goodbye, it expires
    window.on('beforeunload', event => {
        if (!session) return;
        event.preventDefault();
        event.returnValue = '';
    });

})();
