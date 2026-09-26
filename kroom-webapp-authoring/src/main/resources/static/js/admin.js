// admin.js - the admin bar, to the left of every page an administrator visits
// Part of kroom-webapp-authoring. Needs api.js. Emitted by `$site.foot()` for Permissions.ADMIN only.
//
// The server hands the entries as data (kroom's own, then the plugins'); every piece of markup is built
// here. A panel opens beside the bar: pages, journal, plugins and their settings, roles — or a plugin's
// table, any API answering {columns, rows}.

(function () {

    const bar = document.querySelector('aside.kroom-admin');
    if (!bar) return;

    // Every word the bar says. Overridable like the editor's: Object.assign(kroomAdmin.strings, {…}).
    const strings = Object.assign({
        pages: 'pages', journal: 'journal', media: 'media', plugins: 'plugins', roles: 'roles', close: 'close',
        noMedia: 'nothing uploaded yet', remove: 'delete', size: '{kb} kB',
        noPages: 'no page template', instances: '{count} pages', noInstance: 'none written yet',
        noJournal: 'this content store keeps no history', revision: '{time} — {author}',
        unknownAuthor: 'unknown', noPlugin: 'no plugin installed', save: 'save', saved: 'saved',
        secretSet: 'set — leave empty to keep', secretUnset: 'not set',
        yours: 'your roles: {roles}', empty: 'nothing yet', error: 'Error: {message}'
    }, window.kroomAdmin?.strings);

    // one stroked path each, on the editor's 24px grid
    const icons = Object.assign({
        pages: 'M7 3h7l5 5v13H7zM14 3v5h5M10 13h6M10 17h6',
        journal: 'M12 7v5l3 2M21 12a9 9 0 1 1-3-6.7M21 4v4h-4',
        media: 'M4 5h16v14H4zM4 16l5-5 4 4 3-3 4 4M15 9h.01',
        plugins: 'M9 3v4M15 3v4M7 7h10v5a5 5 0 0 1-10 0zM12 17v4',
        roles: 'M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM2 21a7 7 0 0 1 14 0M17 8l2 2 4-4',
        close: 'M6 6l12 12M18 6L6 18'
    }, window.kroomAdmin?.icons);

    window.kroomAdmin = Object.assign(window.kroomAdmin || {}, { strings, icons });

    const t = (key, args = {}) => (strings[key] ?? key).replace(/\{(\w+)\}/g, (_, name) => args[name] ?? '');
    const said = (err) => t('error', { message: err.data?.message || err.message });

    // api.js roots every call at /api/
    const relative = (url) => url.startsWith('/api/') ? url.slice(5) : url;
    const siteApi = relative(bar.dataset.api) + '/';
    const contentApi = relative(bar.dataset.contentApi || '/api/content') + '/';

    function element(tag, className, text) {
        const el = document.createElement(tag);
        if (className) el.className = className;
        if (text !== undefined) el.textContent = text;
        return el;
    }

    function icon(path, fallback) {
        if (!path) return element('span', 'kroom-admin-initial', (fallback || '?').charAt(0).toUpperCase());
        const ns = 'http://www.w3.org/2000/svg';
        const svg = document.createElementNS(ns, 'svg');
        svg.setAttribute('viewBox', '0 0 24 24');
        svg.setAttribute('aria-hidden', 'true');
        const p = svg.appendChild(document.createElementNS(ns, 'path'));
        p.setAttribute('d', path);
        return svg;
    }

    // --- the bar -----------------------------------------------------------------------------------

    const entries = JSON.parse(bar.dataset.entries || '[]');
    const nav = bar.appendChild(element('nav'));
    const panel = bar.appendChild(element('section', 'kroom-admin-panel'));
    panel.hidden = true;
    let open = null;

    for (const entry of entries) {
        const label = entry.builtin ? t(entry.id) : entry.label;
        const control = element(entry.href ? 'a' : 'button');
        if (entry.href) control.href = entry.href; else control.type = 'button';
        control.title = label;
        control.setAttribute('aria-label', label);
        control.dataset.entry = entry.id;
        control.appendChild(icon(entry.builtin ? icons[entry.id] : entry.icon, label));
        if (!entry.href) control.addEventListener('click', () => toggle(entry, label, control));
        nav.appendChild(control);
    }

    function toggle(entry, label, control) {
        nav.querySelectorAll('[aria-pressed]').forEach(b => b.removeAttribute('aria-pressed'));
        if (open === entry.id) { close(); return; }
        open = entry.id;
        control.setAttribute('aria-pressed', 'true');
        panel.replaceChildren();
        const header = panel.appendChild(element('header'));
        header.appendChild(element('strong', null, label));
        const shut = header.appendChild(element('button', 'kroom-admin-close'));
        shut.type = 'button';
        shut.title = t('close');
        shut.appendChild(icon(icons.close));
        shut.addEventListener('click', close);
        const body = panel.appendChild(element('div', 'kroom-admin-body'));
        panel.hidden = false;
        panel.classList.toggle('kroom-admin-wide', !!entry.frame);
        const show = entry.builtin ? panels[entry.id]
            : entry.frame ? (into) => frame(entry.frame, label, into)
            : (into) => table(relative(entry.table), into);
        show(body).catch(err => body.replaceChildren(element('p', 'kroom-admin-error', said(err))));
    }

    function close() {
        open = null;
        panel.hidden = true;
        nav.querySelectorAll('[aria-pressed]').forEach(b => b.removeAttribute('aria-pressed'));
    }

    document.addEventListener('keydown', e => { if (e.key === 'Escape' && open) close(); });

    function link(href, text) {
        const a = element('a', null, text);
        a.href = href;
        return a;
    }

    // --- kroom's own panels ------------------------------------------------------------------------

    const panels = {
        async pages(body) {
            const pages = await api.getJson(siteApi + 'pages');
            if (!pages.length) return body.appendChild(element('p', 'kroom-admin-muted', t('noPages')));
            const list = body.appendChild(element('ul', 'kroom-admin-list'));
            for (const page of pages) {
                const item = list.appendChild(element('li'));
                if (page.route.includes('{')) {
                    item.appendChild(element('span', 'kroom-admin-pattern', page.route));
                    if (!page.urls.length) item.appendChild(element('small', 'kroom-admin-muted', ' ' + t('noInstance')));
                    const sub = item.appendChild(element('ul'));
                    page.urls.forEach(url => sub.appendChild(element('li')).appendChild(link(url, url)));
                } else {
                    item.appendChild(link(page.route, page.route));
                }
                item.title = page.template;
            }
        },

        async journal(body) {
            let log;
            try {
                log = await api.getJson(contentApi + 'journal?limit=100');
            } catch (err) {
                if (err.data?.code !== 'noHistory') throw err;
                return body.appendChild(element('p', 'kroom-admin-muted', t('noJournal')));
            }
            if (!log.length) return body.appendChild(element('p', 'kroom-admin-muted', t('empty')));
            const list = body.appendChild(element('ul', 'kroom-admin-list'));
            for (const rev of log) {
                const item = list.appendChild(element('li'));
                item.appendChild(element('small', 'kroom-admin-muted', t('revision', {
                    time: new Date(rev.time).toLocaleString(), author: rev.author || t('unknownAuthor')
                })));
                item.appendChild(element('div', null, rev.path));
            }
        },

        async media(body) {
            const files = await api.getJson(contentApi + 'media');
            if (!files.length) return body.appendChild(element('p', 'kroom-admin-muted', t('noMedia')));
            const grid = body.appendChild(element('ul', 'kroom-admin-media'));
            for (const file of files) {
                const item = grid.appendChild(element('li'));
                const open = item.appendChild(link(file.url, ''));
                if (file.type.startsWith('image/')) {
                    const img = open.appendChild(element('img'));
                    img.src = file.url;
                    img.alt = file.name;
                    img.loading = 'lazy';
                } else {
                    open.textContent = file.name;
                }
                item.title = file.name;
                item.appendChild(element('small', 'kroom-admin-muted', t('size', { kb: Math.ceil(file.size / 1024) })));
                const remove = item.appendChild(element('button', 'kroom-admin-remove', t('remove')));
                remove.type = 'button';
                remove.addEventListener('click', async () => {
                    try {
                        await api.deleteJson(contentApi + 'media/' + encodeURIComponent(file.name));
                        item.remove();
                    } catch (err) {
                        item.appendChild(element('small', 'kroom-admin-error', said(err)));
                    }
                });
            }
        },

        async plugins(body) {
            const plugins = await api.getJson(siteApi + 'plugins');
            if (!plugins.length) return body.appendChild(element('p', 'kroom-admin-muted', t('noPlugin')));
            for (const plugin of plugins) {
                const details = body.appendChild(element('details', 'kroom-admin-plugin'));
                details.appendChild(element('summary', null, plugin.name));
                if (plugin.description) details.appendChild(element('p', 'kroom-admin-muted', plugin.description));
                if (plugin.settings.length) details.appendChild(settingsForm(plugin));
            }
        },

        async roles(body) {
            const answer = await api.getJson(siteApi + 'roles');
            body.appendChild(element('p', 'kroom-admin-muted', t('yours', { roles: answer.yours.join(', ') })));
            const list = body.appendChild(element('dl', 'kroom-admin-roles'));
            for (const [role, grants] of Object.entries(answer.roles)) {
                list.appendChild(element('dt', null, role));
                list.appendChild(element('dd', null, grants.join(' · ')));
            }
        }
    };

    function settingsForm(plugin) {
        const form = element('form', 'kroom-admin-settings');
        for (const setting of plugin.settings) {
            const label = form.appendChild(element('label'));
            let input;
            if (setting.type === 'boolean') {
                input = element('input');
                input.type = 'checkbox';
                input.checked = setting.value === 'true';
                label.appendChild(input);
                label.appendChild(document.createTextNode(' ' + setting.label));
            } else if (setting.type === 'choice') {
                label.appendChild(document.createTextNode(setting.label));
                input = element('select');
                setting.choices.forEach(choice => {
                    const option = input.appendChild(element('option', null, choice));
                    option.value = choice;
                });
                input.value = setting.value ?? '';
                label.appendChild(input);
            } else {
                label.appendChild(document.createTextNode(setting.label));
                input = element(setting.type === 'textarea' ? 'textarea' : 'input');
                if (setting.type === 'number') input.type = 'number';
                if (setting.type === 'secret') {
                    input.type = 'password';
                    input.autocomplete = 'off';
                    input.placeholder = t(setting.set ? 'secretSet' : 'secretUnset');
                } else {
                    input.value = setting.value ?? '';
                }
                label.appendChild(input);
            }
            input.name = setting.key;
            if (setting.help) label.appendChild(element('small', 'kroom-admin-muted', setting.help));
        }
        const footer = form.appendChild(element('footer'));
        const save = footer.appendChild(element('button', null, t('save')));
        save.type = 'submit';
        const status = footer.appendChild(element('small', 'kroom-admin-muted'));
        form.addEventListener('submit', async (e) => {
            e.preventDefault();
            const values = {};
            for (const setting of plugin.settings) {
                const input = form.elements[setting.key];
                values[setting.key] = setting.type === 'boolean' ? String(input.checked) : input.value;
            }
            try {
                await api.putJson(siteApi + `plugins/${encodeURIComponent(plugin.id)}/settings`, values);
                status.textContent = t('saved');
            } catch (err) {
                status.textContent = said(err);
            }
        });
        return form;
    }

    /** Another application's page, in the panel: whatever it shows, it shows on its own origin. */
    async function frame(url, title, body) {
        const iframe = body.appendChild(element('iframe', 'kroom-admin-frame'));
        iframe.src = url;
        iframe.title = title;
    }

    /** A plugin's panel: {columns: [..], rows: [[..], ..]} — cells are text, never html. */
    async function table(url, body) {
        const data = await api.getJson(url);
        if (!data.rows?.length) return body.appendChild(element('p', 'kroom-admin-muted', t('empty')));
        const tbl = body.appendChild(element('table', 'kroom-admin-table'));
        const head = tbl.appendChild(element('thead')).appendChild(element('tr'));
        data.columns.forEach(c => head.appendChild(element('th', null, c)));
        const rows = tbl.appendChild(element('tbody'));
        for (const row of data.rows) {
            const tr = rows.appendChild(element('tr'));
            row.forEach(cell => tr.appendChild(element('td', null, cell == null ? '' : String(cell))));
        }
    }
})();
