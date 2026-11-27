// What markdown.js does to a textarea, checked against a fake DOM. jsdom has no execCommand('insertText'):
// the fallback is what runs here. Not part of `gradle test`: `./smoke.sh` here (node runs in docker).

import { JSDOM } from 'jsdom';
import { readFileSync } from 'node:fs';

const ASSETS = '../../main/resources/static/js';

let failures = 0;
const check = (what, got, want) => {
    const ok = JSON.stringify(got) === JSON.stringify(want);
    if (!ok) failures++;
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}${ok ? '' : `\n     got  ${JSON.stringify(got)}\n     want ${JSON.stringify(want)}`}`);
};

/** A page holding one textarea, the scripts loaded as a browser loads them (one shared scope). */
function page(preset = '') {
    const dom = new JSDOM(`<!doctype html><html><body><textarea></textarea></body></html>`,
        { runScripts: 'outside-only', url: 'http://localhost/' });
    const { window } = dom;
    window.eval(['domhelper.js', 'keys.js', 'markdown.js'].map(f => readFileSync(`${ASSETS}/${f}`, 'utf8'))
        .concat(preset).join('\n;\n'));
    const textarea = window.document.querySelector('textarea');
    const select = (text, from, to = from) => { textarea.value = text; textarea.setSelectionRange(from, to); };
    const selected = () => textarea.value.slice(textarea.selectionStart, textarea.selectionEnd);
    const key = (k, init = { ctrlKey: true }) => new window.KeyboardEvent('keydown', { key: k, cancelable: true, ...init });
    return { window, md: window.kroomMarkdown, textarea, select, selected, key };
}

// --- the formats ----------------------------------------------------------------------------------
{
    const { md, textarea, select, selected } = page();
    const apply = (name) => md.formats.find(f => f.name === name).apply(textarea);
    check('the formats, in the toolbar\'s order', md.formats.map(f => f.name),
        ['bold', 'italic', 'heading', 'link', 'bullets', 'numbers', 'quote', 'code']);

    select('un mot ici', 3, 6);
    apply('bold');
    check('bold wraps the selection, and keeps it selected', [textarea.value, selected()], ['un **mot** ici', 'mot']);
    apply('bold');
    check('bold again takes it off', [textarea.value, selected()], ['un mot ici', 'mot']);
    select('un ', 3);
    apply('italic');
    check('nothing selected: the placeholder, selected to type over', [textarea.value, selected()], ['un _italic_', 'italic']);

    select('a\nb\nc', 0, 3);
    apply('bullets');
    check('a list marks every line touched', [textarea.value, textarea.selectionStart, textarea.selectionEnd], ['- a\n- b\nc', 2, 7]);
    textarea.setSelectionRange(0, 7);
    apply('bullets');
    check('and unmarks them when all are marked', textarea.value, 'a\nb\nc');
    select('a\nb\nc', 0, 5);
    apply('numbers');
    check('a numbered list counts', textarea.value, '1. a\n2. b\n3. c');
    textarea.setSelectionRange(0, textarea.value.length);
    apply('numbers');
    check('and uncounts', textarea.value, 'a\nb\nc');

    select('voir https://kroom.org', 5, 22);
    apply('link');
    check('a selected address becomes the link, its text selected', [textarea.value, selected()],
        ['voir [text](https://kroom.org)', 'text']);
    select('voir ici', 5, 8);
    apply('link');
    check('a selected word becomes the text, the address selected', [textarea.value, selected()],
        ['voir [ici](https://)', 'https://']);

    select('un mot', 3, 6);
    apply('code');
    check('code within a line is inline', textarea.value, 'un `mot`');
    select('a\nb', 0, 3);
    apply('code');
    check('code over several lines is fenced', [textarea.value, selected()], ['```\na\nb\n```', 'a\nb']);
}

// --- every edit is heard as typing -----------------------------------------------------------------
{
    const { window, md, textarea, select } = page();
    const heard = [];
    textarea.addEventListener('input', e => heard.push([e instanceof window.InputEvent, e.inputType, textarea.value, textarea.selectionStart]));
    select('abc', 1, 2);
    md.replace(textarea, 1, 2, 'XY');
    check('the fallback dispatches input, the edit and the caret done', heard, [[true, 'insertText', 'aXYc', 3]]);
}

// --- shortcuts ----------------------------------------------------------------------------------------
{
    const { window, md, textarea, select, key } = page();
    let extra = 0;
    const off = md.keys('editor', textarea, textarea, [{ name: 'emoji', key: 'e', apply: () => extra++ }]);
    const press = (event) => { textarea.dispatchEvent(event); return event.defaultPrevented; };
    select('mot', 0, 3);
    textarea.focus();
    check('Ctrl+B is handled, the text bold', [press(key('b')), textarea.value], [true, '**mot**']);
    check('an unbound key is not', press(key('q')), false);
    check('nor a bound one without Ctrl', press(key('b', {})), false);
    check('nor with Shift', press(key('b', { ctrlKey: true, shiftKey: true })), false);
    check('Cmd counts as Ctrl', [press(key('i', { metaKey: true })), textarea.value], [true, '**_mot_**']);
    check('an extra format\'s key, applied', [press(key('e')), extra], [true, 1]);
    textarea.blur();
    check('the focus elsewhere, the key is the browser\'s', press(key('b')), false);
    textarea.focus();
    check('registered with kroomKeys', window.kroomKeys.list().map(c => c.key), ['Mod+b', 'Mod+i', 'Mod+k', 'Mod+e']);
    off();
    check('taken back', [window.kroomKeys.list().length, press(key('b'))], [0, false]);
}

// --- the toolbar ----------------------------------------------------------------------------------------
{
    const { window, md, textarea, select } = page();
    const pick = [];
    const bar = md.toolbar(textarea, { extra: [{ name: 'image', apply: ta => pick.push(ta) }] });
    window.document.body.prepend(bar);
    const buttons = [...bar.querySelectorAll('button')];
    check('a div.kroom-format', [bar.tagName, bar.className], ['DIV', 'kroom-format']);
    check('one button per format, the extra last', buttons.map(b => b.className),
        ['bold', 'italic', 'heading', 'link', 'bullets', 'numbers', 'quote', 'code', 'image'].map(n => `kroom-format-${n}`));
    check('each drawn by its pictogram', buttons.every(b => b.querySelector('svg.kroom-icon path')?.getAttribute('d')), true);
    check('and named by the stock words', [buttons[0].title, buttons[0].getAttribute('aria-label')], ['bold (Ctrl+B)', 'bold (Ctrl+B)']);
    check('an extra without words is named by its name', buttons[8].title, 'image');

    const down = new window.MouseEvent('mousedown', { bubbles: true, cancelable: true });
    buttons[0].dispatchEvent(down);
    check('pressing a button leaves the focus where it is', down.defaultPrevented, true);
    select('mot', 0, 3);
    buttons[0].dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    check('a click applies its format', textarea.value, '**mot**');
    buttons[8].dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    check('the extra one too, on the textarea', pick[0] === textarea, true);

    let target = null;
    const lazy = md.toolbar(() => target);
    select('mot', 0, 3);
    lazy.querySelector('.kroom-format-bold').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    check('a target answering none: nothing happens', textarea.value, 'mot');
    target = textarea;
    lazy.querySelector('.kroom-format-bold').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    check('answering one: it is the one acted on', textarea.value, '**mot**');

    const own = md.toolbar(textarea, {
        strings: { bold: 'fat', boldText: 'heavy', emoji: 'smile' }, icons: { bold: 'M0 0h24', emoji: 'M1 1h1' },
        extra: [{ name: 'emoji', apply: () => {} }]
    });
    check('words and pictograms given override the stock',
        [own.querySelector('.kroom-format-bold').title, own.querySelector('.kroom-format-bold path').getAttribute('d')], ['fat', 'M0 0h24']);
    check('and name the extra', [own.querySelector('.kroom-format-emoji').title, own.querySelector('.kroom-format-emoji path').getAttribute('d')],
        ['smile', 'M1 1h1']);
    check('the others keep theirs', own.querySelector('.kroom-format-italic').title, 'italic (Ctrl+I)');
    select('', 0);
    own.querySelector('.kroom-format-bold').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    check('what a button writes is given too', textarea.value, '**heavy**');

    const fr = md.toolbar(textarea, { language: 'fr' });
    check('a language asked for', fr.querySelector('.kroom-format-bullets').title, 'liste à puces');
    select('', 0);
    fr.querySelector('.kroom-format-link').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    check('writes in it', textarea.value, '[texte](https://)');
}

// --- languages, and loading twice ------------------------------------------------------------------------
{
    const { md } = page();
    check('French says every word English does', Object.keys(md.stock.fr).sort(), Object.keys(md.stock.en).sort());
    check('a jsdom browser (en-US) gets English', [md.language, md.strings.numbers], ['en', 'numbered list']);
    const french = page(`Object.defineProperty(window.navigator, 'languages', { value: ['fr-FR', 'en'], configurable: true });
        ${readFileSync(`${ASSETS}/markdown.js`, 'utf8')}`);
    check('loaded twice, the first stays', french.md.language, 'en');
    const browser = new JSDOM('<!doctype html>', { runScripts: 'outside-only' }).window;
    Object.defineProperty(browser.navigator, 'languages', { value: ['fr-FR', 'en'], configurable: true });
    browser.eval(readFileSync(`${ASSETS}/markdown.js`, 'utf8'));
    check('a French browser gets French', [browser.kroomMarkdown.language, browser.kroomMarkdown.strings.bold], ['fr', 'gras (Ctrl+B)']);
}

console.log(failures ? `\n${failures} FAILED` : '\nall green');
process.exit(failures ? 1 : 0);
