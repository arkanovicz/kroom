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
    { id: 'pages', label: 'pages', builtin: true }, { id: 'menu', label: 'menu', builtin: true }, { id: 'journal', label: 'journal', builtin: true },
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
    '/api/site/menu': { stored: false, lang: 'en', languages: ['en', 'fr'], items: [
        { slug: 'about', label: { en: 'About' }, path: '/about', resolved: true },
        { slug: 'legal', label: { en: 'Legal' }, path: '/legal', resolved: false, children: [{ slug: 'terms', label: { en: 'Terms', fr: 'Mentions' }, path: '/legal/terms', resolved: true }] }] },
    '/api/site/pages': { templates: [{ template: 'pages/login.html', route: '/login', urls: ['/login'] },
                                     { template: 'pages/club/_club_.html', route: '/club/{club}', urls: ['/club/13Ma'] }],
                         authored: [{ path: '/about', title: { en: 'About' }, layout: 'default', status: 'draft' }],
                         layouts: ['default', 'article', 'sidebar', 'landing'],
                         lang: 'en', languages: ['en'] },
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

check('one control per entry', $$('.kroom-admin nav > *').map(e => e.dataset.entry), ['site', 'pages', 'menu', 'journal', 'media', 'themes', 'plugins', 'roles', 'forms', 'web', 'inbox']);
check('builtins get a pictogram, a plugin without one the initial of its (translated) label', [!!entry('pages').querySelector('svg'), entry('forms').textContent], [true, 'M']);
check('the panel starts closed', $('.kroom-admin-panel').hidden, true);
check("kroom's entries keep kroom's words, a plugin's take the application's", [entry('pages').title, entry('forms').title], ['pages', 'Messages reçus']);

click(entry('pages'));
await sleep(20);
check('pages: the editors\' first, then the templates\' routes and instances, as links', $$('.kroom-admin-body a').map(a => a.getAttribute('href')), ['/about', '/login', '/club/13Ma']);
check('a draft says so, and offers to publish', [$('.kroom-admin-draft small').textContent, $('.kroom-admin-draft button').textContent], [' /about · draft', 'publish']);
$('.kroom-admin-draft button').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
await sleep(20);
check('publishing puts the status', calls.at(-2), { url: '/api/site/pages/about', method: 'PUT', body: { status: 'published' } });
const newPage = $('.kroom-admin-new-page');
newPage.elements.path.value = '/company/history';
newPage.elements.title.value = 'Our history';
newPage.dispatchEvent(new window.Event('submit', { cancelable: true }));
await sleep(20);
check('a new page posts its path, its title in the default language and its layout', calls.find(c => c.method === 'POST' && c.url === '/api/site/pages')?.body,
    { path: '/company/history', title: { en: 'Our history' }, layout: 'default' });
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

click(entry('menu'));
await sleep(20);
check('the menu: a tree, labels in the chosen language, the unresolved section in red',
    [$$('.kroom-admin-body > .kroom-admin-menu > li > .kroom-admin-menu-row input').map(i => i.value), $$('.kroom-admin-unresolved > .kroom-admin-menu-row code').map(c => c.textContent)],
    [['About', 'Legal'], ['/legal']]);
$('.kroom-admin-menu-head select').value = 'fr';
$('.kroom-admin-menu-head select').dispatchEvent(new window.Event('change'));
check('switching the language shows its words, blank where it has none', $$('.kroom-admin-menu input[placeholder="label"]').map(i => i.value), ['', '', 'Mentions']);
$$('.kroom-admin-menu-moves button')[1].dispatchEvent(new window.MouseEvent('click', { bubbles: true }));   // About ↓
await sleep(20);
check('a move puts the whole tree, stripped of what the server computes', calls.find(c => c.method === 'PUT' && c.url === '/api/site/menu')?.body,
    { items: [{ slug: 'legal', label: { en: 'Legal' }, description: {}, children: [{ slug: 'terms', label: { en: 'Terms', fr: 'Mentions' }, description: {}, children: [] }] },
              { slug: 'about', label: { en: 'About' }, description: {}, children: [] }] });

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
