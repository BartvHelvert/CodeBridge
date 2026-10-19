// Put first in every page that may run scripts, ahead of the page's own content security policy,
// which only governs what follows it. What VS Code's webview host gives a page: `acquireVsCodeApi`,
// messages from the extension as `message` events, the theme's colours, and its links handled by
// the workbench rather than followed inside the page.
(function () {
    'use strict';
    const send = function (payload) { /*SEND*/ };
    let state = /*STATE*/undefined;
    /** @type {?{vars: Object<string, string>, kind: string, name: string}} */
    let theme = /*THEME*/null;
    // Whether the extension takes the `ArrayBuffer`s in a message as buffers (`serializeBuffersForPostMessage`).
    const binary = /*BINARY*/false;
    // Which of the workbench's loads of this webview this page is; named when it is ready.
    const load = /*LOAD*/0;
    let acquired = false;

    // The constructors a buffer reference's `view.type` names, as VS Code numbers them.
    const views = [null, Int8Array, Uint8Array, Uint8ClampedArray, Int16Array, Uint16Array, Int32Array,
        Uint32Array, Float32Array, Float64Array, BigInt64Array, BigUint64Array];

    function base64(buffer) {
        const bytes = new Uint8Array(buffer);
        let text = '';
        for (let i = 0; i < bytes.length; i += 0x8000) text += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
        return btoa(text);
    }

    // A message as VS Code's workbench hands it on: each `ArrayBuffer`, or typed array over one, a
    // reference to a buffer sent beside the JSON -- for an extension that asked for them so.
    function serialize(message) {
        const value = message === undefined ? null : message;
        if (!binary) return 'm' + JSON.stringify(value);
        const buffers = [];
        const index = function (buffer) {
            let at = buffers.indexOf(buffer);
            if (at < 0) { at = buffers.length; buffers.push(buffer); }
            return at;
        };
        const json = JSON.stringify(value, function (key, item) {
            if (item instanceof ArrayBuffer) return { $$vscode_array_buffer_reference$$: true, index: index(item) };
            if (ArrayBuffer.isView(item)) {
                // noinspection JSCheckFunctionSignatures -- any constructor may be looked up; unknown ones are -1
                const type = views.indexOf(item.constructor);
                if (type > 0) return {
                    $$vscode_array_buffer_reference$$: true, index: index(item.buffer),
                    view: { type: type, byteLength: item.byteLength, byteOffset: item.byteOffset },
                };
            }
            return item;
        });
        return buffers.length ? 'b' + JSON.stringify([json, buffers.map(base64)]) : 'm' + json;
    }

    const api = Object.freeze({
        postMessage: function (message) {
            send(serialize(message));
        },
        setState: function (newState) {
            state = newState;
            send('s' + JSON.stringify(newState === undefined ? null : newState));
            return newState;
        },
        getState: function () {
            return state;
        },
    });

    window.acquireVsCodeApi = function () {
        if (acquired) throw new Error('An instance of the VS Code API has already been acquired');
        acquired = true;
        return api;
    };

    // `ArrayBuffer`s the extension posted travel apart from the JSON, which names each by its index.
    function revive(json, buffers) {
        if (!buffers.length) return JSON.parse(json);
        const arrays = buffers.map(function (encoded) {
            const text = atob(encoded);
            const bytes = new Uint8Array(text.length);
            for (let i = 0; i < text.length; i++) bytes[i] = text.charCodeAt(i);
            return bytes.buffer;
        });
        return JSON.parse(json, function (key, value) {
            if (value && typeof value === 'object' && value['$$vscode_array_buffer_reference$$']) {
                const buffer = arrays[value.index];
                if (!value.view) return buffer;
                const View = views[value.view.type];
                return new View(buffer, value.view.byteOffset, value.view.byteLength / View.BYTES_PER_ELEMENT);
            }
            return value;
        });
    }

    window.__codeBridgeReceive = function (json, buffers) {
        window.dispatchEvent(new MessageEvent('message', { data: revive(json, buffers || []) }));
    };

    function applyTheme() {
        const root = document.documentElement;
        for (const name in theme.vars) root.style.setProperty(name, theme.vars[name]);
        const body = document.body;
        if (!body) return;
        body.classList.remove('vscode-light', 'vscode-dark', 'vscode-high-contrast', 'vscode-high-contrast-light');
        body.classList.add(theme.kind);
        body.dataset.vscodeThemeKind = theme.kind;
        body.dataset.vscodeThemeName = theme.name;
        body.dataset.vscodeThemeId = theme.name;
    }

    window.__codeBridgeTheme = function (next) {
        theme = next;
        applyTheme();
    };

    applyTheme();
    document.addEventListener('DOMContentLoaded', function () {
        applyTheme();
        send('r' + load);
    });

    // Links leave the page: web addresses for the browser, `command:` ones for the workbench to run
    // if the extension allows it. A link the page handles itself has had its default prevented.
    function followed(event) {
        if (event.defaultPrevented || (event.type === 'auxclick' && event.button !== 1)) return;
        let node = event.target;
        while (node && node.tagName !== 'A') node = node.parentElement;
        if (!node || !node.href) return;
        // An anchor in the page is the page's business; its address has a query of its own, so
        // the two are compared without their fragments.
        if (node.href.split('#')[0] === location.href.split('#')[0]) return;
        if (/^(https?|mailto|command|vscode|vscode-insiders):/i.test(node.href)) {
            event.preventDefault();
            send('l' + node.href);
        }
    }
    document.addEventListener('click', followed);
    document.addEventListener('auxclick', followed);

    // Find, as VS Code's find widget opens on it, unless the page handles the keys itself.
    document.addEventListener('keydown', function (event) {
        if (event.defaultPrevented || event.altKey || event.shiftKey || !(event.ctrlKey || event.metaKey)) return;
        if (event.key === 'f' || event.key === 'F') {
            event.preventDefault();
            send('f');
        }
    });
})();
