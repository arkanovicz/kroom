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

// kroom's own, then the plugins' that stand alone: an application framed, a link — tables alone are no entry
const entries = JSON.stringify([
    { id: 'site', label: 'site', builtin: true, tables: [{ id: 'rules', label: 'Redirects', url: '/api/site/rules' }, { id: 'missing', label: 'Not found', url: '/api/site/missing' }] },
    { id: 'pages', label: 'pages', builtin: true },
    { id: 'plugins', label: 'plugins', builtin: true },
    { id: 'themes', label: 'themes', builtin: true },
    { id: 'roles', label: 'roles', builtin: true },
    { id: 'inbox', label: 'Mailbox', frame: '/mail/inbox', builtin: false },
    { id: 'stats', label: 'Statistics', href: 'https://stats.example/', builtin: false }
]).replace(/"/g, '&quot;');

const answers = {
    '/api/site/settings': [
        { key: 'name', label: 'Name', type: 'text', value: 'kroom', group: 'General' },
        { key: 'description', label: 'Description', type: 'textarea', value: '', group: 'Sharing' },
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
            { slug: 'history', label: { en: 'History' }, path: '/company/history', kind: 'authored', status: 'draft', layout: 'default', movable: true, resolved: true, children: [] }] },
        { href: 'https://y.example/doc', label: { en: 'Docs' }, kind: 'link', movable: true, children: [] }] },
    // plugins only, themes have their panel; a plugin's tables come with it, and what switching it off would silence
    '/api/site/plugins': [
        { id: 'seo', name: 'SEO', description: 'meta', enabled: true, settings: [
            { key: 'title', label: 'Title', type: 'text', value: 'Club', group: 'Search engines' },
            { key: 'index', label: 'Indexed', type: 'boolean', value: 'true', group: 'Search engines' },
            { key: 'key', label: 'Key', type: 'secret', set: true },
            { key: 'mode', label: 'Mode', type: 'choice', choices: ['starttls', 'tls', 'none'], value: 'tls' }] },
        { id: 'forms', name: 'Forms', description: '', enabled: true, settings: [], holds: '2 forms, 5 entries',
          tables: [{ id: 'entries', label: 'Entries', url: '/api/forms/entries' }] },
        { id: 'web', name: 'Webmaster', description: '', enabled: false, settings: [],
          tables: [{ id: 'redirects', label: 'Redirects', url: '/api/web/redirects' }, { id: 'missing', label: 'Not found', url: '/api/web/missing' }] }],
    '/api/site/themes': [
        { id: 'basic', name: 'Basic', description: 'pico', layouts: ['default', 'sidebar'], active: true,
          settings: [{ key: 'accent', label: 'Accent', type: 'text', value: 'blue' }] },
        { id: 'editorial', name: 'Editorial', description: '', layouts: ['default'], active: false, settings: [] }],
    '/api/site/roles': { roles: { admin: ['*'], editor: ['content.*'] }, yours: ['admin'] },
    '/api/web/redirects': { columns: ['from', 'to'], rows: [['/old', '/new']] },
    '/api/web/missing': { columns: ['uri', 'count'], rows: [['/gone', 3]] },
    '/api/forms/entries': { columns: ['name', 'message'], rows: [['Alice', '<b>hi</b>']] }
};

const calls = [];
/**
 * A page with the bar, admin.js loaded after `preset` as a browser loads them: one shared scope. `kept` is the
 * tab's sessionStorage, carried over as a reload keeps it.
 */
function bar(preset, kept = {}) {
    const { window } = new JSDOM(`<!doctype html><html><body><main>page</main>
        <aside class="kroom-admin" data-api="/api/site" data-content-api="/api/content" data-entries="${entries}"></aside>
        </body></html>`, { runScripts: 'outside-only', url: 'http://localhost/club/13Ma' });
    for (const [key, value] of Object.entries(kept)) window.sessionStorage.setItem(key, value);
    window.Response = Response;
    window.fetch = async (url, options = {}) => {
        calls.push({ url, method: options.method || 'GET', body: options.body && JSON.parse(options.body) });
        return new Response(JSON.stringify(answers[url] ?? {}), { status: 200, headers: { 'content-type': 'application/json' } });
    };
    // jsdom drags nothing: a stand-in for Sortable records each list made draggable, for a test to play a drop
    window.sortables = [];
    window.Sortable = function (el, options) { window.sortables.push({ el, options }); };
    // jsdom navigates nowhere, and its location is unforgeable: admin.js gets one recording where it would go
    window.navigations = [];
    window.recordingLocation = {
        get href() { return window.location.href; }, set href(url) { window.navigations.push(url); },
        get pathname() { return window.location.pathname; }, reload() { window.navigations.push('reload'); }
    };
    window.eval(['domhelper.js', 'api.js'].map(f => readFileSync(`${ASSETS}/${f}`, 'utf8'))
        .concat(preset, `(function (location) {\n${readFileSync(`${OWN}/js/admin.js`, 'utf8')}\n})(window.recordingLocation);`).join('\n;\n'));
    return window;
}
// what `$site.foot()` emits ahead of admin.js when the application says it in French
const words = `Object.assign(((window.kroomAdmin ??= {}).strings ??= {}), { pages: 'pages du site', inbox: 'Courrier reçu', 'seo.title': 'Titre', 'web.missing': 'Introuvables' });`;
let window = bar(words);
/** The page reloaded, in the same tab: what admin.js remembered in sessionStorage is still there. */
const reload = async () => {
    const storage = window.sessionStorage;
    window = bar(words, Object.fromEntries([...Array(storage.length).keys()].map(i => [storage.key(i), storage.getItem(storage.key(i))])));
    await sleep(20);
};
/** Where admin.js asked to go since last asked: 'reload', or an address. */
const went = () => window.navigations.splice(0);
const $ = (s) => window.document.querySelector(s);
const $$ = (s) => [...window.document.querySelectorAll(s)];
const click = (el) => el.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
const entry = (id) => $(`.kroom-admin nav [data-entry="${id}"]`);
const remembered = (key) => window.sessionStorage.getItem('kroom.admin.' + key);

check('one control per entry, kroom\'s, then the plugins\' that stand alone, then the eye', $$('.kroom-admin nav > *').map(e => e.dataset.entry ?? e.getAttribute('aria-label')),
    ['site', 'pages', 'plugins', 'themes', 'roles', 'inbox', 'stats', 'preview']);
check('neither the journal nor the media have an entry', [entry('journal'), entry('media')], [null, null]);
check('builtins get a pictogram, a plugin without one the initial of its (translated) label', [!!entry('pages').querySelector('svg'), entry('inbox').textContent], [true, 'C']);
check('a plugin\'s link is a link', [entry('stats').tagName, entry('stats').getAttribute('href')], ['A', 'https://stats.example/']);
check('the panel starts closed', $('.kroom-admin-panel').hidden, true);
check("kroom's entries keep kroom's words, a plugin's take the application's", [entry('pages').title, entry('inbox').title], ['pages', 'Courrier reçu']);

// the eye: pressed, the page as visitors see it; released, every block revealed — remembered by the tab
{
    const eye = () => $('.kroom-admin nav > :last-child');
    check('the eye starts pressed, nothing revealed', [eye().getAttribute('aria-pressed'), window.document.documentElement.classList.contains('kroom-admin-revealed')], ['true', false]);
    click(eye());
    check('released, it reveals every block', [eye().getAttribute('aria-pressed'), window.document.documentElement.classList.contains('kroom-admin-revealed')], ['false', true]);
    await reload();
    check('and stays released across a reload of the tab', window.document.documentElement.classList.contains('kroom-admin-revealed'), true);
    click(eye());
    check('pressed again, the page is the visitors\' and the tab forgets', [window.document.documentElement.classList.contains('kroom-admin-revealed'), remembered('reveal')], [false, null]);
}

click(entry('pages'));
await sleep(20);
// the pages panel, which is the menu
const sent = () => calls.findLast(c => c.method !== 'GET');
const lines = () => $$('.kroom-admin-menu-entry:not(.kroom-admin-phantom)');
const line = (slug) => lines().find(li => li._item.slug === slug);
const list = (slug) => slug ? line(slug).querySelector(':scope > ul') : $('.kroom-admin-menu-root');
const sortable = (ul) => window.sortables.find(s => s.el === ul).options;
const rows = () => lines().map(li => li.querySelector(':scope > details > summary').firstChild.textContent);
/** What a stored tree says, slugs only: `legal(terms)`. */
const outline = (items) => items.map(i => i.children.length ? `${i.slug}(${outline(i.children).join(' ')})` : i.slug ?? i.href);
const lastMenu = () => calls.findLast(c => c.method === 'PUT' && c.url === '/api/site/menu')?.body;

check('pages: one line per page, its label as plain text, the pages under it included', rows(),
    ['About', 'Contact', 'Clubs', 'Les Vagabonds', 'Legal', 'Terms', 'Company', 'History', 'Docs']);
check('nothing but a handle and a label on the line', lines().every(li => [...li.children].map(c => c.tagName).join() === 'SPAN,DETAILS,UL'
    && li.querySelector(':scope > .kroom-admin-handle') && !li.querySelector(':scope > details > summary :is(input, select, button, a)')), true);
check('a draft says so', lines().filter(li => li.querySelector(':scope > details > summary small')).map(li => [li._item.slug,
    li.querySelector(':scope > details > summary small').textContent]), [['company', ' draft'], ['history', ' draft']]);
check('a line says its kind; only an editor\'s page with nothing under it is free to move', lines().map(li =>
    [li._item.slug, [...li.classList].filter(c => c !== 'kroom-admin-menu-entry').join(' ')]), [
    ['about', 'kroom-admin-kind-authored'], ['contact', 'kroom-admin-kind-template kroom-admin-fixed'], ['club', 'kroom-admin-kind-template kroom-admin-fixed'],
    ['13Ma', 'kroom-admin-kind-instance kroom-admin-fixed'], ['legal', 'kroom-admin-kind-section kroom-admin-fixed'],
    ['terms', 'kroom-admin-kind-template kroom-admin-fixed'], ['company', 'kroom-admin-kind-authored kroom-admin-fixed'], ['history', 'kroom-admin-kind-authored'],
    [undefined, 'kroom-admin-kind-link']]);
check('the arrangement is said to be the editors\', and can be forgotten', [$('.kroom-admin-menu-head small').textContent,
    $('.kroom-admin-menu-reset')?.textContent], ['as you arranged them', 'forget this arrangement']);
{
    const levels = window.sortables.filter(s => s.el !== $('.kroom-admin-menu-source'));
    check('every level is a place to drag into, a page\'s empty list included', [levels.length,
        levels.every(s => s.options.handle === '.kroom-admin-handle' && s.options.group === 'kroom-menu')], [10, true]);
    const source = sortable($('.kroom-admin-menu-source'));
    check('below the tree, a phantom row is dragged out as a copy, nothing dropped back into it', [$('.kroom-admin-phantom').title, source.group, source.sort],
        ['drag me where a new page or link goes', { name: 'kroom-menu', pull: 'clone', put: false }, false]);
}

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
    check('a drop where it was asks nothing', [calls.length, went()], [before, []]);
    list().insertBefore(line('contact'), line('about'));
    sortable(list()).onEnd({ from: list(), to: list(), item: line('contact'), oldIndex: 1, newIndex: 0 });
    await sleep(20);
    check('a reorder puts the whole tree as it now stands, and nothing moves', [calls.slice(before).filter(c => c.method !== 'GET').map(c => `${c.method} ${c.url}`),
        outline(lastMenu().items)], [['PUT /api/site/menu'], ['contact', 'about', 'club(13Ma)', 'legal(terms)', 'company(history)', 'https://y.example/doc']]);
    check('the tree put is stripped of what the server computes', lastMenu().items.slice(0, 2), [
        { slug: 'contact', label: { en: 'Contact' }, description: {}, children: [] },
        { slug: 'about', label: { en: 'About', fr: 'À propos' }, description: { en: 'Who we are' }, children: [] }]);
    check('then the page reloads: the menu, the west, a TOC are the server\'s', went(), ['reload']);
    await reload();
    check('the bar reopens on the pages panel, the tree the server\'s again', [entry('pages').getAttribute('aria-pressed'), $('.kroom-admin-panel').hidden, rows()[0]],
        ['true', false, 'About']);

    before = calls.length;
    const [from, to, history] = [list('company'), list('about'), line('history')];
    to.appendChild(history);
    sortable(from).onEnd({ from, to, item: history, oldIndex: 0, newIndex: 0 });
    await sleep(20);
    const moved = calls.slice(before).filter(c => c.method !== 'GET');
    check('a drop under another page moves it there first, then puts the tree', [moved.map(c => `${c.method} ${c.url}`), moved[0]?.body, outline(moved[1]?.body.items ?? [])],
        [['POST /api/site/pages/move', 'PUT /api/site/menu'], { from: '/company/history', to: '/about/history' },
         ['about(history)', 'contact', 'club(13Ma)', 'legal(terms)', 'company', 'https://y.example/doc']]);
    check('and reloads', went(), ['reload']);

    // a link has nothing to move: a drop under another page just puts the tree
    before = calls.length;
    const docs = lines().find(li => li._item.href);
    list('about').appendChild(docs);
    sortable(list()).onEnd({ from: list(), to: list('about'), item: docs, oldIndex: 5, newIndex: 1 });
    await sleep(20);
    check('a link dropped elsewhere moves nothing, the tree is put with it there', calls.slice(before).filter(c => c.method !== 'GET').map(c => `${c.method} ${c.url}`),
        ['PUT /api/site/menu']);
    check('and it is put as a link', lastMenu().items[0].children.find(i => i.href), { href: 'https://y.example/doc', label: { en: 'Docs' }, description: {}, children: [] });
    went();
    await reload();
}

// the accordion: label and description in the language chosen, what the page is, and an editor's page's own
$('.kroom-admin-menu-head select').value = 'fr';
$('.kroom-admin-menu-head select').dispatchEvent(new window.Event('change'));
check('switching the language shows its labels, the default\'s where it has none', rows(),
    ['À propos', 'Contact', 'Clubs', 'Les Vagabonds', 'Legal', 'Mentions', 'Company', 'History', 'Docs']);
{
    const fields = (slug) => [...line(slug).querySelectorAll(':scope > details > .kroom-admin-menu-fields input')];
    check('the fields hold that language\'s words', [fields('about').map(i => i.value), fields('contact').map(i => i.value)], [['À propos', ''], ['', '']]);
    check('and say what they take by a placeholder, no label', [fields('about').map(i => i.placeholder), line('about').querySelector(':scope > details label')], [['label', 'description'], null]);
    const [label] = fields('contact');
    label.value = ' Nous écrire ';
    label.dispatchEvent(new window.Event('change'));
    await sleep(20);
    check('a label changed is stored in that language, beside the others, with the tree, and the page reloads', [sent().url, lastMenu().items[1], went()],
        ['/api/site/menu', { slug: 'contact', label: { en: 'Contact', fr: 'Nous écrire' }, description: {}, children: [] }, ['reload']]);
}
check('each page links to itself, a developer\'s says whose it is', ['about', 'contact', '13Ma'].map(slug =>
    line(slug).querySelector(':scope > details .kroom-admin-menu-page').textContent), ['/about ↗', '/contact ↗ — a developer\'s page', '/club/13Ma ↗ — a developer\'s page']);
check('a section offers to create its page', line('legal').querySelector(':scope > details .kroom-admin-menu-page').textContent,
    '/legal — no page here create this page');
click(line('legal').querySelector(':scope > details .kroom-admin-menu-page button'));
await sleep(20);
check('creating it posts its path and its words, then opens it', [sent(), went()],
    [{ url: '/api/site/pages', method: 'POST', body: { path: '/legal', label: { en: 'Legal' } } }, ['/legal']]);
check('only an editor\'s page has a layout, a publishing switch and a delete', lines().filter(li => li.querySelector(':scope > details select')).map(li => li._item.slug),
    ['about', 'company', 'history']);
{
    const layout = line('about').querySelector(':scope > details select');
    check('the layouts offered are the skeleton\'s, on the page\'s', [[...layout.options].map(o => o.value), layout.value],
        [['default', 'article', 'sidebar', 'landing'], 'article']);
    layout.value = 'sidebar';
    layout.dispatchEvent(new window.Event('change'));
    await sleep(20);
    check('another layout is put on the page, which reloads', [sent(), went()], [{ url: '/api/site/pages/about', method: 'PUT', body: { layout: 'sidebar' } }, ['reload']]);
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
went();
{
    const before = calls.length;
    window.confirm = () => false;
    click(buttons('history')[1]);
    await sleep(20);
    check('a delete not confirmed asks nothing', [calls.length, went()], [before, []]);
    window.confirm = () => true;
    click(buttons('history')[1]);
    await sleep(20);
    check('a delete confirmed deletes the page, and reloads this one', [sent(), went()],
        [{ url: '/api/site/pages/company/history', method: 'DELETE', body: undefined }, ['reload']]);
}
check('the layout reads on one line with its choice', line('about').querySelector(':scope > details .kroom-admin-menu-layout')?.firstChild.textContent, 'layout ');
check('one accordion opens at a time', lines().every(li => li.querySelector(':scope > details').name === 'kroom-admin-menu'), true);
{
    line('about').querySelector(':scope > details').open = true;
    await sleep(20);
    check('the accordion open is remembered by its path', remembered('entry'), '/about');
    await reload();
    check('and is open again after a reload', lines().filter(li => li.querySelector(':scope > details').open).map(li => li._item.slug), ['about']);
}
click($('.kroom-admin-menu-reset'));
await sleep(20);
check('forgetting the arrangement deletes it', [sent(), went()], [{ url: '/api/site/menu', method: 'DELETE', body: undefined }, ['reload']]);
{
    const docs = lines().find(li => li._item.href);
    check('a link offers to be removed, and nothing else', [...docs.querySelectorAll(':scope > details button')].map(b => b.textContent), ['remove this link']);
    click(docs.querySelector(':scope > details button'));
    await sleep(20);
    check('removing it puts the tree without it, and reloads', [sent().url, sent().method, sent().body.items.some(i => i.href), went()], ['/api/site/menu', 'PUT', false, ['reload']]);
    // its row is out of the tree by the time the click reaches the document: still a click in the bar
    check('and the bar will reopen on the pages after the reload', remembered('panel'), 'pages');
}

// a new page: the phantom row dropped in the tree asks its name, then where — the segment following the name
// until typed over — and its layout. Sortable leaves a copy of the row behind and moves the row dragged.
window = bar(words);
click(entry('pages'));
await sleep(20);
{
    const drop = (ul) => {
        const row = $('.kroom-admin-phantom').cloneNode(true);
        ul.appendChild(row);
        sortable(ul).onAdd({ item: row, to: ul });
        return row.querySelector('.kroom-admin-new-page');
    };
    const type = (input, text) => { input.value = text; input.dispatchEvent(new window.Event('input')); };
    const form = drop(list('club'));
    type(form.elements.label, 'Notre Société !');
    check('the segment is derived from the name', form.elements.slug.value, 'notre-societe');
    type(form.elements.slug, 'societe');
    type(form.elements.label, 'Notre Société');
    check('typed over, it stays as typed', form.elements.slug.value, 'societe');
    check('the one input says what it takes', form.elements.slug.placeholder, 'segment / URL');
    check('the layout is asked at birth, among the skeleton\'s', [...form.elements.layout.options].map(o => o.value), ['default', 'article', 'sidebar', 'landing']);
    form.elements.layout.value = 'landing';
    form.dispatchEvent(new window.Event('submit', { cancelable: true }));
    await sleep(20);
    check('a new page posts its path under the list it landed in, its name and its layout', calls.findLast(c => c.method === 'POST'),
        { url: '/api/site/pages', method: 'POST', body: { path: '/club/societe', label: { en: 'Notre Société' }, layout: 'landing' } });
    check('then the tree is put with it at that very place', outline(lastMenu().items),
        ['about', 'contact', 'club(13Ma societe)', 'legal(terms)', 'company(history)', 'https://y.example/doc']);
    check('and the new page is opened', went(), ['/club/societe']);

    const link = drop(list());
    type(link.elements.label, 'Docs');
    type(link.elements.slug, 'https://docs.example');
    link.dispatchEvent(new window.Event('submit', { cancelable: true }));
    await sleep(20);
    check('an address makes a link, stored where it was dropped', [sent().url, sent().method, sent().body.items.at(-1), went()],
        ['/api/site/menu', 'PUT', { href: 'https://docs.example', label: { en: 'Docs' }, description: {}, children: [] }, ['reload']]);

    const takes = (text) => new RegExp(`^(?:${drop(list()).elements.slug.pattern})$`, 'v').test(text);
    check('an address is a place to go, nothing a browser runs', ['https://x.example/a', 'mailto:a@x.example', 'tel:+33123456789', 'javascript:alert(1)', 'data:text/html,x'].map(takes),
        [true, true, true, false, false]);

    // a form left: its row goes, the panel stays — the click and the key are the form's, not the page's
    const before = () => calls.filter(c => c.method !== 'GET').length;
    const sentBefore = before();
    const left = drop(list());
    click(left.querySelector('button[type="button"]'));
    check('cancelled, the form is gone, nothing sent, and the panel stays', [left.isConnected, before() - sentBefore, $('.kroom-admin-panel').hidden, remembered('panel')], [false, 0, false, 'pages']);
    const escaped = drop(list());
    escaped.elements.label.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    check('Escape leaves the form, not the panel', [escaped.isConnected, $('.kroom-admin-panel').hidden, remembered('panel')], [false, false, 'pages']);
    window.document.body.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    check('a second one closes the panel', [$('.kroom-admin-panel').hidden, remembered('panel')], [true, null]);
    click(entry('pages'));
    await sleep(20);
}
check('the open entry is pressed', entry('pages').getAttribute('aria-pressed'), 'true');
click(entry('pages'));
check('clicking it again closes', $('.kroom-admin-panel').hidden, true);

click(entry('roles'));
await sleep(20);
click($('main'));
check('a click on the page closes the panel, and the tab forgets it', [$('.kroom-admin-panel').hidden, remembered('panel')], [true, null]);

click(entry('themes'));
await sleep(20);
check('themes: each with a preview of this page, the one in use says so', [$$('.kroom-admin-actions a').map(a => a.getAttribute('href')),
    $$('.kroom-admin-actions button').length], [['/club/13Ma?theme=basic', '/club/13Ma?theme=editorial'], 1]);
check('a theme\'s settings fold under it, whether worn or not; a theme with none has no fold', $$('.kroom-admin-body > ul > li').map(li =>
    [...li.querySelectorAll(':scope > details.kroom-admin-group > form')].map(f => [...f.elements].filter(e => e.name).map(e => e.name))), [[['accent']], []]);
{
    const form = $('.kroom-admin-body > ul > li form');
    form.elements.accent.value = 'red';
    form.dispatchEvent(new window.Event('submit', { cancelable: true }));
    await sleep(20);
    check('saved as a plugin\'s, and the page reloads', [calls.at(-1), went()], [{ url: '/api/site/plugins/basic/settings', method: 'PUT', body: { accent: 'red' } }, ['reload']]);
}
click($('.kroom-admin-actions button'));
await sleep(20);
check('using one puts it, and reloads', [calls.at(-1).method, calls.at(-1).url, calls.at(-1).body, went()], ['PUT', '/api/site/theme', { id: 'editorial' }, ['reload']]);

click(entry('plugins'));
await sleep(20);
const plugin = (name) => $$('.kroom-admin-plugin').find(d => d.querySelector('summary').textContent === name);
check('the plugins panel lists plugins only, each with its switch in the summary', $$('.kroom-admin-plugin > summary').map(s => [s.textContent, !!s.querySelector('input[role="switch"]')]),
    [['SEO', true], ['Forms', true], ['Webmaster', true]]);
check('a plugin switched off says it quietly', $$('.kroom-admin-plugin').map(d => d.classList.contains('kroom-admin-off')), [false, false, true]);
{
    const form = $('.kroom-admin-settings');
    check('a secret is never filled in', form.elements.key.value, '');
    check("a plugin's setting in the application's words", form.elements.title.closest('label').firstChild.textContent, 'Titre');
    check('a boolean is a checkbox', form.elements.index.checked, true);
    check('a group folds under its name, the rest outside', [$$('.kroom-admin-settings details.kroom-admin-group > summary').map(s => s.textContent),
        $('.kroom-admin-settings details.kroom-admin-group').querySelectorAll('label').length], [['Search engines'], 2]);
    check('a choice is a select, on its value', [form.elements.mode.tagName, form.elements.mode.value], ['SELECT', 'tls']);
    form.elements.title.value = 'Les Vagabonds';
    form.dispatchEvent(new window.Event('submit', { cancelable: true }));
    await sleep(20);
    check('save puts every setting, a secret left empty, and reloads the page', [calls.at(-1), went()],
        [{ url: '/api/site/plugins/seo/settings', method: 'PUT', body: { title: 'Les Vagabonds', index: 'true', key: '', mode: 'tls' } }, ['reload']]);
}
{
    const forms = plugin('Forms');
    forms.open = true;
    await sleep(20);
    check('a plugin\'s tables show under it once it is opened, cells as text', [...forms.querySelectorAll('.kroom-admin-table td')].map(td => td.innerHTML), ['Alice', '&lt;b&gt;hi&lt;/b&gt;']);
    const web = plugin('Webmaster');
    check('not before', web.querySelectorAll('.kroom-admin-table').length, 0);
    web.open = true;
    await sleep(20);
    check('several tables, each under its label, in the application\'s words', [[...web.querySelectorAll('.kroom-admin-section h4')].map(h => h.textContent),
        web.querySelectorAll('.kroom-admin-table').length], [['Redirects', 'Introuvables'], 2]);
}
{
    const switchOf = (name) => plugin(name).querySelector('summary input');
    const asked = [];
    window.confirm = (message) => { asked.push(message); return false; };
    const before = calls.length;
    const forms = switchOf('Forms');
    forms.checked = false;
    forms.dispatchEvent(new window.Event('change'));
    await sleep(20);
    check('switching off a plugin that holds something asks first, saying what', asked,
        ['Switch Forms off? It holds 2 forms, 5 entries — the blocks calling it will say they cannot be displayed.']);
    check('declined, nothing is asked of the server and the switch stays on', [calls.length - before, forms.checked, went()], [0, true, []]);
    window.confirm = (message) => { asked.push(message); return true; };
    forms.checked = false;
    forms.dispatchEvent(new window.Event('change'));
    await sleep(20);
    check('confirmed, the state is put and the page reloads', [calls.at(-1), went()],
        [{ url: '/api/site/plugins/forms/enabled', method: 'PUT', body: { enabled: false } }, ['reload']]);
    asked.length = 0;
    const seo = switchOf('SEO');
    click(seo.closest('summary'));
    check('a plugin opened is remembered', remembered('fold'), 'seo');
    seo.checked = false;
    seo.dispatchEvent(new window.Event('change'));
    await sleep(20);
    check('a plugin holding nothing is switched off without a question, and the page reloads', [asked, calls.at(-1), went()],
        [[], { url: '/api/site/plugins/seo/enabled', method: 'PUT', body: { enabled: false } }, ['reload']]);
    await reload();
    check('the bar reopens on the plugins, that plugin open', [entry('plugins').getAttribute('aria-pressed'), $$('.kroom-admin-plugin').filter(d => d.open).map(d => d.querySelector('summary').textContent)],
        ['true', ['SEO']]);
}

click(entry('site'));
await sleep(20);
{
    const form = $('.kroom-admin-settings');
    const groups = () => $$('.kroom-admin-settings details.kroom-admin-group').map(d => [d.querySelector('summary').textContent, d.open]);
    check("the site's settings fold by group in kroom's words, the first open", groups(), [['General', true], ['Sharing', false], ['Mail', false]]);
    check("kroom's words for kroom's labels, a secret never read back", [form.elements.name.closest('label').firstChild.textContent, form.elements.smtpPassword.value], ['Name', '']);
    click($$('.kroom-admin-settings details.kroom-admin-group > summary')[2]);
    check('a group opened is remembered', remembered('fold'), 'site.Mail');
    form.elements.name.value = 'Les Vagabonds';
    form.dispatchEvent(new window.Event('submit', { cancelable: true }));
    await sleep(20);
    check('saved to the site, not to a plugin, and the page reloads', [calls.at(-1), went()],
        [{ url: '/api/site/settings', method: 'PUT', body: { name: 'Les Vagabonds', description: '', smtpPassword: '' } }, ['reload']]);
    check('the traffic tables under the form, titled in kroom\'s words', $$('.kroom-admin-section h4').map(h => h.textContent), ['Redirects', 'Not found']);
    await reload();
    check('the bar reopens on the site, on the group left open', [entry('site').getAttribute('aria-pressed'), groups()],
        ['true', [['General', false], ['Sharing', false], ['Mail', true]]]);
}

click(entry('roles'));
await sleep(20);
check('roles: the table', $$('.kroom-admin-roles dt').map(d => d.textContent), ['admin', 'editor']);

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
