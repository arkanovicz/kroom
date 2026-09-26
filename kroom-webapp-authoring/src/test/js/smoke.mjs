// What authoring.js does to the page, checked against a fake DOM and a fake server.
//
// Not part of `gradle test`: it needs node, which this house runs only in docker — `./smoke.sh` here, or
//   docker run --rm -u 1000:1000 -v "$PWD":/home/app -w /home/app node:24 \
//     bash -c "npm install --silent --no-fund --no-audit jsdom && node smoke.mjs"
// The editor is the one piece no ktor test can reach; leaving it entirely unexercised was the alternative.

import { JSDOM } from 'jsdom';
import { readFileSync } from 'node:fs';

const ASSETS = '../../../../kroom-webapp-assets/src/main/resources/static/js';
const OWN = '../../main/resources/static';
const PATH = 'pages/club/13Ma/description.md';

let failures = 0;
const check = (what, got, want) => {
    const ok = JSON.stringify(got) === JSON.stringify(want);
    if (!ok) failures++;
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}${ok ? '' : `\n     got  ${JSON.stringify(got)}\n     want ${JSON.stringify(want)}`}`);
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/** A page holding one editable block, the scripts loaded as a browser loads them: one shared scope. */
function page(responder, preset = '') {
    const dom = new JSDOM(`<!doctype html><html><body>
        <div class="kroom-block" data-content="${PATH}" data-api="/api/content" data-name="description">
          <nav class="kroom-block-tools"><button class="kroom-edit">e</button></nav>
          <div class="kroom-block-body"><p>published text</p></div>
        </div></body></html>`, { runScripts: 'outside-only', url: 'http://localhost/club/13Ma' });
    const { window } = dom;
    const calls = [];
    // real Response objects: api.js branches on `instanceof Response` to carry a status and a payload
    window.Response = Response;
    window.fetch = async (url, options = {}) => {
        calls.push({ url, method: options.method || 'GET', body: options.body && JSON.parse(options.body) });
        const { status = 200, payload = {} } = responder(url, options.method || 'GET') || {};
        return new Response(JSON.stringify(payload), { status, headers: { 'content-type': 'application/json' } });
    };
    if (window.HTMLDialogElement) window.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
    window.eval(['domhelper.js', 'api.js'].map(f => readFileSync(`${ASSETS}/${f}`, 'utf8'))
        .concat(preset, readFileSync(`${OWN}/lib/diff-match-patch/diff_match_patch.js`, 'utf8'),
                readFileSync(`${OWN}/js/authoring.js`, 'utf8')).join('\n;\n'));
    const click = (selector) => window.document.querySelector(selector)
        ?.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    return { window, calls, click, $: (s) => window.document.querySelector(s) };
}

const held = { payload: { body: '## Titre', rev: 'abc123', meta: { author: 'admin' }, lock: { owner: 'admin' }, editable: true } };
const previewOf = (body) => ({ payload: { page: `<html><body><div class="kroom-block" data-content="${PATH}"><div class="kroom-block-body"><p>${body}</p></div></div></body></html>` } });

// --- taking a block, and giving it back -----------------------------------------------------------
{
    const { calls, click, $ } = page((url) => url.includes('/lock/') ? held : { payload: { rev: 'def456' } });
    click('.kroom-edit');
    await sleep(20);
    check('edit takes the lock', calls[0], { url: `/api/content/lock/${PATH}`, method: 'POST', body: undefined });
    check('the stored body is what you edit', $('.kroom-block textarea')?.value, '## Titre');
    check('the published rendering is kept beside it', $('.kroom-preview')?.textContent.includes('published text'), true);
    check('the markdown tab is the one shown', [$('.kroom-pane[data-pane="source"]').hidden, $('.kroom-preview').hidden], [false, true]);

    click('.kroom-cancel');
    await sleep(20);
    check('cancel gives the lock back', calls[1], { url: `/api/content/lock/${PATH}`, method: 'DELETE', body: undefined });
    check('cancel restores the block', $('.kroom-block textarea'), null);
    check('the page is as it was', $('.kroom-block-body')?.textContent.trim(), 'published text');
}

// --- submitting -----------------------------------------------------------------------------------
{
    const { calls, click, $, window } = page((url) =>
        url.includes('/lock/') ? held : url.includes('/preview/') ? previewOf('rendu du serveur') : { payload: { rev: 'def456' } });
    click('.kroom-edit');
    await sleep(20);
    const textarea = $('.kroom-block textarea');
    textarea.value = '## Titre\n\nnouveau';
    textarea.dispatchEvent(new window.Event('input', { bubbles: true }));
    await sleep(900);
    check('typing alone renders nothing, it keeps the lock', calls.map(c => c.url.includes('/preview/')), [false, false]);
    click('.kroom-tab[data-tab="preview"]');
    await sleep(20);
    check('the preview tab re-renders the page and shows this block of it',
        $('.kroom-preview .kroom-block-body')?.textContent.trim(), 'rendu du serveur');
    check('the preview asks for the page it is in', calls[2],
        { url: `/api/content/preview/${PATH}`, method: 'POST', body: { page: '/club/13Ma', body: '## Titre\n\nnouveau' } });
    check('formatting is for the markdown tab only', $('.kroom-format').hidden, true);
    click('.kroom-tab[data-tab="source"]');
    click('.kroom-tab[data-tab="preview"]');
    await sleep(20);
    check('an unchanged body is not re-rendered', calls.filter(c => c.url.includes('/preview/')).length, 1);
    click('.kroom-tab[data-tab="source"]');

    click('.kroom-submit');
    await sleep(20);
    check('submit carries its page and the rev it started from', calls[calls.length - 1],
        { url: `/api/content/${PATH}`, method: 'POST', body: { page: '/club/13Ma', rev: 'abc123', body: '## Titre\n\nnouveau' } });
}

// --- formatting -----------------------------------------------------------------------------------
{
    const { click, $ } = page(() => held);
    click('.kroom-edit');
    await sleep(20);
    const textarea = $('.kroom-block textarea');
    const select = (text, from, to) => { textarea.value = text; textarea.setSelectionRange(from, to); };

    select('un mot ici', 3, 6);
    click('.kroom-format-bold');
    check('bold wraps the selection', textarea.value, 'un **mot** ici');
    check('and keeps it selected', [textarea.selectionStart, textarea.selectionEnd], [5, 8]);
    click('.kroom-format-bold');
    check('bold again takes it off', textarea.value, 'un mot ici');

    select('a\nb\nc', 0, 3);
    click('.kroom-format-bullets');
    check('a list marks every line touched', textarea.value, '- a\n- b\nc');
    check('the selection stays on the text, not the markers', [textarea.selectionStart, textarea.selectionEnd], [2, 7]);
    textarea.setSelectionRange(0, 7);
    click('.kroom-format-bullets');
    check('and unmarks them when all are marked', textarea.value, 'a\nb\nc');
    select('a\nb', 0, 3);
    click('.kroom-format-numbers');
    check('a numbered list counts', textarea.value, '1. a\n2. b');
    select('texte', 2, 2);
    click('.kroom-format-quote');
    check('a caret keeps its place in the line', [textarea.value, textarea.selectionStart, textarea.selectionEnd], ['> texte', 4, 4]);
    click('.kroom-format-quote');
    check('and keeps it when the marker goes', [textarea.value, textarea.selectionStart, textarea.selectionEnd], ['texte', 2, 2]);

    select('voir ici', 5, 8);
    click('.kroom-format-link');
    check('a link selects its url to type over', [textarea.value, textarea.value.slice(textarea.selectionStart, textarea.selectionEnd)],
        ['voir [ici](https://)', 'https://']);
}

// --- the application's words ---------------------------------------------------------------------
{
    // as AuthoringAssets.stringTags() emits them, ahead of authoring.js
    const preset = `Object.assign(((window.kroomAuthoring ??= {}).strings ??= {}), {"preview":"aperçu","edit":"modifier","lockHeld":"{owner} y travaille"});`;
    const taken = { status: 409, payload: { message: 'block held by nestor', code: 'lockHeld', args: { owner: 'nestor' } } };
    const { click, $, window } = page(() => taken, preset);
    await sleep(20);   // the handle is named once the document is loaded
    check('the edit handle is named in the application\'s words', $('.kroom-edit').title, 'modifier');
    click('.kroom-edit');
    await sleep(20);
    check('an API error is said by its code', $('.kroom-notice')?.textContent, 'Error: nestor y travaille');
    window.kroomAuthoring.strings.lockHeld = undefined;
    click('.kroom-edit');
    await sleep(20);
    check('a code the table lacks falls back to the server\'s message', $('.kroom-notice')?.textContent, 'Error: block held by nestor');
    window.kroomAuthoring.strings.error = 'Erreur : {message}';
    click('.kroom-edit');
    await sleep(20);
    check('and every error is introduced in the application\'s words', $('.kroom-notice')?.textContent, 'Erreur : block held by nestor');
    Object.assign(window.kroomAuthoring.strings, { markdown: 'source' });   // after load, client side
    const second = page(() => held, preset);
    second.window.kroomAuthoring.strings.markdown = 'source';
    second.click('.kroom-edit');
    await sleep(20);
    check('tabs read the table when the editor opens', [...second.window.document.querySelectorAll('.kroom-tab')].map(b => b.textContent),
        ['source', 'aperçu', 'history']);
}

// --- someone else wrote meanwhile -----------------------------------------------------------------
{
    const conflict = { status: 409, payload: { message: 'stale', theirs: { body: '## Titre\n\nle leur', rev: 'zzz' } } };
    const { click, $ } = page((url) => url.includes('/lock/') ? held : conflict);
    click('.kroom-edit');
    await sleep(20);
    $('.kroom-block textarea').value = '## Titre\n\nle mien';
    click('.kroom-submit');
    await sleep(20);
    check('a stale submit opens a diff instead of overwriting', !!$('.kroom-diff'), true);
    check('your text is still yours to keep editing', $('.kroom-block textarea')?.value, '## Titre\n\nle mien');
}

// --- history ------------------------------------------------------------------------------------
{
    const log = { payload: [{ rev: 'abc123', path: PATH, author: 'admin', time: 1757000000000 },
                            { rev: 'old999', path: PATH, author: 'nestor', time: 1756000000000 }] };
    const old = { payload: { body: '## Ancien', rev: 'old999' } };
    const { click, $, window } = page((url) =>
        url.includes('/history/') ? log : url.includes('?rev=old999') ? old : held);
    click('.kroom-edit');
    await sleep(20);
    click('.kroom-tab[data-tab="history"]');
    await sleep(20);
    const lines = [...window.document.querySelectorAll('.kroom-past li')].map(li => li.textContent);
    check('the history tab lists what the store remembers', lines.length, 2);
    check('the revision being edited says so', lines[0].endsWith('(current)'), true);
    window.document.querySelectorAll('.kroom-revision')[1].dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    await sleep(20);
    check('a revision is shown against what you are writing', !!$('.kroom-past .kroom-diff'), true);
    check('history offers no submit or cancel', [$('.kroom-submit').hidden, $('.kroom-cancel').hidden], [true, true]);
    click('.kroom-restore');
    check('restoring fills the editor, back on the markdown tab',
        [$('.kroom-block textarea').value, $('.kroom-pane[data-pane="source"]').hidden], ['## Ancien', false]);
    check('and submit is back', $('.kroom-submit').hidden, false);
}

console.log(failures ? `\n${failures} failure(s)` : '\nall good');
process.exit(failures ? 1 : 0);
