// admin.js - the admin bar, to the left of every page an administrator visits
// Part of kroom-webapp-authoring. Needs api.js. Emitted by `$site.foot()` for Permissions.ADMIN only.
//
// The server hands the entries as data (kroom's own, then the plugins'); every piece of markup is built
// here. A panel opens beside the bar: pages, journal, media, plugins and their settings, roles — or a
// plugin's tables (APIs answering {columns, rows}), or another application, framed.

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
        site: 'site', pages: 'pages', menu: 'menu', journal: 'journal', media: 'media', plugins: 'plugins', themes: 'themes', roles: 'roles', close: 'close',
        // the menu editor
        menuDerived: 'derived from the pages — save to keep a menu of your own', menuStored: 'the menu as you saved it',
        menuLabel: 'label', menuDescription: 'description', menuUnresolved: 'no page here yet',
        menuUp: 'up', menuDown: 'down', menuOut: 'out of its section', menuIn: 'under the entry above', menuRemove: 'remove',
        menuAddPage: 'add a page', menuAddLink: 'add a link', menuSlug: 'page segment, e.g. company', menuUrl: 'address, https://…',
        menuReset: 'back to the pages',
        // the site's own settings: their labels come from the server in English, said here in each language
        'site.Site': 'Site', 'site.Mail': 'Mail', 'site.Redirects': 'Redirects', 'site.rules': 'Redirects', 'site.missing': 'Not found',
        'site.name': 'Name', 'site.lang': 'Language', 'site.lang.help': 'The content\'s — html lang',
        'site.baseUrl': 'Public URL', 'site.baseUrl.help': 'https://example.org — absolute URLs need it',
        'site.description': 'Description', 'site.description.help': 'What the site is, in a sentence or two',
        'site.image': 'Picture', 'site.image.help': 'Absolute URL of the picture a shared link shows',
        'site.layout': 'Layout', 'site.layout.help': 'What a page gets when it names none', 'site.footer': 'Footer', 'site.footer.help': 'A line at the foot of every page',
        'site.smtpHost': 'SMTP host', 'site.smtpHost.help': 'Nothing is sent while empty', 'site.smtpPort': 'SMTP port',
        'site.smtpSecurity': 'Security', 'site.smtpUser': 'User', 'site.smtpPassword': 'Password',
        'site.mailFrom': 'Sender', 'site.mailFrom.help': 'Site <noreply@example.org>',
        'site.redirects': 'Rules', 'site.redirects.help': 'One per line: /from /to [301|302|307|308] — a trailing * on both sides, ?id={id}',
        activate: 'use it', active: 'in use', preview: 'preview', layouts: 'layouts: {layouts}',
        noMedia: 'nothing uploaded yet', remove: 'delete', size: '{kb} kB',
        noPages: 'no page template', instances: '{count} pages', noInstance: 'none written yet',
        noJournal: 'this content store keeps no history', revision: '{time} — {author}',
        unknownAuthor: 'unknown', noPlugin: 'no plugin installed', enabled: 'enabled', disabled: 'disabled', save: 'save', saved: 'saved',
        secretSet: 'set — leave empty to keep', secretUnset: 'not set',
        yourRoles: 'your roles: {roles}', empty: 'nothing yet', error: 'Error: {message}'
    }, fr: {
        site: 'site', pages: 'pages', menu: 'menu', journal: 'journal', media: 'médias', plugins: 'extensions', themes: 'thèmes', roles: 'rôles', close: 'fermer',
        // the menu editor
        menuDerived: 'déduit des pages — enregistrez pour garder un menu à vous', menuStored: 'le menu tel que vous l’avez enregistré',
        menuLabel: 'libellé', menuDescription: 'description', menuUnresolved: 'pas encore de page ici',
        menuUp: 'monter', menuDown: 'descendre', menuOut: 'sortir de sa rubrique', menuIn: 'sous l’entrée au-dessus', menuRemove: 'retirer',
        menuAddPage: 'ajouter une page', menuAddLink: 'ajouter un lien', menuSlug: 'segment de la page, p. ex. societe', menuUrl: 'adresse, https://…',
        menuReset: 'revenir aux pages',
        // the site's own settings: their labels come from the server in English, said here in each language
        'site.Site': 'Site', 'site.Mail': 'Courrier', 'site.Redirects': 'Redirections', 'site.rules': 'Redirections', 'site.missing': 'Introuvables',
        'site.name': 'Nom', 'site.lang': 'Langue', 'site.lang.help': 'Celle du contenu — html lang',
        'site.baseUrl': 'URL publique', 'site.baseUrl.help': 'https://exemple.org — pour les URL absolues',
        'site.description': 'Description', 'site.description.help': 'Ce qu’est le site, en une phrase ou deux',
        'site.image': 'Image', 'site.image.help': 'URL absolue de l’image qu’un lien partagé montre',
        'site.layout': 'Mise en page', 'site.layout.help': 'Celle d’une page qui n’en nomme aucune', 'site.footer': 'Pied de page', 'site.footer.help': 'Une ligne au bas de chaque page',
        'site.smtpHost': 'Hôte SMTP', 'site.smtpHost.help': 'Rien n’est envoyé tant que vide', 'site.smtpPort': 'Port SMTP',
        'site.smtpSecurity': 'Sécurité', 'site.smtpUser': 'Utilisateur', 'site.smtpPassword': 'Mot de passe',
        'site.mailFrom': 'Expéditeur', 'site.mailFrom.help': 'Site <noreply@exemple.org>',
        'site.redirects': 'Règles', 'site.redirects.help': 'Une par ligne\u00a0: /de /vers [301|302|307|308] — * final des deux côtés, ?id={id}',
        activate: 'l’utiliser', active: 'utilisé', preview: 'aperçu', layouts: 'mises en page : {layouts}',
        noMedia: 'rien d’envoyé pour l’instant', remove: 'supprimer', size: '{kb} ko',
        noPages: 'aucun modèle de page', instances: '{count} pages', noInstance: 'aucune écrite pour l’instant',
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
        menu: 'M4 7h16M4 12h10M4 17h16M18 11l3 3-3 3',
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

        // the menu: the stored tree, or the one derived from the pages, edited in one language at a time
        async menu(body) {
            const answer = await api.getJson(siteApi + 'menu');
            let items = answer.items, lang = answer.lang;
            const strip = (list) => list.map(i => ({ slug: i.slug, href: i.href, label: i.label || {}, description: i.description || {},
                                                     children: strip(i.children || []) }));
            const put = async () => {
                try {
                    await api.putJson(siteApi + 'menu', { items: strip(items) });
                    const fresh = await api.getJson(siteApi + 'menu');
                    items = fresh.items;
                    render();
                } catch (err) { body.appendChild(element('p', 'kroom-admin-error', said(err))); }
            };
            const render = () => {
                body.replaceChildren();
                const head = body.appendChild(element('div', 'kroom-admin-menu-head'));
                head.appendChild(element('p', 'kroom-admin-muted', t(answer.stored ? 'menuStored' : 'menuDerived')));
                if (answer.languages.length > 1) {
                    const pick = head.appendChild(element('select'));
                    for (const l of answer.languages) {
                        const option = pick.appendChild(element('option', null, l));
                        option.value = l;
                        option.selected = l === lang;
                    }
                    pick.addEventListener('change', () => { lang = pick.value; render(); });
                }
                body.appendChild(tree(items, null));
                const actions = body.appendChild(element('div', 'kroom-admin-actions'));
                const addPage = actions.appendChild(element('button', null, t('menuAddPage')));
                addPage.type = 'button';
                addPage.addEventListener('click', () => {
                    const slug = window.prompt(t('menuSlug'));
                    if (slug) { items.push({ slug, label: {}, description: {}, children: [] }); put(); }
                });
                const addLink = actions.appendChild(element('button', null, t('menuAddLink')));
                addLink.type = 'button';
                addLink.addEventListener('click', () => {
                    const href = window.prompt(t('menuUrl'));
                    if (href) { items.push({ href, label: { [lang]: href }, description: {}, children: [] }); put(); }
                });
                if (answer.stored) {
                    const reset = actions.appendChild(element('button', 'secondary', t('menuReset')));
                    reset.type = 'button';
                    reset.addEventListener('click', async () => {
                        await api.deleteJson(siteApi + 'menu');
                        Object.assign(answer, await api.getJson(siteApi + 'menu'));
                        items = answer.items;
                        render();
                    });
                }
            };
            // one list per level: words in the chosen language, the path, and the moves
            const tree = (list, parent) => {
                const ul = element('ul', 'kroom-admin-menu');
                list.forEach((item, i) => {
                    const li = ul.appendChild(element('li', item.resolved === false ? 'kroom-admin-unresolved' : null));
                    const row = li.appendChild(element('div', 'kroom-admin-menu-row'));
                    const label = row.appendChild(element('input'));
                    label.value = item.label?.[lang] || '';
                    label.placeholder = t('menuLabel');
                    label.addEventListener('change', () => { (item.label ??= {})[lang] = label.value; answer.stored = true; put(); });
                    const path = row.appendChild(element('code', null, item.href || item.path));
                    if (item.resolved === false) path.title = t('menuUnresolved');
                    const moves = row.appendChild(element('span', 'kroom-admin-menu-moves'));
                    const move = (name, enabled, act) => {
                        const b = moves.appendChild(element('button', 'outline secondary', { menuUp: '↑', menuDown: '↓', menuOut: '←', menuIn: '→', menuRemove: '✕' }[name]));
                        b.type = 'button'; b.title = t(name); b.setAttribute('aria-label', t(name)); b.disabled = !enabled;
                        b.addEventListener('click', () => { act(); answer.stored = true; put(); });
                    };
                    move('menuUp', i > 0, () => { list.splice(i, 1); list.splice(i - 1, 0, item); });
                    move('menuDown', i < list.length - 1, () => { list.splice(i, 1); list.splice(i + 1, 0, item); });
                    move('menuIn', i > 0 && !list[i - 1].href && !item.href, () => { list.splice(i, 1); (list[i - 1].children ??= []).push(item); });
                    move('menuOut', !!parent, () => { list.splice(i, 1); const up = parent.list; up.splice(up.indexOf(parent.item) + 1, 0, item); });
                    move('menuRemove', true, () => list.splice(i, 1));
                    const description = li.appendChild(element('input'));
                    description.value = item.description?.[lang] || '';
                    description.placeholder = t('menuDescription');
                    description.addEventListener('change', () => { (item.description ??= {})[lang] = description.value; answer.stored = true; put(); });
                    if (item.children?.length) li.appendChild(tree(item.children, { item, list }));
                });
                return ul;
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
