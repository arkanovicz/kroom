// admin.js - the admin bar, to the left of every page of whoever may open one of its entries
// Part of kroom-webapp-authoring. Needs api.js, and Sortable for dragging pages. Emitted by `$site.foot()`.
//
// The server hands the entries as data (kroom's own, then the plugins'); every piece of markup is built
// here. A panel opens beside the bar: the site's settings, the pages (which are the menu), journal, media,
// plugins and their settings, themes, roles — or a plugin's tables (APIs answering {columns, rows}), or
// another application, framed.

(function () {

    const bar = document.querySelector('aside.kroom-admin');
    if (!bar) return;

    // Every word the bar says, in the languages kroom ships; the application's choice of language
    // (installContentSite { language = … }) is emitted ahead of this script, as for the editor. What the bar
    // says of a PLUGIN is the plugin's, and the application's to translate: `installContentSite { strings[…] }`
    // reaches here as kroomAdmin.strings, keyed by the plugin's ids — `<entry id>` and `<entry id>.<table id>`,
    // `<plugin>.name`, `<plugin>.description`, `<plugin>.<setting>`, `<plugin>.<setting>.help`, `<plugin>.<group>`
    // — and defaults to what the plugin says. The stock words are not up for rewording.
    const STOCK = { en: {
        site: 'site', pages: 'pages', journal: 'journal', media: 'media', plugins: 'plugins', themes: 'themes', roles: 'roles', close: 'close',

        // the site's own settings: their labels come from the server in English, said here in each language
        'site.Site': 'Site', 'site.Mail': 'Mail', 'site.Redirects': 'Redirects', 'site.rules': 'Redirects', 'site.missing': 'Not found',
        'site.name': 'Name', 'site.lang': 'Language', 'site.lang.help': 'The content\'s — html lang',
        'site.baseUrl': 'Public URL', 'site.baseUrl.help': 'https://example.org — absolute URLs need it',
        'site.description': 'Description', 'site.description.help': 'What the site is, in a sentence or two',
        'site.image': 'Picture', 'site.image.help': 'Absolute URL of the picture a shared link shows',
        'site.layout': 'Layout', 'site.layout.help': 'What a page gets when it names none', 'site.footer': 'Footer', 'site.footer.help': 'A line at the foot of every page',
        'site.languages': 'Languages', 'site.languages.help': 'The others the site speaks, comma-separated (fr, de)',
        'site.menuDepth': 'Menu depth', 'site.menuDepth.help': 'How many levels of pages the header\'s menu shows',
        'site.menuPanels': 'Menu panels', 'site.menuPanels.help': 'The header shows a section\'s pages as a panel',
        'site.west': 'West region', 'site.west.help': 'What a page shows on its left when it says nothing: the section\'s pages, or nothing',
        'site.smtpHost': 'SMTP host', 'site.smtpHost.help': 'Nothing is sent while empty', 'site.smtpPort': 'SMTP port',
        'site.smtpSecurity': 'Security', 'site.smtpUser': 'User', 'site.smtpPassword': 'Password',
        'site.mailFrom': 'Sender', 'site.mailFrom.help': 'Site <noreply@example.org>',
        'site.redirects': 'Rules', 'site.redirects.help': 'One per line: /from /to [301|302|307|308] — a trailing * on both sides, ?id={id}',
        activate: 'use it', active: 'in use', preview: 'preview', layouts: 'layouts: {layouts}',
        noMedia: 'nothing uploaded yet', remove: 'delete', size: '{kb} kB',
        // the pages panel — which is the menu
        pagesDerived: 'in the order the pages come', pagesArranged: 'as you arranged them', pagesReset: 'forget this arrangement',
        pageDrag: 'drag to move', pageStays: 'it stays in its list: reordered, not moved', pageHasChildren: 'it has pages under it: it cannot move yet',
        pageLabel: 'label', pageDescription: 'description', pageLayout: 'layout', pageNone: 'no page here', pageCreate: 'create this page',
        pageSourced: 'a developer\'s page', pageDelete: 'delete this page', linkDelete: 'remove this link', pageNewLabel: 'new page or link: its name', pageNewSlug: 'segment / URL',
        publish: 'publish', unpublish: 'unpublish', draft: 'draft', published: 'published',
        confirmDeletePage: 'Delete this page? Its blocks stay in the store.',
        noJournal: 'this content store keeps no history', revision: '{time} — {author}',
        unknownAuthor: 'unknown', noPlugin: 'no plugin installed', enabled: 'enabled', disabled: 'disabled', save: 'save', saved: 'saved',
        secretSet: 'set — leave empty to keep', secretUnset: 'not set',
        yourRoles: 'your roles: {roles}', empty: 'nothing yet', error: 'Error: {message}'
    }, fr: {
        site: 'site', pages: 'pages', journal: 'journal', media: 'médias', plugins: 'extensions', themes: 'thèmes', roles: 'rôles', close: 'fermer',

        // the site's own settings: their labels come from the server in English, said here in each language
        'site.Site': 'Site', 'site.Mail': 'Courrier', 'site.Redirects': 'Redirections', 'site.rules': 'Redirections', 'site.missing': 'Introuvables',
        'site.name': 'Nom', 'site.lang': 'Langue', 'site.lang.help': 'Celle du contenu — html lang',
        'site.baseUrl': 'URL publique', 'site.baseUrl.help': 'https://exemple.org — pour les URL absolues',
        'site.description': 'Description', 'site.description.help': 'Ce qu’est le site, en une phrase ou deux',
        'site.image': 'Image', 'site.image.help': 'URL absolue de l’image qu’un lien partagé montre',
        'site.layout': 'Mise en page', 'site.layout.help': 'Celle d’une page qui n’en nomme aucune', 'site.footer': 'Pied de page', 'site.footer.help': 'Une ligne au bas de chaque page',
        'site.languages': 'Langues', 'site.languages.help': 'Les autres langues du site, séparées par des virgules (fr, de)',
        'site.menuDepth': 'Profondeur du menu', 'site.menuDepth.help': 'Combien de niveaux de pages le menu de l’en-tête montre',
        'site.menuPanels': 'Panneaux du menu', 'site.menuPanels.help': 'L’en-tête montre les pages d’une rubrique dans un panneau',
        'site.west': 'Région ouest', 'site.west.help': 'Ce qu’une page montre à gauche quand elle ne dit rien\u00a0: les pages de la rubrique, ou rien',
        'site.smtpHost': 'Hôte SMTP', 'site.smtpHost.help': 'Rien n’est envoyé tant que vide', 'site.smtpPort': 'Port SMTP',
        'site.smtpSecurity': 'Sécurité', 'site.smtpUser': 'Utilisateur', 'site.smtpPassword': 'Mot de passe',
        'site.mailFrom': 'Expéditeur', 'site.mailFrom.help': 'Site <noreply@exemple.org>',
        'site.redirects': 'Règles', 'site.redirects.help': 'Une par ligne\u00a0: /de /vers [301|302|307|308] — * final des deux côtés, ?id={id}',
        activate: 'l’utiliser', active: 'utilisé', preview: 'aperçu', layouts: 'mises en page : {layouts}',
        noMedia: 'rien d’envoyé pour l’instant', remove: 'supprimer', size: '{kb} ko',
        // the pages panel — which is the menu
        pagesDerived: 'dans l’ordre où viennent les pages', pagesArranged: 'telles que vous les avez arrangées', pagesReset: 'oublier cet arrangement',
        pageDrag: 'glisser pour déplacer', pageStays: 'elle reste dans sa liste\u00a0: on la réordonne, on ne la déplace pas', pageHasChildren: 'elle a des pages sous elle\u00a0: elle ne peut pas encore bouger',
        pageLabel: 'libellé', pageDescription: 'description', pageLayout: 'mise en page', pageNone: 'pas de page ici', pageCreate: 'créer cette page',
        pageSourced: 'page d’un développeur', pageDelete: 'supprimer cette page', linkDelete: 'retirer ce lien', pageNewLabel: 'nouvelle page ou lien\u00a0: son nom', pageNewSlug: 'segment / URL',
        publish: 'publier', unpublish: 'dépublier', draft: 'brouillon', published: 'publiée',
        confirmDeletePage: 'Supprimer cette page\u00a0? Ses blocs restent dans le stockage.',
        noJournal: 'ce stockage de contenu ne garde pas d’historique', revision: '{time} — {author}',
        unknownAuthor: 'inconnu', noPlugin: 'aucune extension installée', enabled: 'activée', disabled: 'désactivée', save: 'enregistrer', saved: 'enregistré',
        secretSet: 'défini — laisser vide pour le garder', secretUnset: 'non défini',
        yourRoles: 'vos rôles : {roles}', empty: 'rien pour l’instant', error: 'Erreur : {message}'
    } };

    /** The stock language asked for, or the browser's first one that is stocked; English otherwise. */
    function pickLanguage(stock, asked) {
        const wanted = asked && asked !== 'auto' ? [asked] : (navigator.languages || [navigator.language]);
        return wanted.map(l => String(l).toLowerCase().split('-')[0]).find(l => stock[l]) || 'en';
    }

    const language = pickLanguage(STOCK, window.kroomAdmin?.language);
    const strings = Object.assign({}, window.kroomAdmin?.strings, STOCK.en, STOCK[language]);

    // one stroked path each, on the editor's 24px grid
    const icons = Object.assign({
        pages: 'M7 3h7l5 5v13H7zM14 3v5h5M10 13h6M10 17h6',
        journal: 'M12 7v5l3 2M21 12a9 9 0 1 1-3-6.7M21 4v4h-4',
        media: 'M4 5h16v14H4zM4 16l5-5 4 4 3-3 4 4M15 9h.01',
        plugins: 'M9 3v4M15 3v4M7 7h10v5a5 5 0 0 1-10 0zM12 17v4',
        site: 'M3 11l9-7 9 7M5 10v10h14V10M10 20v-6h4v6',
        themes: 'M12 3a9 9 0 0 0 0 18c1.5 0 2-1 2-2s-.5-1.5-.5-2.5S14.5 15 16 15h2a3 3 0 0 0 3-3 9 9 0 0 0-9-9zM7.5 12h.01M9.5 7.5h.01M14.5 7.5h.01',
        roles: 'M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM2 21a7 7 0 0 1 14 0M17 8l2 2 4-4',
        close: 'M6 6l12 12M18 6L6 18'
    }, window.kroomAdmin?.icons);

    window.kroomAdmin = Object.assign(window.kroomAdmin || {}, { strings, icons, language, stock: STOCK });

    const t = (key, args = {}) => (strings[key] ?? key).replace(/\{(\w+)\}/g, (_, name) => args[name] ?? '');
    /** A word a plugin brought: the table's, when the application translated it. */
    const own = (key, fallback) => strings[key] ?? fallback;
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
        const label = entry.builtin ? t(entry.id) : own(entry.id, entry.label);
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
        const show = entry.builtin ? (into) => panels[entry.id](into, entry)
            : entry.frame ? (into) => frame(entry.frame, label, into)
            : (into) => tables(entry, into);
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
        // the pages, which are the menu: one compact tree, a line per page — a handle to drag it by (Sortable; the
        // ghost is the phantom row showing where it lands) and its label, which opens what there is to say of
        // it. A developer's page stays in its list (its handle greyed); an editor's moves, blocks and all.
        async pages(body) {
            let answer = await api.getJson(siteApi + 'menu');
            let lang = answer.lang;
            let opened = null;                          // the accordion left open, by path or address, across re-renders
            const strip = (list) => list.map(i => ({ slug: i.slug, href: i.href, label: i.label || {}, description: i.description || {}, children: strip(i.children || []) }));
            // an address elsewhere: anything with a scheme — what a segment can never be
            const LINK = /^[A-Za-z][A-Za-z0-9+.-]*:.+$/;
            const fail = (err) => body.appendChild(element('p', 'kroom-admin-error', said(err)));
            const refresh = async () => { answer = await api.getJson(siteApi + 'menu'); render(); };
            // their order and words; whatever happens, the tree shown is the server's
            const store = async (items) => {
                try { await api.putJson(siteApi + 'menu', { items: strip(items) }); await refresh(); }
                catch (err) { await refresh(); fail(err); }
            };
            const act = async (call) => { try { await call(); await refresh(); } catch (err) { fail(err); } };
            // the tree as the DOM now has it: what a drop made of it
            const read = (ul) => [...ul.children].filter(li => li._item).map(li => Object.assign({}, li._item, { children: read(li.querySelector(':scope > ul')) }));
            const slugify = (text) => text.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');

            const field = (into, name, item, key) => {
                const input = into.appendChild(element('input'));
                input.placeholder = input.title = t(name);
                input.value = item[key]?.[lang] || '';
                input.addEventListener('change', () => { (item[key] ??= {})[lang] = input.value.trim(); store(answer.items); });
                return input;
            };
            const action = (into, word, className, onClick) => {
                const button = into.appendChild(element('button', className, t(word)));
                button.type = 'button';
                button.addEventListener('click', onClick);
                return button;
            };

            const entry = (item) => {
                const authored = item.kind === 'authored';
                const li = element('li', `kroom-admin-menu-entry kroom-admin-kind-${item.kind}` + (item.movable ? '' : ' kroom-admin-fixed'));
                li._item = item;
                const handle = li.appendChild(element('span', 'kroom-admin-handle', '\u283f'));
                handle.title = t(item.movable ? 'pageDrag' : authored ? 'pageHasChildren' : 'pageStays');
                // one accordion open at a time (`name` makes the browser close the others), remembered across renders
                const details = li.appendChild(element('details'));
                details.name = 'kroom-admin-menu';
                const key = item.path ?? item.href;
                details.open = opened === key;
                details.addEventListener('toggle', () => { if (details.open) opened = key; else if (opened === key) opened = null; });
                const summary = details.appendChild(element('summary', null, item.label?.[lang] || item.label?.[answer.lang] || item.slug || item.href));
                if (item.status === 'draft') summary.appendChild(element('small', 'kroom-admin-muted', ' ' + t('draft')));
                const fields = details.appendChild(element('div', 'kroom-admin-menu-fields'));
                field(fields, 'pageLabel', item, 'label');
                field(fields, 'pageDescription', item, 'description');
                const page = fields.appendChild(element('p', 'kroom-admin-menu-page'));
                if (item.kind === 'link') {
                    page.appendChild(link(item.href, item.href + ' \u2197'));
                    // nothing behind a link: taking it out of the tree is the whole deletion
                    action(fields.appendChild(element('div', 'kroom-admin-actions')), 'linkDelete', 'secondary outline', () => { li.remove(); store(read(root)); });
                } else if (item.kind === 'section') {
                    page.appendChild(element('span', null, `${item.path} \u2014 ${t('pageNone')} `));
                    action(page, 'pageCreate', 'outline', () => act(() => api.postJson(siteApi + 'pages', { path: item.path, label: item.label || {} })));
                } else {
                    page.appendChild(link(item.path, item.path + ' \u2197'));
                    if (!authored) page.appendChild(element('small', 'kroom-admin-muted', ' \u2014 ' + t('pageSourced')));
                }
                if (authored) {
                    const layout = fields.appendChild(element('p', 'kroom-admin-menu-layout', t('pageLayout') + ' ')).appendChild(element('select'));
                    for (const l of answer.layouts) layout.appendChild(element('option', null, l)).value = l;
                    layout.value = item.layout;
                    layout.addEventListener('change', () => act(() => api.putJson(siteApi + 'pages' + item.path, { layout: layout.value })));
                    const actions = fields.appendChild(element('div', 'kroom-admin-actions'));
                    const out = item.status === 'published';
                    action(actions, out ? 'unpublish' : 'publish', null, () => act(() => api.putJson(siteApi + 'pages' + item.path, { status: out ? 'draft' : 'published' })));
                    action(actions, 'pageDelete', 'secondary outline', () => {
                        if (window.confirm(t('confirmDeletePage'))) act(() => api.deleteJson(siteApi + 'pages' + item.path));
                    });
                }
                li.appendChild(tree(item.children ??= [], item.path));
                return li;
            };

            // one list per level, each a place to drop into — a page's own, empty, is how it gets its first child
            const tree = (list, path) => {
                const ul = element('ul', 'kroom-admin-menu');
                ul._path = path;
                list.forEach(item => ul.appendChild(entry(item)));
                if (window.Sortable) new window.Sortable(ul, {
                    group: 'kroom-menu', handle: '.kroom-admin-handle', draggable: '> .kroom-admin-menu-entry',
                    animation: 150, fallbackOnBody: true, swapThreshold: 0.65, emptyInsertThreshold: 8,
                    ghostClass: 'kroom-admin-ghost',
                    // within its own list anything is reordered; only a page that may move leaves it
                    onMove: (ev, original) => {
                        const allowed = ev.to === ev.from || !!ev.dragged._item?.movable;
                        body.querySelectorAll('.kroom-admin-nodrop').forEach(el => el.classList.remove('kroom-admin-nodrop'));
                        if (!allowed) {
                            ev.to.classList.add('kroom-admin-nodrop');
                            if (original?.dataTransfer) original.dataTransfer.dropEffect = 'none';     // the forbidden cursor
                        }
                        return allowed;
                    },
                    onEnd: async (ev) => {
                        body.querySelectorAll('.kroom-admin-nodrop').forEach(el => el.classList.remove('kroom-admin-nodrop'));
                        if (ev.from === ev.to && ev.oldIndex === ev.newIndex) return;
                        if (ev.from !== ev.to && ev.item._item.path) {
                            // another parent is another place: the page moves there, blocks and past with it (a link just goes)
                            const item = ev.item._item;
                            try { await api.postJson(siteApi + 'pages/move', { from: item.path, to: `${ev.to._path}/${item.slug}` }); }
                            catch (err) { await refresh(); return fail(err); }
                        }
                        store(read(root));
                    }
                });
                return ul;
            };

            let root;
            const render = () => {
                body.replaceChildren();
                const head = body.appendChild(element('div', 'kroom-admin-menu-head'));
                head.appendChild(element('small', 'kroom-admin-muted', t(answer.stored ? 'pagesArranged' : 'pagesDerived')));
                if (answer.languages.length > 1) {
                    const pick = head.appendChild(element('select'));
                    for (const l of answer.languages) {
                        const option = pick.appendChild(element('option', null, l));
                        option.value = l;
                        option.selected = l === lang;
                    }
                    pick.addEventListener('change', () => { lang = pick.value; render(); });
                }
                root = body.appendChild(tree(answer.items, ''));
                root.classList.add('kroom-admin-menu-root');
                // a new page: what it is called, then where — the segment follows the name until it is typed over;
                // an address typed there instead (a scheme says so) makes a link, stored in the tree
                const form = body.appendChild(element('form', 'kroom-admin-new-page'));
                const name = form.appendChild(element('input'));
                name.name = 'label'; name.placeholder = t('pageNewLabel'); name.required = true;
                const slug = form.appendChild(element('input'));
                slug.name = 'slug'; slug.placeholder = t('pageNewSlug'); slug.required = true; slug.pattern = `[A-Za-z0-9_-]+|${LINK.source.slice(1, -1)}`;
                let typed = false;
                name.addEventListener('input', () => { if (!typed) slug.value = slugify(name.value); });
                slug.addEventListener('input', () => { typed = slug.value !== ''; });
                const create = form.appendChild(element('button', null, '+'));
                create.type = 'submit'; create.title = t('pageCreate'); create.setAttribute('aria-label', t('pageCreate'));
                form.addEventListener('submit', (e) => {
                    e.preventDefault();
                    const where = slug.value.trim(), label = { [lang]: name.value.trim() };
                    if (LINK.test(where)) store([...answer.items, { href: where, label }]);
                    else act(() => api.postJson(siteApi + 'pages', { path: '/' + where, label }));
                });
                if (answer.stored) action(body, 'pagesReset', 'kroom-admin-menu-reset', () => act(() => api.deleteJson(siteApi + 'menu')));
            };
            render();
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

        async site(body, entry) {
            const settings = await api.getJson(siteApi + 'settings');
            body.appendChild(settingsForm({ id: 'site', settings }, siteApi + 'settings'));
            await tables(entry, body);
        },

        async plugins(body) {
            const plugins = await api.getJson(siteApi + 'plugins');
            if (!plugins.length) return body.appendChild(element('p', 'kroom-admin-muted', t('noPlugin')));
            for (const plugin of plugins) {
                const details = body.appendChild(element('details', 'kroom-admin-plugin'));
                details.classList.toggle('kroom-admin-off', !plugin.enabled);
                const summary = details.appendChild(element('summary'));
                // a theme is switched on the themes panel; any other plugin here, live — clicking the switch
                // activates the switch, not the summary
                if (!plugin.theme) {
                    const toggle = summary.appendChild(element('input'));
                    toggle.type = 'checkbox';
                    toggle.setAttribute('role', 'switch');
                    toggle.checked = plugin.enabled;
                    toggle.title = t(plugin.enabled ? 'enabled' : 'disabled');
                    toggle.setAttribute('aria-label', toggle.title);
                    toggle.addEventListener('change', async () => {
                        try {
                            await api.putJson(siteApi + `plugins/${encodeURIComponent(plugin.id)}/enabled`, { enabled: toggle.checked });
                            details.classList.toggle('kroom-admin-off', !toggle.checked);
                            toggle.title = t(toggle.checked ? 'enabled' : 'disabled');
                        } catch (err) {
                            toggle.checked = !toggle.checked;
                            details.appendChild(element('small', 'kroom-admin-error', said(err)));
                        }
                    });
                }
                summary.appendChild(document.createTextNode(own(`${plugin.id}.name`, plugin.name)));
                const description = own(`${plugin.id}.description`, plugin.description);
                if (description) details.appendChild(element('p', 'kroom-admin-muted', description));
                if (plugin.settings.length) details.appendChild(settingsForm(plugin, siteApi + `plugins/${encodeURIComponent(plugin.id)}/settings`));
            }
        },

        async themes(body) {
            const themes = await api.getJson(siteApi + 'themes');
            const list = body.appendChild(element('ul', 'kroom-admin-list'));
            for (const theme of themes) {
                const item = list.appendChild(element('li'));
                item.appendChild(element('strong', null, own(`${theme.id}.name`, theme.name)));
                const description = own(`${theme.id}.description`, theme.description);
                if (description) item.appendChild(element('p', 'kroom-admin-muted', description));
                item.appendChild(element('small', 'kroom-admin-muted', t('layouts', { layouts: theme.layouts.join(', ') })));
                const actions = item.appendChild(element('div', 'kroom-admin-actions'));
                // this very page, dressed in it — for an admin only, nobody else's page changes
                const url = new URL(location.href);
                url.searchParams.set('theme', theme.id);
                actions.appendChild(link(url.pathname + url.search, t('preview')));
                if (theme.active) {
                    actions.appendChild(element('small', null, t('active')));
                } else {
                    const use = actions.appendChild(element('button', null, t('activate')));
                    use.type = 'button';
                    use.addEventListener('click', async () => {
                        try {
                            await api.putJson(siteApi + 'theme', { id: theme.id });
                            location.reload();
                        } catch (err) {
                            actions.appendChild(element('small', 'kroom-admin-error', said(err)));
                        }
                    });
                }
            }
        },

        async roles(body) {
            const answer = await api.getJson(siteApi + 'roles');
            body.appendChild(element('p', 'kroom-admin-muted', t('yourRoles', { roles: answer.yours.join(', ') })));
            const list = body.appendChild(element('dl', 'kroom-admin-roles'));
            for (const [role, grants] of Object.entries(answer.roles)) {
                list.appendChild(element('dt', null, role));
                list.appendChild(element('dd', null, grants.join(' · ')));
            }
        }
    };

    /** The settings of [plugin] (or of the site, as `{id: 'site', settings}`) as a form saving to [url]. */
    function settingsForm(plugin, url) {
        const form = element('form', 'kroom-admin-settings');
        let group = null, into = form;
        for (const declared of plugin.settings) {
            const setting = Object.assign({}, declared, {
                label: own(`${plugin.id}.${declared.key}`, declared.label),
                help: own(`${plugin.id}.${declared.key}.help`, declared.help)
            });
            // consecutive settings of one group share a fieldset, under the group's name
            if ((declared.group ?? null) !== group) {
                group = declared.group ?? null;
                into = group ? form.appendChild(element('fieldset')) : form;
                if (group) into.appendChild(element('legend', null, own(`${plugin.id}.${group}`, group)));
            }
            const label = into.appendChild(element('label'));
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
                await api.putJson(url, values);
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

    /** A plugin's tables, one under the other — each under its label when there are several. */
    async function tables(entry, body) {
        for (const tbl of entry.tables || []) {
            const section = body.appendChild(element('section', 'kroom-admin-section'));
            if (entry.tables.length > 1) section.appendChild(element('h4', null, own(`${entry.id}.${tbl.id}`, tbl.label)));
            await table(relative(tbl.url), section);
        }
    }

    /** One {columns: [..], rows: [[..], ..]} — cells are text, never html. */
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
