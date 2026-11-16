// What i18n.js's t() answers, against a fake DOM. Not part of `gradle test`: `./smoke.sh` here (node runs in docker).

import { JSDOM } from 'jsdom';
import { readFileSync } from 'node:fs';

const ASSETS = '../../main/resources/static/js';

let failures = 0;
const check = (what, got, want) => {
    const ok = JSON.stringify(got) === JSON.stringify(want);
    if (!ok) failures++;
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}${ok ? '' : `\n     got  ${JSON.stringify(got)}\n     want ${JSON.stringify(want)}`}`);
};

/** A page with i18n.js loaded, `preset` run before it. */
function page(preset = '') {
    const { window } = new JSDOM('<!doctype html>', { runScripts: 'outside-only', url: 'http://localhost/' });
    window.eval([preset, readFileSync(`${ASSETS}/i18n.js`, 'utf8')].join('\n;\n'));
    return window;
}

{
    const window = page(`window.i18n = { 'Hello': 'Bonjour', 'Hello {name}': 'Bonjour {name}',
        '{0} wins against {1}': '{0} bat {1}', 'Blank': '' };`);
    const t = window.t;
    check('a translated key', t('Hello'), 'Bonjour');
    check('a missing key answers the English', t('Goodbye'), 'Goodbye');
    check('an empty translation answers the English', t('Blank'), 'Blank');
    check('named placeholders, filled after the lookup', t('Hello {name}', { name: 'Ann' }), 'Bonjour Ann');
    check('positional placeholders', t('{0} wins against {1}', 'Ann', 'Bob'), 'Ann bat Bob');
    check('a placeholder without a value is left', t('Hello {name}', { other: 'x' }), 'Bonjour {name}');
    check('a positional one too', t('{0} wins against {1}', 'Ann'), 'Ann bat {1}');
    check('no argument leaves them all', t('{0} wins against {1}'), '{0} bat {1}');
}

{
    const window = page();
    check('no catalog at all answers the English', window.t('Hello {name}', { name: 'Ann' }), 'Hello Ann');
    window.i18n = { 'Hello {name}': 'Salut {name}' };
    check('a catalog declared after the script is seen', window.t('Hello {name}', { name: 'Ann' }), 'Salut Ann');
}

console.log(failures ? `\n${failures} FAILED` : '\nall green');
process.exit(failures ? 1 : 0);
