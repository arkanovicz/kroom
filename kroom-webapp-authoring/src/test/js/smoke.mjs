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
        <div class="kroom-block" data-content="${PATH}" data-api="/api/content" data-name="description" data-user="admin">
          <nav class="kroom-block-tools"><button class="kroom-edit"></button></nav>
          <div class="kroom-block-body"><p>published text</p></div>
        </div></body></html>`, { runScripts: 'outside-only', url: 'http://localhost/club/13Ma' });
    const { window } = dom;
    const calls = [];
    // real Response objects: api.js branches on `instanceof Response` to carry a status and a payload
    window.Response = Response;
    window.fetch = async (url, options = {}) => {
        calls.push({ url, method: options.method || 'GET', body: typeof options.body === 'string' ? JSON.parse(options.body) : options.body });
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
    const { calls, click, $, window } = page((url) => url.includes('/lock/') ? held : { payload: { rev: 'def456' } });
    await sleep(20);
    check('the empty edit handle is given its pictogram', !!$('.kroom-edit svg.kroom-icon'), true);
    click('.kroom-edit');
    await sleep(20);
    check('edit takes the lock', calls[0], { url: `/api/content/lock/${PATH}`, method: 'POST', body: undefined });
    check('the stored body is what you edit', $('.kroom-block textarea')?.value, '## Titre');
    check('the published rendering is kept beside it', $('.kroom-preview')?.textContent.includes('published text'), true);
    check('every button of the editor shows its pictogram',
        [...window.document.querySelectorAll('.kroom-format button, .kroom-editor-tools button')].every(b => b.querySelector('svg.kroom-icon path')), true);
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

// --- the pictograms are the application's, the words are kroom's ------------------------------------
{
    // nothing an application emits ahead of authoring.js rewords the editor; its pictograms it may redraw
    const preset = `Object.assign(((window.kroomAuthoring ??= {}).strings ??= {}), {"edit":"modifier","lockHeld":"{owner} y travaille"});
        window.kroomAuthoring.icons = { edit: 'M0 0h24' };`;
    const taken = { status: 409, payload: { message: 'block held by nestor', code: 'lockHeld', args: { owner: 'nestor' } } };
    const { click, $, window } = page(() => taken, preset);
    await sleep(20);   // the handle is named once the document is loaded
    check('the edit handle keeps kroom\'s word', $('.kroom-edit').title, 'edit');
    check('and is drawn with the application\'s pictogram', $('.kroom-edit path')?.getAttribute('d'), 'M0 0h24');
    click('.kroom-edit');
    await sleep(20);
    check('an API error is said by its code, in kroom\'s words', $('.kroom-notice')?.textContent, 'Error: block held by nestor');
    const unknown = { status: 409, payload: { message: 'the server says so', code: 'neverHeardOf' } };
    const other = page(() => unknown);
    other.click('.kroom-edit');
    await sleep(20);
    check('a code the table lacks falls back to the server\'s message', other.$('.kroom-notice')?.textContent, 'Error: the server says so');
}

// --- the stocked languages ------------------------------------------------------------------------
{
    const opened = async (preset) => {
        const p = page(() => held, preset);
        p.click('.kroom-edit');
        await sleep(20);
        return p;
    };
    const english = await opened();
    const stock = english.window.kroomAuthoring.stock;
    check('French says every word English does', Object.keys(stock.fr).sort(), Object.keys(stock.en).sort());
    check('a jsdom browser (en-US) gets English', [english.$('.kroom-tab')?.textContent, english.window.kroomAuthoring.language], ['markdown', 'en']);

    const french = await opened(`window.kroomAuthoring = { language: 'fr' };`);
    check('an application asking for French gets it', [french.$('.kroom-tab[data-tab="preview"]')?.textContent, french.window.kroomAuthoring.language],
        ['aperçu', 'fr']);
    check('the help panel is titled in it too', french.$('#kroom-help header strong')?.textContent, 'aide');

    const auto = page(() => held, `Object.defineProperty(window.navigator, 'languages', { value: ['fr-FR', 'en'], configurable: true });
        window.kroomAuthoring = { language: 'auto' };`);
    check('auto follows the browser\'s first stocked language', auto.window.kroomAuthoring.language, 'fr');

    check('a language kroom does not stock falls back to English',
        page(() => held, `window.kroomAuthoring = { language: 'de' };`).window.kroomAuthoring.language, 'en');
}

// --- help: a manual popover, a tree of two pages ----------------------------------------------------
// jsdom has no popover API: what is checked is the markup the browser acts on, never showPopover()
{
    const { click, $, window } = page(() => held);
    click('.kroom-edit');
    await sleep(20);
    const panel = $('#kroom-help');
    check('the bar offers help, toggling its panel', $('.kroom-editor-bar .kroom-help-toggle')?.getAttribute('popovertarget'), 'kroom-help');
    check('a manual popover', panel?.getAttribute('popover'), 'manual');
    check('its × hides it', $('.kroom-help-close')?.getAttribute('popovertargetaction'), 'hide');
    const shown = () => [...panel.querySelectorAll('.kroom-help-sections, .kroom-help-page')].filter(e => !e.hidden).map(e => e.dataset.section || 'landing');
    check('the landing names the pages, and shows nothing else', [shown(), [...panel.querySelectorAll('.kroom-help-section')].map(b => b.textContent)],
        [['landing'], ['Markdown', 'Model']]);
    check('with no way back from it', [$('.kroom-help-back').hidden, $('#kroom-help header strong').textContent], [true, 'help']);
    panel.querySelectorAll('.kroom-help-section')[0].dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    check('a page opens alone, titled', [shown(), $('#kroom-help header strong').textContent, $('.kroom-help-back').hidden], [['markdown'], 'Markdown', false]);
    check('its first row says how to write a heading', $('.kroom-help-page[data-section="markdown"] tr td code')?.textContent, '## Title');
    check('the model page knows the dialect', [...panel.querySelectorAll('.kroom-help-page[data-section="model"] code')].map(c => c.textContent).slice(0, 3),
        ['$name', '${name.field}', '%if($x) … %else … %end']);
    click('.kroom-help-back');
    check('← comes back to the landing', shown(), ['landing']);
    check('✓ sits last, ✗ before it, the trash and the status first', [...$('.kroom-editor-tools').children].map(e => e.className.split(' ')[0]),
        ['kroom-status', 'kroom-trash', 'kroom-cancel', 'kroom-submit']);
    window.confirm = () => true;
    const { calls: trashed, click: clickT, window: w2 } = page(() => held);
    w2.confirm = () => true;
    clickT('.kroom-edit');
    await sleep(20);
    clickT('.kroom-trash');
    await sleep(20);
    check('trash deletes the block', trashed.at(-1), { url: `/api/content/${PATH}`, method: 'DELETE', body: undefined });
    const unwritten = page(() => ({ payload: { body: '', rev: '', meta: {}, lock: { owner: 'admin' }, editable: true } }));
    unwritten.click('.kroom-edit');
    await sleep(20);
    check('a block nobody wrote has nothing to trash', unwritten.$('.kroom-trash'), null);
}

// --- drafts -------------------------------------------------------------------------------------
const KEY = `kroom.draft:admin:${PATH}`;
const seeded = (draft) => `localStorage.setItem(${JSON.stringify(KEY)}, ${JSON.stringify(JSON.stringify(draft))});`;
{
    const { click, $, window } = page(() => held);
    click('.kroom-edit');
    await sleep(20);
    check('while editing, no block offers its handle', window.document.documentElement.classList.contains('kroom-busy'), true);
    const textarea = $('.kroom-block textarea');
    textarea.value = '## Titre\n\nen cours';
    textarea.dispatchEvent(new window.Event('input', { bubbles: true }));
    const kept = JSON.parse(window.localStorage.getItem(KEY));
    check('what is typed is kept, with the rev it started from', [kept?.rev, kept?.body], ['abc123', '## Titre\n\nen cours']);
    const leaving = new window.Event('beforeunload', { cancelable: true });
    window.dispatchEvent(leaving);
    check('so leaving the page warns of nothing', leaving.defaultPrevented, false);
    textarea.value = '## Titre';
    textarea.dispatchEvent(new window.Event('input', { bubbles: true }));
    check('typing back to the stored text drops it', window.localStorage.getItem(KEY), null);
    textarea.value = '## Titre\n\nencore';
    textarea.dispatchEvent(new window.Event('input', { bubbles: true }));
    click('.kroom-cancel');
    await sleep(20);
    check('cancel drops it too', window.localStorage.getItem(KEY), null);
    check('and the handles are back', window.document.documentElement.classList.contains('kroom-busy'), false);
}
{
    // reloaded mid-edit: the lock is still ours
    const draft = { rev: 'old999', body: '## Titre\n\nmon brouillon', time: Date.now() };
    const { calls, click, $ } = page(() => held, seeded(draft));
    await sleep(50);
    check('a draft whose lock is still ours reopens its block', $('.kroom-block textarea')?.value, draft.body);
    check('saying so', $('.kroom-status')?.textContent, 'your unsaved draft is back \u2014 submit to write it');
    click('.kroom-submit');
    await sleep(20);
    check('and submits against the rev it started from', calls[calls.length - 1].body?.rev, 'old999');
}
{
    // an old draft: its lock is gone
    const draft = { rev: 'abc123', body: '## Titre\n\nun vieux brouillon', time: 1757000000000 };
    const log = { payload: [{ rev: 'abc123', path: PATH, author: 'admin', time: 1757000000000 }] };
    const { click, $, window } = page((url) =>
        url.includes('/history/') ? log : url.includes('/lock/') ? held : { payload: { ...held.payload, lock: null } }, seeded(draft));
    await sleep(50);
    check('an old draft opens nothing', $('.kroom-block textarea'), null);
    click('.kroom-edit');
    await sleep(20);
    check('the editor opens on the stored text', $('.kroom-block textarea')?.value, '## Titre');
    click('.kroom-tab[data-tab="history"]');
    await sleep(20);
    check('the draft heads the history', $('.kroom-past li .kroom-draft') !== null, true);
    click('.kroom-draft');
    await sleep(20);
    check('shown against the revision it started from', !!$('.kroom-past .kroom-diff'), true);
    click('.kroom-restore');
    check('and restored like one', $('.kroom-block textarea')?.value, draft.body);
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

// --- a picture dropped in: uploaded as it is, written in where the caret is ------------------------
{
    const uploaded = { payload: { name: 'x-club.png', url: '/media/x-club.png', type: 'image/png', size: 3 } };
    const { calls, click, $, window } = page((url) => url.includes('/media') ? uploaded : held);
    click('.kroom-edit');
    await sleep(20);
    check('the toolbar offers a picture', !!$('.kroom-format-image'), true);
    const textarea = $('.kroom-block textarea');
    textarea.setSelectionRange(textarea.value.length, textarea.value.length);
    const file = new window.File([new Uint8Array([1, 2, 3])], 'Club [photo].png', { type: 'image/png' });
    const drop = new window.Event('drop', { bubbles: true, cancelable: true });
    Object.defineProperty(drop, 'dataTransfer', { value: { files: [file], types: ['Files'] } });
    textarea.dispatchEvent(drop);
    await sleep(20);
    const post = calls.find(c => c.url.includes('/media'));
    check('the file is posted as it is, its name in the query', [post?.url, post?.body === file],
        ['/api/content/media?name=Club%20%5Bphoto%5D.png', true]);
    check('and comes back as markdown at the caret', textarea.value, '## Titre![Club photo](/media/x-club.png)');
}

console.log(failures ? `\n${failures} failure(s)` : '\nall good');
process.exit(failures ? 1 : 0);
