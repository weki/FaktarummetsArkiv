/* Delade konstanter och hjälpfunktioner (alla sidor). */
const API = '/api/chat';
const DOC_API = '/api/documents';

const ICONS = {
    check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
    warn: '<path d="M12 3l10 18H2z"/><path d="M12 10v5M12 18v.5"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 8v5M12 16v.5"/>',
    cross: '<path d="M6 6l12 12M18 6L6 18"/>',
    chevron: '<path d="M6 9l6 6 6-6"/>',
    copy: '<rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V6a2 2 0 012-2h9"/>',
    retry: '<path d="M20 12a8 8 0 11-2.3-5.7M20 4v5h-5"/>',
    thumb: '<path d="M7 11v9H4v-9zM7 11l4-8a2 2 0 012 2v4h6a2 2 0 012 2.3l-1.2 7A2 2 0 0117.8 20H7"/>',
    download: '<path d="M12 4v11M7 11l5 5 5-5M5 20h14"/>',
    external: '<path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 01-1 1H5a1 1 0 01-1-1V7a1 1 0 011-1h5"/>',
    spinner: '<path d="M12 3a9 9 0 109 9"/>'
};

function icon(name, size, extraClass) {
    const span = document.createElement('span');
    span.className = 'icon' + (extraClass ? ' ' + extraClass : '');
    span.setAttribute('aria-hidden', 'true');
    span.innerHTML = '<svg width="' + size + '" height="' + size + '" viewBox="0 0 24 24" fill="none" '
        + 'stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">'
        + ICONS[name] + '</svg>';
    return span;
}

function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = text;
    return node;
}

let qdrantCollections = null;

async function loadCollections(force) {
    if (qdrantCollections && !force) return qdrantCollections;
    try {
        const res = await fetch(DOC_API + '/collections');
        const data = await res.json().catch(() => null);
        qdrantCollections = Array.isArray(data) ? data : [];
    } catch (error) {
        qdrantCollections = [];
    }
    return qdrantCollections;
}

let knownTags = [];

async function loadTags() {
    try {
        const res = await fetch(DOC_API + '/tags');
        const data = await res.json().catch(() => null);
        knownTags = Array.isArray(data) ? data : [];
    } catch (error) {
        knownTags = [];
    }
    return knownTags;
}

function formatDate(value) {
    const date = new Date(value);
    if (isNaN(date.getTime())) return '';
    return date.toLocaleDateString('sv-SE') + ' ' +
        date.toLocaleTimeString('sv-SE', {hour: '2-digit', minute: '2-digit'});
}

function formatBytes(bytes) {
    if (!bytes) return '0 B';
    const units = ['B', 'kB', 'MB', 'GB'];
    let value = bytes;
    let i = 0;
    while (value >= 1024 && i < units.length - 1) {
        value /= 1024;
        i++;
    }
    return (i === 0 ? Math.round(value) : value.toFixed(1)) + ' ' + units[i];
}

/* ---------- Sanering av serverns markdown-HTML (allowlist, försvar på djupet) ---------- */
const SAFE_TAGS = new Set(['P', 'BR', 'STRONG', 'EM', 'B', 'I', 'CODE', 'PRE', 'UL', 'OL', 'LI', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6',
    'BLOCKQUOTE', 'TABLE', 'THEAD', 'TBODY', 'TR', 'TH', 'TD', 'A', 'HR', 'DEL', 'S']);
const DROP_TAGS = new Set(['SCRIPT', 'STYLE', 'IFRAME', 'OBJECT', 'EMBED', 'LINK', 'META', 'TEMPLATE', 'SVG', 'MATH',
    'FORM', 'INPUT', 'BUTTON', 'TEXTAREA', 'SELECT', 'IMG', 'VIDEO', 'AUDIO']);

function copySafeNodes(from, to) {
    for (const node of from.childNodes) {
        if (node.nodeType === 3) {
            to.appendChild(document.createTextNode(node.nodeValue));
        } else if (node.nodeType === 1) {
            if (DROP_TAGS.has(node.tagName.toUpperCase())) continue;
            if (!SAFE_TAGS.has(node.tagName)) { copySafeNodes(node, to); continue; }
            const copy = document.createElement(node.tagName.toLowerCase());
            if (node.tagName === 'A') {
                const href = node.getAttribute('href') || '';
                const number = node.getAttribute('data-source-number');
                if (node.classList.contains('citation') && /^\d{1,3}$/.test(number || '') && /^#source-\d{1,3}$/.test(href)) {
                    copy.className = 'citation';
                    copy.setAttribute('data-source-number', number);
                    copy.setAttribute('href', href);
                } else if (/^https?:\/\//i.test(href)) {
                    copy.setAttribute('href', href);
                    copy.setAttribute('target', '_blank');
                    copy.setAttribute('rel', 'noopener noreferrer');
                }
            }
            copySafeNodes(node, copy);
            to.appendChild(copy);
        }
    }
}

/** Skriver serverns HTML till target efter sanering. */
function setSafeHtml(target, html) {
    const doc = new DOMParser().parseFromString(String(html || ''), 'text/html');
    const fragment = document.createDocumentFragment();
    copySafeNodes(doc.body, fragment);
    target.textContent = '';
    target.appendChild(fragment);
}
