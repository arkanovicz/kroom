// i18n.js - t() translates a hand-written script's English text through window.i18n (msgid -> translation)
//
// Usage:
//   t('Hello {name}', {name: 'Ann'})        // named placeholders
//   t('{0} wins against {1}', alice, bob)   // positional placeholders

window.t = function (en, ...args) {
    // read at each call: the catalog may be declared after this script
    const catalog = window.i18n;
    const text = catalog && typeof catalog === 'object' && typeof catalog[en] === 'string' && catalog[en] ? catalog[en] : en;
    const vars = args.length === 1 && args[0] !== null && typeof args[0] === 'object' ? args[0] : Object.assign({}, args);
    return text.replace(/\{(\w+)\}/g, (placeholder, name) =>
        Object.prototype.hasOwnProperty.call(vars, name) && vars[name] != null ? vars[name] : placeholder);
};
