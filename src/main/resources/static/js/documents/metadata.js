/* Redigera källsystem och taggar för ett eller flera dokument: befintliga värden som
   klickbara chips, nya värden via textfält. Flera dokument = lägg till (union). */
const metaOverlay = document.getElementById('metaOverlay');
const metaModalTitle = document.getElementById('metaModalTitle');
const metaModalClose = document.getElementById('metaModalClose');
const metaBulkNote = document.getElementById('metaBulkNote');
const metaCollectionChips = document.getElementById('metaCollectionChips');
const metaCollectionNew = document.getElementById('metaCollectionNew');
const metaTagChips = document.getElementById('metaTagChips');
const metaTagNew = document.getElementById('metaTagNew');
const metaTagAdd = document.getElementById('metaTagAdd');
const metaSave = document.getElementById('metaSave');
const metaStatus = document.getElementById('metaStatus');

let metaDocs = [];
let metaCollection = '';
const metaTags = new Set();

function setMetaStatus(text, cls) {
    metaStatus.textContent = text;
    metaStatus.className = 'status' + (cls ? ' ' + cls : '');
}

async function openMetadataEditor(docs) {
    metaDocs = Array.isArray(docs) ? docs.slice() : [docs];
    if (!metaDocs.length) return;
    const bulk = metaDocs.length > 1;
    const shared = sharedCollection(metaDocs);
    metaCollection = bulk ? shared : (metaDocs[0].collection || '');
    metaTags.clear();
    if (!bulk) (metaDocs[0].tags || []).forEach(tag => metaTags.add(tag));
    metaModalTitle.textContent = bulk
        ? 'Metadata – ' + metaDocs.length + ' dokument'
        : 'Metadata – ' + (metaDocs[0].name || '');
    if (metaBulkNote) metaBulkNote.hidden = !bulk;
    metaCollectionNew.value = '';
    metaTagNew.value = '';
    setMetaStatus('', '');
    const [collections, tags] = await Promise.all([loadCollections(), loadTags(true)]);
    renderMetaCollections(collections || []);
    renderMetaTags(tags || []);
    metaOverlay.classList.remove('hidden');
}

function sharedCollection(docs) {
    const first = docs[0].collection || '';
    return docs.every(doc => (doc.collection || '') === first) ? first : '';
}

function closeMetadataEditor() {
    metaOverlay.classList.add('hidden');
    metaDocs = [];
}

function renderMetaCollections(collections) {
    const names = new Set(collections);
    if (metaCollection) names.add(metaCollection);
    metaCollectionChips.innerHTML = '';
    for (const name of names) {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'tag' + (name === metaCollection ? ' tag-active' : '');
        button.textContent = name;
        button.setAttribute('aria-pressed', String(name === metaCollection));
        button.addEventListener('click', () => {
            metaCollection = name;
            metaCollectionNew.value = '';
            renderMetaCollections(collections);
        });
        metaCollectionChips.appendChild(button);
    }
}

function renderMetaTags(existing) {
    const names = new Set(existing);
    metaTags.forEach(tag => names.add(tag));
    metaTagChips.innerHTML = '';
    for (const name of names) {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'tag' + (metaTags.has(name) ? ' tag-active' : '');
        button.textContent = name;
        button.setAttribute('aria-pressed', String(metaTags.has(name)));
        button.addEventListener('click', () => {
            if (metaTags.has(name)) metaTags.delete(name); else metaTags.add(name);
            renderMetaTags(existing);
        });
        metaTagChips.appendChild(button);
    }
}

function addNewMetaTag() {
    const value = metaTagNew.value.trim();
    if (!value) return;
    metaTags.add(value);
    metaTagNew.value = '';
    renderMetaTags(Array.from(knownTags));
}

metaCollectionNew.addEventListener('input', () => {
    const value = metaCollectionNew.value.trim();
    if (value) {
        metaCollection = value;
        renderMetaCollections(Array.from(qdrantCollections || []));
    }
});

metaTagNew.addEventListener('keydown', event => {
    if (event.key === 'Enter') {
        event.preventDefault();
        addNewMetaTag();
    }
});
metaTagAdd.addEventListener('click', addNewMetaTag);

metaSave.addEventListener('click', async () => {
    if (!metaDocs.length) return;
    const collection = (metaCollectionNew.value.trim() || metaCollection || '').trim();
    const bulk = metaDocs.length > 1;
    metaSave.disabled = true;
    setMetaStatus('Sparar...', '');
    try {
        for (const doc of metaDocs) {
            if (collection && collection !== (doc.collection || '')) {
                const res = await fetch(DOC_API + '/' + encodeURIComponent(doc.id) + '/collection', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json', 'X-Requested-With': 'FakturummetsArkiv'},
                    body: JSON.stringify({collection})
                });
                const data = await res.json().catch(() => ({}));
                if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
            }
            const currentTags = new Set(doc.tags || []);
            const targetTags = bulk ? union(currentTags, metaTags) : metaTags;
            if (!sameTagSet(currentTags, targetTags)) {
                const res = await fetch(DOC_API + '/' + encodeURIComponent(doc.id) + '/tags', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json', 'X-Requested-With': 'FakturummetsArkiv'},
                    body: JSON.stringify({tags: Array.from(targetTags)})
                });
                const data = await res.json().catch(() => ({}));
                if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
            }
        }
        qdrantCollections = null;
        const count = metaDocs.length;
        closeMetadataEditor();
        await loadDocuments();
        if (count > 1) setDocumentsStatus('Metadata uppdaterad för ' + count + ' dokument', 'ok');
    } catch (error) {
        setMetaStatus('Fel: ' + error.message, 'err');
    } finally {
        metaSave.disabled = false;
    }
});

function union(left, right) {
    const set = new Set(left);
    right.forEach(value => set.add(value));
    return set;
}

function sameTagSet(left, right) {
    if (left.size !== right.size) return false;
    for (const value of left) if (!right.has(value)) return false;
    return true;
}

metaModalClose.addEventListener('click', closeMetadataEditor);
metaOverlay.addEventListener('click', event => {
    if (event.target === metaOverlay) closeMetadataEditor();
});
window.addEventListener('keydown', event => {
    if (event.key === 'Escape' && !metaOverlay.classList.contains('hidden')) closeMetadataEditor();
});
