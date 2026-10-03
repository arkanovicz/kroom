// What admin.js does to the page, checked against a fake DOM and a fake server — see smoke.mjs.

import { JSDOM } from 'jsdom';
import { readFileSync } from 'node:fs';

const ASSETS = '../../../../kroom-webapp-assets/src/main/resources/static/js';
const OWN = '../../main/resources/static';

let failures = 0;
const check = (what, got, want) => {
    const ok = JSON.stringify(got) === JSON.stringify(want);
    if (!ok) failures++;
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}${ok ? '' : `\n     got  ${JSON.stringify(got)}\n     want ${JSON.stringify(want)}`}`);
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const entries = JSON.stringify([
    { id: 'site', label: 'site', builtin: true, tables: [{ id: 'rules', label: 'Redirects', url: '/api/site/rules' }, { id: 'missing', label: 'Not found', url: '/api/site/missing' }] },
    { id: 'pages', label: 'pages', builtin: true }, { id: 'journal', label: 'journal', builtin: true },
    { id: 'media', label: 'media', builtin: true },
    { id: 'themes', label: 'themes', builtin: true },
    { id: 'plugins', label: 'plugins', builtin: true }, { id: 'roles', label: 'roles', builtin: true },
    { id: 'forms', label: 'Forms', tables: [{ id: 'entries', label: 'Entries', url: '/api/forms/entries' }], builtin: false },
    { id: 'web', label: 'Webmaster', builtin: false, tables: [{ id: 'redirects', label: 'Redirects', url: '/api/web/redirects' },
                                                          { id: 'missing', label: 'Not found', url: '/api/web/missing' }] },
    { id: 'inbox', label: 'Mailbox', frame: '/mail/inbox', builtin: false }
]).replace(/"/g, '&quot;');

const answers = {
    '/api/site/settings': [
        { key: 'name', label: 'Name', type: 'text', value: 'kroom', group: 'Site' },
        { key: 'smtpPassword', label: 'Password', type: 'secret', set: false, group: 'Mail' }],
    '/api/site/rules': { columns: ['from', 'to', 'status', 'hits'], rows: [['/old', '/new', 301, 2]] },
    '/api/site/missing': { columns: ['uri', 'count', 'last'], rows: [['/gone', 3, '2026-09-30T00:00:00Z']] },
    // every kind of page: an editor's (published, a draft, a draft with a page under it), a developer's, an instance
    // of a placeholder template, a section no page answers
    '/api/site/menu': { stored: true, lang: 'en', languages: ['en', 'fr'], layouts: ['default', 'article', 'sidebar', 'landing'], items: [
        { slug: 'about', label: { en: 'About', fr: 'À propos' }, description: { en: 'Who we are' }, path: '/about', kind: 'authored',
          status: 'published', layout: 'article', movable: true, resolved: true, children: [] },
        { slug: 'contact', label: { en: 'Contact' }, path: '/contact', kind: 'template', resolved: true, children: [] },
        { slug: 'club', label: { en: 'Clubs' }, path: '/club', kind: 'template', resolved: true, children: [
            { slug: '13Ma', label: { en: 'Les Vagabonds' }, path: '/club/13Ma', kind: 'instance', resolved: true, children: [] }] },
        { slug: 'legal', label: { en: 'Legal' }, path: '/legal', kind: 'section', resolved: false, children: [
            { slug: 'terms', label: { en: 'Terms', fr: 'Mentions' }, path: '/legal/terms', kind: 'template', resolved: true, children: [] }] },
        { slug: 'company', label: { en: 'Company' }, path: '/company', kind: 'authored', status: 'draft', layout: 'default', movable: false, resolved: true, children: [
            { slug: 'history', label: { en: 'History' }, path: '/company/history', kind: 'authored', status: 'draft', layout: 'default', movable: true, resolved: true, children: [] }] }] },
    '/api/content/journal?limit=100': [{ rev: 'a', path: 'pages/club/13Ma/description.md', author: 'admin', time: 0 }],
    '/api/site/plugins': [{ id: 'basic', name: 'Basic', description: 'pico', enabled: true, theme: true, settings: [] },
                          { id: 'seo', name: 'SEO', description: 'meta', enabled: true, settings: [
        { key: 'title', label: 'Title', type: 'text', value: 'Club', group: 'Search engines' },
        { key: 'index', label: 'Indexed', type: 'boolean', value: 'true', group: 'Search engines' },
        { key: 'key', label: 'Key', type: 'secret', set: true },
        { key: 'mode', label: 'Mode', type: 'choice', choices: ['starttls', 'tls', 'none'], value: 'tls' }] }],
    '/api/content/media': [{ name: 'a-photo.png', url: '/media/a-photo.png', type: 'image/png', size: 2048, time: 0 },
                           { name: 'b-doc.pdf', url: '/media/b-doc.pdf', type: 'application/pdf', size: 10, time: 0 }],
    '/api/site/themes': [{ id: 'basic', name: 'Basic', description: 'pico', layouts: ['default', 'sidebar'], active: true },
                         { id: 'editorial', name: 'Editorial', description: '', layouts: ['default'], active: false }],
    '/api/site/roles': { roles: { admin: ['*'], editor: ['content.*'] }, yours: ['admin'] },
    '/api/web/redirects': { columns: ['from', 'to'], rows: [['/old', '/new']] },
    '/api/web/missing': { columns: ['uri', 'count'], rows: [['/gone', 3]] },
    '/api/forms/entries': { columns: ['name', 'message'], rows: [['Alice', '<b>hi</b>']] }
};

const calls = [];
/** A page with the bar, admin.js loaded after `preset` as a browser loads them: one shared scope. */
function bar(preset) {
    const { window } = new JSDOM(`<!doctype html><html><body><main>page</main>
        <aside class="kroom-admin" data-api="/api/site" data-content-api="/api/content" data-entries="${entries}"></aside>
        </body></html>`, { runScripts: 'outside-only', url: 'http://localhost/club/13Ma' });
    window.Response = Response;
    window.fetch = async (url, options = {}) => {
        calls.push({ url, method: options.method || 'GET', body: options.body && JSON.parse(options.body) });
        return new Response(JSON.stringify(answers[url] ?? {}), { status: 200, headers: { 'content-type': 'application/json' } });
    };
    // jsdom drags nothing: a stand-in for Sortable records each list made draggable, for a test to play a drop
    window.sortables = [];
    window.Sortable = function (el, options) { window.sortables.push({ el, options }); };
    window.eval(['domhelper.js', 'api.js'].map(f => readFileSync(`${ASSETS}/${f}`, 'utf8'))
        .concat(preset, readFileSync(`${OWN}/js/admin.js`, 'utf8')).join('\n;\n'));
    return window;
}
// what `$site.foot()` emits ahead of admin.js when the application says it in French
const words = `Object.assign(((window.kroomAdmin ??= {}).strings ??= {}), { pages: 'pages du site', 'forms': 'Messages reçus', 'seo.title': 'Titre' });`;
const window = bar(words);
const $ = (s) => window.document.querySelector(s);
const $$ = (s) => [...window.document.querySelectorAll(s)];
const click = (el) => el.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
const entry = (id) => $(`.kroom-admin nav [data-entry="${id}"]`);

check('one control per entry', $$('.kroom-admin nav > *').map(e => e.dataset.entry), ['site', 'pages', 'journal', 'media', 'themes', 'plugins', 'roles', 'forms', 'web', 'inbox']);
check('builtins get a pictogram, a plugin without one the initial of its (translated) label', [!!entry('pages').querySelector('svg'), entry('forms').textContent], [true, 'M']);
check('the panel starts closed', $('.kroom-admin-panel').hidden, true);
check("kroom's entries keep kroom's words, a plugin's take the application's", [entry('pages').title, entry('forms').title], ['pages', 'Messages reçus']);

click(entry('pages'));
await sleep(20);
// the pages panel, which is the menu
const sent = () => calls.findLast(c => c.method !== 'GET');
const lines = () => $$('.kroom-admin-menu-entry');
const line = (slug) => lines().find(li => li._item.slug === slug);
const list = (slug) => slug ? line(slug).querySelector(':scope > ul') : $('.kroom-admin-menu-root');
const sortable = (ul) => window.sortables.find(s => s.el === ul).options;
const rows = () => lines().map(li => li.querySelector(':scope > details > summary').firstChild.textContent);
/** What a stored tree says, slugs only: `legal(terms)`. */
const outline = (items) => items.map(i => i.children.length ? `${i.slug}(${outline(i.children).join(' ')})` : i.slug);
const lastMenu = () => calls.findLast(c => c.method === 'PUT' && c.url === '/api/site/menu')?.body;

check('pages: one line per page, its label as plain text, the pages under it included', rows(),
    ['About', 'Contact', 'Clubs', 'Les Vagabonds', 'Legal', 'Terms', 'Company', 'History']);
check('nothing but a handle and a label on the line', lines().every(li => [...li.children].map(c => c.tagName).join() === 'SPAN,DETAILS,UL'
    && li.querySelector(':scope > .kroom-admin-handle') && !li.querySelector(':scope > details > summary :is(input, select, button, a)')), true);
check('a draft says so', lines().filter(li => li.querySelector(':scope > details > summary small')).map(li => [li._item.slug,
    li.querySelector(':scope > details > summary small').textContent]), [['company', ' draft'], ['history', ' draft']]);
check('a line says its kind; only an editor\'s page with nothing under it is free to move', lines().map(li =>
    [li._item.slug, [...li.classList].filter(c => c !== 'kroom-admin-menu-entry').join(' ')]), [
    ['about', 'kroom-admin-kind-authored'], ['contact', 'kroom-admin-kind-template kroom-admin-fixed'], ['club', 'kroom-admin-kind-template kroom-admin-fixed'],
    ['13Ma', 'kroom-admin-kind-instance kroom-admin-fixed'], ['legal', 'kroom-admin-kind-section kroom-admin-fixed'],
    ['terms', 'kroom-admin-kind-template kroom-admin-fixed'], ['company', 'kroom-admin-kind-authored kroom-admin-fixed'], ['history', 'kroom-admin-kind-authored']]);
check('the arrangement is said to be the editors\', and can be forgotten', [$('.kroom-admin-menu-head small').textContent,
    $('.kroom-admin-menu-reset')?.textContent], ['as you arranged them', 'forget this arrangement']);
check('every level is a place to drag into, a page\'s empty list included', [window.sortables.length,
    window.sortables.every(s => s.options.handle === '.kroom-admin-handle' && s.options.group === 'kroom-menu')], [9, true]);

// where a page may go: Sortable asks onMove at each list it hovers
{
    const move = (slug, to) => {
        const [from, original] = [line(slug).parentElement, { dataTransfer: {} }];
        return [sortable(from).onMove({ from, to, dragged: line(slug) }, original), original.dataTransfer.dropEffect];
    };
    check('any page is reordered within its list', [move('contact', list()), move('terms', list('legal'))], [[true, undefined], [true, undefined]]);
    check('a developer\'s page, or one with pages under it, does not leave its list: the list hovered says no, the cursor too',
        [move('contact', list('about')), move('13Ma', list()), move('company', list('about')), list('about').classList.contains('kroom-admin-nodrop')],
        [[false, 'none'], [false, 'none'], [false, 'none'], true]);
    check('an editor\'s page with nothing under it goes under any page, and the refusal is lifted',
        [move('history', list()), move('about', list('contact')), $$('.kroom-admin-nodrop').length], [[true, undefined], [true, undefined], 0]);
}

// a drop: Sortable has moved the node, the list it left reads the tree back from the DOM
{
    let before = calls.length;
    sortable(list()).onEnd({ from: list(), to: list(), item: line('about'), oldIndex: 0, newIndex: 0 });
    await sleep(20);
    check('a drop where it was asks nothing', calls.length, before);
    list().insertBefore(line('contact'), line('about'));
    sortable(list()).onEnd({ from: list(), to: list(), item: line('contact'), oldIndex: 1, newIndex: 0 });
    await sleep(20);
    check('a reorder puts the whole tree as it now stands, and nothing moves', [calls.slice(before).filter(c => c.method !== 'GET').map(c => `${c.method} ${c.url}`),
        outline(lastMenu().items)], [['PUT /api/site/menu'], ['contact', 'about', 'club(13Ma)', 'legal(terms)', 'company(history)']]);
    check('the tree put is stripped of what the server computes', lastMenu().items.slice(0, 2), [
        { slug: 'contact', label: { en: 'Contact' }, description: {}, children: [] },
        { slug: 'about', label: { en: 'About', fr: 'À propos' }, description: { en: 'Who we are' }, children: [] }]);
    check('the tree shown is the server\'s again', rows()[0], 'About');

    before = calls.length;
    const [from, to, history] = [list('company'), list('about'), line('history')];
    to.appendChild(history);
    sortable(from).onEnd({ from, to, item: history, oldIndex: 0, newIndex: 0 });
    await sleep(20);
    const moved = calls.slice(before).filter(c => c.method !== 'GET');
    check('a drop under another page moves it there first, then puts the tree', [moved.map(c => `${c.method} ${c.url}`), moved[0]?.body, outline(moved[1]?.body.items ?? [])],
        [['POST /api/site/pages/move', 'PUT /api/site/menu'], { from: '/company/history', to: '/about/history' },
         ['about(history)', 'contact', 'club(13Ma)', 'legal(terms)', 'company']]);
}

// the accordion: label and description in the language chosen, what the page is, and an editor's page's own
$('.kroom-admin-menu-head select').value = 'fr';
$('.kroom-admin-menu-head select').dispatchEvent(new window.Event('change'));
check('switching the language shows its labels, the default\'s where it has none', rows(),
    ['À propos', 'Contact', 'Clubs', 'Les Vagabonds', 'Legal', 'Mentions', 'Company', 'History']);
{
    const fields = (slug) => [...line(slug).querySelectorAll(':scope > details > .kroom-admin-menu-fields input')];
    check('the fields hold that language\'s words', [fields('about').map(i => i.value), fields('contact').map(i => i.value)], [['À propos', ''], ['', '']]);
    const [label] = fields('contact');
    label.value = ' Nous écrire ';
    label.dispatchEvent(new window.Event('change'));
    await sleep(20);
    check('a label changed is stored in that language, beside the others, with the tree', [sent().url, lastMenu().items[1]],
        ['/api/site/menu', { slug: 'contact', label: { en: 'Contact', fr: 'Nous écrire' }, description: {}, children: [] }]);
}
check('each page links to itself, a developer\'s says whose it is', ['about', 'contact', '13Ma'].map(slug =>
    line(slug).querySelector(':scope > details .kroom-admin-menu-page').textContent), ['/about ↗', '/contact ↗ — a developer\'s page', '/club/13Ma ↗ — a developer\'s page']);
check('a section offers to create its page', line('legal').querySelector(':scope > details .kroom-admin-menu-page').textContent,
    '/legal — no page here create this page');
click(line('legal').querySelector(':scope > details .kroom-admin-menu-page button'));
await sleep(20);
check('creating it posts its path and its words', sent(), { url: '/api/site/pages', method: 'POST', body: { path: '/legal', label: { en: 'Legal' } } });
check('only an editor\'s page has a layout, a publishing switch and a delete', lines().filter(li => li.querySelector(':scope > details select')).map(li => li._item.slug),
    ['about', 'company', 'history']);
{
    const layout = line('about').querySelector(':scope > details select');
    check('the layouts offered are the skeleton\'s, on the page\'s', [[...layout.options].map(o => o.value), layout.value],
        [['default', 'article', 'sidebar', 'landing'], 'article']);
    layout.value = 'sidebar';
    layout.dispatchEvent(new window.Event('change'));
    await sleep(20);
    check('another layout is put on the page', sent(), { url: '/api/site/pages/about', method: 'PUT', body: { layout: 'sidebar' } });
}
const buttons = (slug) => [...line(slug).querySelectorAll(':scope > details .kroom-admin-actions button')];
check('a published page offers to unpublish, a draft to publish', [buttons('about').map(b => b.textContent), buttons('company')[0].textContent],
    [['unpublish', 'delete this page'], 'publish']);
click(buttons('about')[0]);
await sleep(20);
check('unpublishing puts it back to a draft', sent(), { url: '/api/site/pages/about', method: 'PUT', body: { status: 'draft' } });
click(buttons('company')[0]);
await sleep(20);
check('publishing puts the status', sent(), { url: '/api/site/pages/company', method: 'PUT', body: { status: 'published' } });
{
    const before = calls.length;
    window.confirm = () => false;
    click(buttons('history')[1]);
    await sleep(20);
    check('a delete not confirmed asks nothing', calls.length, before);
    window.confirm = () => true;
    click(buttons('history')[1]);
    await sleep(20);
    check('a delete confirmed deletes the page', sent(), { url: '/api/site/pages/company/history', method: 'DELETE', body: undefined });
}

// a new page: its name, the segment following it until typed over
{
    const form = $('.kroom-admin-new-page');
    const type = (input, text) => { input.value = text; input.dispatchEvent(new window.Event('input')); };
    type(form.elements.label, 'Notre Société !');
    check('the segment is derived from the name', form.elements.slug.value, 'notre-societe');
    type(form.elements.slug, 'societe');
    type(form.elements.label, 'Notre Société');
    check('typed over, it stays as typed', form.elements.slug.value, 'societe');
    form.dispatchEvent(new window.Event('submit', { cancelable: true }));
    await sleep(20);
    check('a new page posts its path and its name in the language chosen', sent(),
        { url: '/api/site/pages', method: 'POST', body: { path: '/societe', label: { fr: 'Notre Société' } } });
}
click($('.kroom-admin-menu-reset'));
await sleep(20);
check('forgetting the arrangement deletes it', sent(), { url: '/api/site/menu', method: 'DELETE', body: undefined });
check('the open entry is pressed', entry('pages').getAttribute('aria-pressed'), 'true');

click(entry('pages'));
check('clicking it again closes', $('.kroom-admin-panel').hidden, true);

click(entry('journal'));
await sleep(20);
check('journal: one revision', $('.kroom-admin-body li div')?.textContent, 'pages/club/13Ma/description.md');

click(entry('media'));
await sleep(20);
check('media: pictures as thumbnails, the rest by name', [$$('.kroom-admin-media img').map(i => i.getAttribute('src')),
    $$('.kroom-admin-media a')[1].textContent], [['/media/a-photo.png'], 'b-doc.pdf']);
click($('.kroom-admin-remove'));
await sleep(20);
check('delete asks the server, and the file leaves the grid', [calls.at(-1).method, calls.at(-1).url, $$('.kroom-admin-media li').length],
    ['DELETE', '/api/content/media/a-photo.png', 1]);

click(entry('themes'));
await sleep(20);
check('themes: each with a preview of this page, the one in use says so', [$$('.kroom-admin-actions a').map(a => a.getAttribute('href')),
    $$('.kroom-admin-actions button').length], [['/club/13Ma?theme=basic', '/club/13Ma?theme=editorial'], 1]);
click($('.kroom-admin-actions button'));
await sleep(20);
check('using one puts it', [calls.at(-1).method, calls.at(-1).url, calls.at(-1).body], ['PUT', '/api/site/theme', { id: 'editorial' }]);

click(entry('plugins'));
await sleep(20);
check('a plugin has its switch in the summary, a theme none', $$('.kroom-admin-plugin summary').map(s => !!s.querySelector('input[role="switch"]')), [false, true]);
const toggle = $('.kroom-admin-plugin:nth-child(2) summary input');
toggle.checked = false;
toggle.dispatchEvent(new window.Event('change'));
await sleep(20);
check('flipping it puts the state, and says it quietly', [calls.at(-1), $('.kroom-admin-plugin:nth-child(2)').classList.contains('kroom-admin-off')],
    [{ url: '/api/site/plugins/seo/enabled', method: 'PUT', body: { enabled: false } }, true]);
const form = $('.kroom-admin-settings');
check('a secret is never filled in', form.elements.key.value, '');
check("a plugin's setting in the application's words", form.elements.title.closest('label').firstChild.textContent, 'Titre');
check('a boolean is a checkbox', form.elements.index.checked, true);
check('a group is a fieldset under its name, the rest outside', [$$('.kroom-admin-settings legend').map(l => l.textContent),
    $('.kroom-admin-settings fieldset').querySelectorAll('label').length], [['Search engines'], 2]);
check('a choice is a select, on its value', [form.elements.mode.tagName, form.elements.mode.value], ['SELECT', 'tls']);
form.elements.title.value = 'Les Vagabonds';
form.dispatchEvent(new window.Event('submit', { cancelable: true }));
await sleep(20);
check('save puts every setting, a secret left empty', calls.at(-1),
    { url: '/api/site/plugins/seo/settings', method: 'PUT', body: { title: 'Les Vagabonds', index: 'true', key: '', mode: 'tls' } });

click(entry('site'));
await sleep(20);
const siteForm = $('.kroom-admin-settings');
check("the site's settings, kroom's words for kroom's groups and labels", [$$('.kroom-admin-settings legend').map(l => l.textContent),
    siteForm.elements.name.closest('label').firstChild.textContent, siteForm.elements.smtpPassword.value], [['Site', 'Mail'], 'Name', '']);
siteForm.elements.name.value = 'Les Vagabonds';
siteForm.dispatchEvent(new window.Event('submit', { cancelable: true }));
await sleep(20);
check('saved to the site, not to a plugin', calls.at(-1), { url: '/api/site/settings', method: 'PUT', body: { name: 'Les Vagabonds', smtpPassword: '' } });
check('the traffic tables under the form, titled in kroom\'s words', $$('.kroom-admin-section h4').map(h => h.textContent), ['Redirects', 'Not found']);

click(entry('roles'));
await sleep(20);
check('roles: the table', $$('.kroom-admin-roles dt').map(d => d.textContent), ['admin', 'editor']);

click(entry('forms'));
await sleep(20);
check('a plugin table, its cells as text', $$('.kroom-admin-table td').map(td => td.innerHTML), ['Alice', '&lt;b&gt;hi&lt;/b&gt;']);

click(entry('web'));
await sleep(20);
check('several tables, each under its label', [$$('.kroom-admin-section h4').map(h => h.textContent), $$('.kroom-admin-table').length],
    [['Redirects', 'Not found'], 2]);

click(entry('inbox'));
await sleep(20);
check('a framed entry: its page in a wide panel', [$('.kroom-admin-frame')?.getAttribute('src'),
    $('.kroom-admin-panel').classList.contains('kroom-admin-wide')], ['/mail/inbox', true]);

window.document.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape' }));
check('Escape closes', $('.kroom-admin-panel').hidden, true);

// --- the stocked languages
{
    const stock = window.kroomAdmin.stock;
    check('French says every word English does', Object.keys(stock.fr).sort(), Object.keys(stock.en).sort());
    const french = bar(`window.kroomAdmin = { language: 'fr' };`);
    const roles = french.document.querySelector('.kroom-admin nav [data-entry="roles"]');
    check('an application asking for French gets its bar in French', [roles?.title, roles?.getAttribute('aria-label'), french.kroomAdmin.language],
        ['rôles', 'rôles', 'fr']);
}

console.log(failures ? `\n${failures} FAILED` : '\nall ok');
process.exit(failures ? 1 : 0);
