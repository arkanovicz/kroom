// domhelper.js - lightweight jQuery-like DOM helper
// Part of kroom-webapp-assets

window.$ = (selector, context = document) => {
  const nodes = context.querySelectorAll(selector);
  return nodes.length === 1 ? nodes[0] : nodes;
};

window.$$ = (selector, context = document) => {
  return context.querySelectorAll(selector);
};

NodeList.prototype.__proto__ = Array.prototype;

// Allow single Element to be treated as collection (forEach, map, filter)
Element.prototype.forEach = function(fn) { fn(this, 0); return this; };
Element.prototype.map = function(fn) { return [fn(this, 0)]; };
Element.prototype.filter = function(fn) { return fn(this, 0) ? [this] : []; };

// Events

Node.prototype.on = window.on = function (eventNames, fn) {
  let events = eventNames.split(' ')
  for (let i = 0; i < events.length; ++i) {
    let name = events[i];
    this.addEventListener(name, fn);
  }
  return this;
};
NodeList.prototype.on = NodeList.prototype.addEventListener = function (eventNames, fn) {
  this.forEach(function (elem, i) {
    elem.on(eventNames, fn);
  });
  return this;
}

// Classes

NodeList.prototype.addClass = function(className) {
  this.forEach(function (elem, i) {
    elem.addClass(className);
  });
  return this;
}
Element.prototype.addClass = function(className) {
  className.split(/\s+/).forEach(c => { if (c) this.classList.add(c); });
  return this;
}
NodeList.prototype.removeClass = function(className) {
  this.forEach(function (elem, i) {
    elem.removeClass(className);
  });
  return this;
}
Element.prototype.removeClass = function(className) {
  className.split(/\s+/).forEach(c => { if (c) this.classList.remove(c); });
  return this;
}
NodeList.prototype.toggleClass = function(className) {
  this.forEach(function (elem, i) {
    elem.classList.toggle(className);
  });
  return this;
}
Element.prototype.toggleClass = function(className) {
  this.classList.toggle(className);
  return this;
}
NodeList.prototype.hasClass = function(className) {
  if (this.length === 0) return false;
  return this.item(0).classList.contains(className);
}
Element.prototype.hasClass = function(className) {
  return this.classList.contains(className);
}

// Offsets & Dimensions

Node.prototype.offset = function() {
  let _x = 0;
  let _y = 0;
  let el = this;
  while( el && !isNaN( el.offsetLeft ) && !isNaN( el.offsetTop ) ) {
    _x += el.offsetLeft - el.scrollLeft;
    _y += el.offsetTop - el.scrollTop;
    el = el.offsetParent;
  }
  return { top: _y, left: _x };
}
NodeList.prototype.offset = function() {
  return this.item(0).offset();
}

// Attributes

Element.prototype.attr = function (key, value) {
  if (typeof(value) === 'undefined') {
    return this.attributes[key]?.value;
  } else {
    this.setAttribute(key, value);
    return this;
  }
}
NodeList.prototype.attr = function(key, value) {
  if (typeof(value) === 'undefined') {
    return this.item(0).attr(key);
  } else {
    this.forEach(elem => {
      elem.attr(key, value);
    });
    return this;
  }
}
Element.prototype.removeAttr = function (key) {
  this.removeAttribute(key);
  return this;
}
NodeList.prototype.removeAttr = function(key) {
  this.forEach(elem => {
    elem.removeAttr(key);
  });
  return this;
}


// Properties

Element.prototype.prop = function (key, value) {
  if (typeof(value) === 'undefined') {
    return this[key];
  } else {
    this[key] = value;
    return this;
  }
}
NodeList.prototype.prop = function(key, value) {
  if (typeof(value) === 'undefined') {
    return this.item(0).prop(key);
  } else {
    this.forEach(elem => {
      elem.prop(key, value);
    });
    return this;
  }
}

// Data

Element.prototype.data = function (key, value) {
  if (typeof(value) === 'undefined') {
    return this.attributes[`data-${key}`]?.value
  } else {
    this.setAttribute(`data-${key}`, value);
    return this;
  }
}
NodeList.prototype.data = function(key, value) {
  if (typeof(value) === 'undefined') {
    return this.item(0).data(key);
  } else {
    this.forEach(elem => {
      elem.data(key, value);
    })
    return this;
  }
}

// Visibility

NodeList.prototype.show = function() {
  this.forEach(elem => {
    elem.show();
  });
  return this;
}
Element.prototype.show = function() {
  this.removeAttribute('hidden');
  this.style.display = '';
  return this;
}
NodeList.prototype.hide = function() {
  this.forEach(elem => {
    elem.hide();
  });
  return this;
}
Element.prototype.hide = function() {
  this.style.display = 'none';
  return this;
}

// State

NodeList.prototype.disable = function(value) {
  this.forEach(elem => {
    elem.disable(value);
  });
  return this;
}
Element.prototype.disable = function(value) {
  this.disabled = typeof(value) === 'undefined' ? true : Boolean(value);
  return this;
}

// Text

NodeList.prototype.text = function(txt) {
  if (typeof(txt) === 'undefined') {
    return this.item(0).text();
  } else {
    this.forEach(elem => {
      elem.text(txt);
    });
    return this;
  }
}
Element.prototype.text = function(txt) {
  if (typeof(txt) === 'undefined') {
    return this.textContent;
  } else {
    this.textContent = txt;
    return this;
  }
}

// HTML

NodeList.prototype.html = function(txt) {
  if (typeof(txt) === 'undefined') {
    return this.item(0).html();
  } else {
    this.forEach(elem => {
      elem.html(txt);
    });
    return this;
  }
}
Element.prototype.html = function(txt) {
  if (typeof(txt) === 'undefined') {
    return this.innerHTML;
  } else {
    this.innerHTML = txt;
    return this;
  }
}

// Children

NodeList.prototype.item = function (i) {
  return this[+i || 0];
};
NodeList.prototype.find = function(selector) {
  let result = [];
  this.forEach(function (elem, i) {
    let partial = elem.find(selector);
    result = result.concat([...partial]);
  });
  return result.length === 1 ? result[0] : Reflect.construct(Array, result, NodeList);
}
Element.prototype.find = function(selector) {
  let result = this.querySelectorAll(':scope ' + selector);
  return result.length === 1 ? result[0] : result;
}
NodeList.prototype.clear = function() {
  this.forEach(function (elem, i) {
    elem.clear();
  });
  return this;
}
Element.prototype.clear = function() {
  while (this.firstChild) this.removeChild(this.lastChild);
  return this;
}
NodeList.prototype.append = function(value) {
  this.forEach(function (elem, i) {
    elem.append(value);
  });
  return this;
}

// Value

NodeList.prototype.val = function(value) {
  if (typeof(value) === 'undefined') {
    if (this instanceof RadioNodeList) {
      return this.value;
    } else {
      return this.item(0).val(value);
    }
  } else {
    if (this instanceof RadioNodeList) {
      this.value = value;
    } else {
      this.forEach(elem => {
        elem.val(value);
      });
    }
    return this;
  }
}
Element.prototype.val = function(value) {
  if (typeof(value) === 'undefined') {
    return this.value;
  } else {
    this.value = value;
    return this;
  }
}

// Misc

NodeList.prototype.focus = function() {
  let first = this.item(0);
  if (first) first.focus();
  return this;
}

Element.prototype.click = function() {
  this.dispatchEvent(new Event('click'));
  return this;
}
NodeList.prototype.click = function() {
  let first = this.item(0);
  if (first) first.click();
  return this;
}

Element.prototype.index = function(selector) {
  let i = 0;
  let child = this;
  while ((child = child.previousSibling) != null) {
    if (typeof(selector) === 'undefined' || child.nodeType === Node.ELEMENT_NODE && child.matches(selector)) {
      ++i;
    }
  }
  return i;
}

NodeList.prototype.filter = function(selector) {
  let result = [];
  this.forEach(elem => {
    if (elem.nodeType === Node.ELEMENT_NODE && elem.matches(selector)) {
      result.push(elem);
    }
  });
  return Reflect.construct(Array, result, NodeList);
}

HTMLFormElement.prototype.field = function(name, value) {
  let hasValue = typeof(value) !== 'undefined';
  let ctl = this.find(`[name="${name}"]`);
  if (!ctl) {
    console.warn(`unknown input name: ${name}`)
    return undefined
  }
  ctl = ctl instanceof NodeList ? ctl[0] : ctl;
  let tag = ctl.tagName;
  let type = tag === 'INPUT' ? ctl.attr('type') : undefined;
  if (
    (tag === 'INPUT' && ['text', 'number', 'hidden', 'password'].includes(ctl.attr('type'))) ||
    tag === 'SELECT'
  ) {
    if (hasValue) {
      ctl.value = value;
      return;
    }
    else return ctl.value;
  } else if (tag === 'INPUT' && ctl.attr('type') === 'radio') {
    if (hasValue) {
      ctl = this.find(`input[name="${name}"][value="${value}"]`);
      if (ctl) {
        ctl = ctl instanceof NodeList ? ctl[0] : ctl;
        ctl.checked = true;
      }
      return;
    } else {
      ctl = $(`input[name="${name}"]:checked`);
      if (ctl) {
        ctl = ctl instanceof NodeList ? ctl[0] : ctl;
        return ctl.value;
      }
      else return null;
    }
  } else if (tag === 'INPUT' && ctl.attr('type') === 'checkbox') {
    if (hasValue) {
      ctl.checked = value !== 'false' && Boolean(value);
      return;
    }
    else return ctl.checked && ctl.value ? ctl.value : ctl.checked;
  }
  console.error(`unhandled input tag or type for input ${name} (tag: ${tag}, type:${type}`);
  return null;
}

Element.prototype.load = function(path) {
  return api.getHtml(path)
    .then(html => {
      this.innerHTML = html;
      return this;
    })
    .finally(() => {
      this.busy(false);
    });
}

NodeList.prototype.load = function(path) {
  let first = this.item(0);
  if (first) {
    return first.load(path);
  }
}

NodeList.prototype.empty = function() {
  this.forEach(function (elem, i) {
    elem.empty();
  });
  return this;
}
Element.prototype.empty = function() {
  this.innerHTML = '';
  return this;
}

NodeList.prototype.showModal = function() {
  let first = this.item(0);
  if (first) first.showModal();
  return this;
}

NodeList.prototype.busy = function(value) {
  this.forEach(function (elem, i) {
    elem.busy(value);
  });
  return this;
}

Element.prototype.busy = function(value) {
  const set = typeof(value) === 'undefined' ? true : Boolean(value);
  this.classList.toggle('busy', set);
  return this;
}

NodeList.prototype.close = function() {
  this.forEach(function (elem, i) {
    elem.close();
  });
  return this;
}
