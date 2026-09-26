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
    { id: 'pages', label: 'pages', builtin: true }, { id: 'journal', label: 'journal', builtin: true },
    { id: 'plugins', label: 'plugins', builtin: true }, { id: 'roles', label: 'roles', builtin: true },
    { id: 'forms', label: 'Forms', table: '/api/forms/entries', builtin: false }
]).replace(/"/g, '&quot;');

const answers = {
    '/api/site/pages': [{ template: 'pages/login.html', route: '/login', urls: ['/login'] },
                        { template: 'pages/club/_club_.html', route: '/club/{club}', urls: ['/club/13Ma'] }],
    '/api/content/journal?limit=100': [{ rev: 'a', path: 'pages/club/13Ma/description.md', author: 'admin', time: 0 }],
    '/api/site/plugins': [{ id: 'seo', name: 'SEO', description: 'meta', settings: [
        { key: 'title', label: 'Title', type: 'text', value: 'Club' },
        { key: 'index', label: 'Indexed', type: 'boolean', value: 'true' },
        { key: 'key', label: 'Key', type: 'secret', set: true }] }],
    '/api/site/roles': { roles: { admin: ['*'], editor: ['content.*'] }, yours: ['admin'] },
    '/api/forms/entries': { columns: ['name', 'message'], rows: [['Alice', '<b>hi</b>']] }
};

const dom = new JSDOM(`<!doctype html><html><body><main>page</main>
    <aside class="kroom-admin" data-api="/api/site" data-content-api="/api/content" data-entries="${entries}"></aside>
    </body></html>`, { runScripts: 'outside-only', url: 'http://localhost/club/13Ma' });
const { window } = dom;
const calls = [];
window.Response = Response;
window.fetch = async (url, options = {}) => {
    calls.push({ url, method: options.method || 'GET', body: options.body && JSON.parse(options.body) });
    return new Response(JSON.stringify(answers[url] ?? {}), { status: 200, headers: { 'content-type': 'application/json' } });
};
window.eval(['domhelper.js', 'api.js'].map(f => readFileSync(`${ASSETS}/${f}`, 'utf8'))
    .concat(readFileSync(`${OWN}/js/admin.js`, 'utf8')).join('\n;\n'));
const $ = (s) => window.document.querySelector(s);
const $$ = (s) => [...window.document.querySelectorAll(s)];
const click = (el) => el.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
const entry = (id) => $(`.kroom-admin nav [data-entry="${id}"]`);

check('one control per entry', $$('.kroom-admin nav > *').map(e => e.dataset.entry), ['pages', 'journal', 'plugins', 'roles', 'forms']);
check('builtins get a pictogram, a plugin without one its initial', [!!entry('pages').querySelector('svg'), entry('forms').textContent], [true, 'F']);
check('the panel starts closed', $('.kroom-admin-panel').hidden, true);

click(entry('pages'));
await sleep(20);
check('pages: routes and instances, as links', $$('.kroom-admin-body a').map(a => a.getAttribute('href')), ['/login', '/club/13Ma']);
check('the open entry is pressed', entry('pages').getAttribute('aria-pressed'), 'true');

click(entry('pages'));
check('clicking it again closes', $('.kroom-admin-panel').hidden, true);

click(entry('journal'));
await sleep(20);
check('journal: one revision', $('.kroom-admin-body li div')?.textContent, 'pages/club/13Ma/description.md');

click(entry('plugins'));
await sleep(20);
const form = $('.kroom-admin-settings');
check('a secret is never filled in', form.elements.key.value, '');
check('a boolean is a checkbox', form.elements.index.checked, true);
form.elements.title.value = 'Les Vagabonds';
form.dispatchEvent(new window.Event('submit', { cancelable: true }));
await sleep(20);
check('save puts every setting, a secret left empty', calls.at(-1),
    { url: '/api/site/plugins/seo/settings', method: 'PUT', body: { title: 'Les Vagabonds', index: 'true', key: '' } });

click(entry('roles'));
await sleep(20);
check('roles: the table', $$('.kroom-admin-roles dt').map(d => d.textContent), ['admin', 'editor']);

click(entry('forms'));
await sleep(20);
check('a plugin table, its cells as text', $$('.kroom-admin-table td').map(td => td.innerHTML), ['Alice', '&lt;b&gt;hi&lt;/b&gt;']);

window.document.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape' }));
check('Escape closes', $('.kroom-admin-panel').hidden, true);

console.log(failures ? `\n${failures} FAILED` : '\nall ok');
process.exit(failures ? 1 : 0);
