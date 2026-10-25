/* Kollektionsfliken. */
const collectionList = document.getElementById('collectionList');
const collectionsStatus = document.getElementById('collectionsStatus');
const collectionsSave = document.getElementById('collectionsSave');
const newCollectionInput = document.getElementById('newCollectionInput');
const createCollectionBtn = document.getElementById('createCollectionBtn');

function renderCollections(available, selected) {
    const chosen = new Set(Array.isArray(selected) ? selected : []);
    const names = new Set(available || []);
    chosen.forEach(name => names.add(name));
    collectionList.innerHTML = '';
    if (!names.size) {
        const empty = document.createElement('div');
        empty.className = 'collection-item';
        const text = document.createElement('span');
        text.className = 'name';
        text.textContent = 'Inga kollektioner hittades';
        empty.appendChild(text);
        collectionList.appendChild(empty);
        return;
    }
    for (const name of names) {
        const label = document.createElement('label');
        label.className = 'collection-item';
        const checkbox = document.createElement('input');
        checkbox.type = 'checkbox';
        checkbox.value = name;
        checkbox.checked = chosen.has(name);
        const text = document.createElement('span');
        text.className = 'name';
        text.textContent = name;
        label.appendChild(checkbox);
        label.appendChild(text);
        collectionList.appendChild(label);
    }
}

async function loadCollectionsTab() {
    setCollectionsStatus('Laddar...', '');
    try {
        const [collections, settingsRes] = await Promise.all([loadCollections(), fetch(API + '/settings')]);
        const settings = await settingsRes.json().catch(() => ({}));
        renderCollections(collections, settings.collections);
        setCollectionsStatus('', '');
    } catch (error) {
        setCollectionsStatus('Kunde inte hämta: ' + error.message, 'err');
    }
}

function setCollectionsStatus(text, cls) {
    collectionsStatus.textContent = text;
    collectionsStatus.className = 'status' + (cls ? ' ' + cls : '');
}

collectionsSave.addEventListener('click', async () => {
    collectionsSave.disabled = true;
    setCollectionsStatus('Sparar...', '');
    const collections = Array.from(
        collectionList.querySelectorAll('input[type=checkbox]:checked')).map(cb => cb.value);
    try {
        const res = await fetch(API + '/settings', {
            method: 'POST',
            headers: {'Content-Type': 'application/json', 'X-Requested-With': 'FakturummetsArkiv'},
            body: JSON.stringify({collections: collections})
        });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
        setCollectionsStatus('Sparat', 'ok');
    } catch (error) {
        setCollectionsStatus('Fel: ' + error.message, 'err');
    } finally {
        collectionsSave.disabled = false;
    }
});

createCollectionBtn.addEventListener('click', async () => {
    const name = newCollectionInput.value.trim();
    if (!name) return;
    createCollectionBtn.disabled = true;
    setCollectionsStatus('Skapar...', '');
    try {
        const res = await fetch(DOC_API + '/collections', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({name})
        });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
        newCollectionInput.value = '';
        qdrantCollections = Array.isArray(data) ? data : null;
        setCollectionsStatus('Skapad', 'ok');
        renderCollections(await loadCollections(true), getSelectedCollections());
    } catch (error) {
        setCollectionsStatus('Fel: ' + error.message, 'err');
    } finally {
        createCollectionBtn.disabled = false;
    }
});

function getSelectedCollections() {
    return Array.from(collectionList.querySelectorAll('input[type=checkbox]:checked')).map(cb => cb.value);
}
