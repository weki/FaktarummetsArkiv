/* Dokumentvisare (popup) som används av chatten och dokumentsidan. */
const docOverlay = document.getElementById('docOverlay');
const docModalClose = document.getElementById('docModalClose');
const docModalTitle = document.getElementById('docModalTitle');
const docModalMeta = document.getElementById('docModalMeta');
const docModalActions = document.getElementById('docModalActions');
const docModalFrame = document.getElementById('docModalFrame');
const docModalText = document.getElementById('docModalText');

function docPdfUrl(ref) {
    if (!ref.documentId) return null;
    const url = DOC_API + '/' + encodeURIComponent(ref.documentId) + '/pdf';
    return ref.page !== undefined && ref.page !== null ? url + '#page=' + encodeURIComponent(ref.page) : url;
}

function docDownloadUrl(ref) {
    return ref.documentId ? DOC_API + '/' + encodeURIComponent(ref.documentId) + '/download' : null;
}

function documentMeta(doc) {
    let meta = (doc.chunks || 0) + ' textstycken \u00b7 ' + (doc.characters || 0) + ' tecken';
    if (doc.collection) meta += ' \u00b7 ' + doc.collection;
    if (doc.tags && doc.tags.length) meta += ' \u00b7 ' + doc.tags.join(', ');
    return meta;
}

function setDocModalMeta(text) {
    docModalMeta.textContent = text || '';
    docModalMeta.classList.toggle('hidden', !text);
}

function showDocModalText(text) {
    docModalText.innerHTML = '';
    if (Array.isArray(text)) {
        if (!text.length) docModalText.textContent = 'Inget textinneh\u00e5ll.';
        for (const chunk of text) {
            const block = document.createElement('div');
            block.className = 'doc-chunk';
            if (chunk.page !== null && chunk.page !== undefined) {
                const page = document.createElement('div');
                page.className = 'doc-chunk-page';
                page.textContent = 'Sida ' + chunk.page;
                block.appendChild(page);
            }
            const body = document.createElement('div');
            body.textContent = chunk.text || '';
            block.appendChild(body);
            docModalText.appendChild(block);
        }
    } else {
        docModalText.textContent = text || 'Inget textinneh\u00e5ll.';
    }
    docModalText.classList.remove('hidden');
    docModalFrame.classList.add('hidden');
}

function showDocModalPdf(url, text) {
    docModalText.classList.add('hidden');
    docModalText.innerHTML = '';
    docModalFrame.src = url;
    docModalFrame.classList.remove('hidden');
    if (!text) return;
    const toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.textContent = 'Visa text';
    toggle.addEventListener('click', () => {
        const showText = docModalText.classList.contains('hidden');
        if (showText) showDocModalText(text);
        else {
            docModalText.classList.add('hidden');
            docModalFrame.classList.remove('hidden');
        }
        toggle.textContent = showText ? 'Visa PDF' : 'Visa text';
    });
    docModalActions.insertBefore(toggle, docModalActions.firstChild);
}

async function openDocumentPopup(ref) {
    docOverlay.classList.remove('hidden');
    docModalTitle.textContent = ref.title || 'Dokument';
    setDocModalMeta(ref.meta);
    docModalActions.innerHTML = '';
    docModalFrame.removeAttribute('src');
    docModalFrame.classList.add('hidden');
    docModalText.classList.add('hidden');
    docModalText.innerHTML = '';

    if (ref.documentId && !ref.text) {
        setDocModalMeta('Laddar...');
        try {
            const res = await fetch(DOC_API + '/' + encodeURIComponent(ref.documentId));
            const doc = await res.json().catch(() => ({}));
            if (!res.ok) throw new Error(doc.error || ('HTTP ' + res.status));
            ref = Object.assign({}, ref, {
                title: ref.title || doc.name,
                meta: ref.meta || documentMeta(doc),
                text: ref.text || doc.content || null
            });
        } catch (error) {
            setDocModalMeta('Kunde inte h\u00e4mta: ' + error.message);
        }
    }
    if (ref.title) docModalTitle.textContent = ref.title;
    setDocModalMeta(ref.meta);

    const download = docDownloadUrl(ref);
    if (download) {
        const link = document.createElement('a');
        link.href = download;
        link.textContent = 'Ladda ner';
        docModalActions.appendChild(link);
    }

    const pdf = docPdfUrl(ref);
    if (pdf) showDocModalPdf(pdf, ref.text);
    else showDocModalText(ref.text);
}

function closeDocumentPopup() {
    docOverlay.classList.add('hidden');
    docModalFrame.removeAttribute('src');
    docModalFrame.classList.add('hidden');
    docModalText.classList.add('hidden');
    docModalText.innerHTML = '';
    docModalActions.innerHTML = '';
}

docModalClose.addEventListener('click', closeDocumentPopup);
docOverlay.addEventListener('click', (event) => {
    if (event.target === docOverlay) closeDocumentPopup();
});
