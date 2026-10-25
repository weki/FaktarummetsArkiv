/* Val av kollektioner/dokument för en tråd. */
const threadDocOverlay = document.getElementById('threadDocOverlay');
const threadDocClose = document.getElementById('threadDocClose');
const threadDocCollectionList = document.getElementById('threadDocCollectionList');
const threadDocList = document.getElementById('threadDocList');
const threadDocSearch = document.getElementById('threadDocSearch');
const threadDocFilterCollection = document.getElementById('threadDocFilterCollection');
const threadDocSave = document.getElementById('threadDocSave');
const threadDocStatus = document.getElementById('threadDocStatus');

let threadDocTargetId = null;
let scopeDocuments = [];

function openThreadDocSelector(threadId) {
    threadDocTargetId = threadId;
    threadDocOverlay.classList.remove('hidden');
    threadDocOverlay.querySelectorAll('.tab-btn').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.tab === 'collections');
    });
    threadDocOverlay.querySelectorAll('.tab-panel').forEach(panel => {
        panel.classList.toggle('active', panel.dataset.panel === 'collections');
    });
    threadDocSearch.value = '';
    threadDocFilterCollection.value = '';
    loadThreadDocCollections();
    loadThreadDocDocuments();
}

function closeThreadDocSelector() {
    threadDocOverlay.classList.add('hidden');
    threadDocTargetId = null;
    threadDocCollectionList.innerHTML = '';
    threadDocList.innerHTML = '';
    threadDocStatus.textContent = '';
    threadDocStatus.className = 'status';
}

function switchThreadDocTab(name) {
    threadDocOverlay.querySelectorAll('.tab-btn').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.tab === name);
    });
    threadDocOverlay.querySelectorAll('.tab-panel').forEach(panel => {
        panel.classList.toggle('active', panel.dataset.panel === name);
    });
}

async function loadThreadDocCollections() {
    try {
        const res = await fetch(DOC_API + '/collections');
        const collections = await res.json().catch(() => []);
        const thread = threads.find(t => t.id === threadDocTargetId);
        const selected = new Set(thread?.collections || []);
        threadDocCollectionList.innerHTML = '';
        if (!collections.length) {
            const empty = document.createElement('div');
            empty.className = 'collection-item';
            const text = document.createElement('span');
            text.className = 'name';
            text.textContent = 'Inga kollektioner hittades';
            empty.appendChild(text);
            threadDocCollectionList.appendChild(empty);
            return;
        }
        for (const name of collections) {
            const label = document.createElement('label');
            label.className = 'collection-item';
            const checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.value = name;
            checkbox.checked = selected.has(name);
            const text = document.createElement('span');
            text.className = 'name';
            text.textContent = name;
            label.appendChild(checkbox);
            label.appendChild(text);
            threadDocCollectionList.appendChild(label);
        }
    } catch (error) {
        threadDocStatus.textContent = 'Kunde inte hämta kollektioner: ' + error.message;
        threadDocStatus.className = 'status err';
    }
}

async function loadThreadDocDocuments() {
    try {
        const res = await fetch(DOC_API);
        const documents = await res.json().catch(() => []);
        scopeDocuments = Array.isArray(documents) ? documents : [];
        const thread = threads.find(t => t.id === threadDocTargetId);
        const selectedDocIds = new Set(thread?.documentIds || []);
        const collections = new Set();
        for (const doc of scopeDocuments) {
            if (doc.collection) collections.add(doc.collection);
        }
        threadDocFilterCollection.innerHTML = '<option value="">Alla kollektioner</option>';
        for (const name of Array.from(collections).sort((a, b) => a.localeCompare(b, 'sv'))) {
            const option = document.createElement('option');
            option.value = name;
            option.textContent = name;
            threadDocFilterCollection.appendChild(option);
        }
        renderThreadDocList();
    } catch (error) {
        threadDocStatus.textContent = 'Kunde inte hämta dokument: ' + error.message;
        threadDocStatus.className = 'status err';
    }
}

function renderThreadDocList() {
    const term = threadDocSearch.value.trim().toLowerCase();
    const collection = threadDocFilterCollection.value;
    const thread = threads.find(t => t.id === threadDocTargetId);
    const selectedDocIds = new Set(thread?.documentIds || []);
    const documents = scopeDocuments.filter(doc => {
        if (term && !(doc.name || '').toLowerCase().includes(term)) return false;
        if (collection && doc.collection !== collection) return false;
        return true;
    });
    threadDocList.innerHTML = '';
    if (!documents.length) {
        const empty = document.createElement('div');
        empty.className = 'doc-item';
        empty.innerHTML = '<span>Inga dokument att visa.</span>';
        threadDocList.appendChild(empty);
    } else {
        for (const doc of documents) {
            threadDocList.appendChild(threadDocumentRow(doc, selectedDocIds));
        }
    }
}

function threadDocumentRow(doc, selectedDocIds) {
    const row = document.createElement('div');
    row.className = 'doc-item';

    const check = document.createElement('input');
    check.type = 'checkbox';
    check.className = 'doc-check';
    check.value = doc.id;
    check.checked = selectedDocIds.has(doc.id);
    if (check.checked) row.classList.add('selected');
    check.addEventListener('change', () => {
        if (check.checked) selectedDocIds.add(doc.id); else selectedDocIds.delete(doc.id);
        row.classList.toggle('selected', check.checked);
    });

    const main = document.createElement('div');
    main.className = 'doc-main';
    const nameEl = document.createElement('div');
    nameEl.className = 'doc-name';
    nameEl.textContent = doc.name;
    nameEl.title = doc.name;
    const metaEl = document.createElement('div');
    metaEl.className = 'meta';
    let meta = doc.chunks + ' textstycken';
    if (doc.collection) meta += ' · ' + doc.collection;
    if (doc.createdAt) meta += ' · ' + formatDate(doc.createdAt);
    metaEl.textContent = meta;
    main.appendChild(nameEl);
    main.appendChild(metaEl);
    if (doc.tags && doc.tags.length) {
        const tagList = document.createElement('div');
        tagList.className = 'tag-list';
        for (const tag of doc.tags) {
            const chip = document.createElement('span');
            chip.className = 'tag';
            chip.textContent = tag;
            tagList.appendChild(chip);
        }
        main.appendChild(tagList);
    }

    row.appendChild(check);
    row.appendChild(main);
    return row;
}

function saveThreadDocSelection() {
    const thread = threads.find(t => t.id === threadDocTargetId);
    if (!thread) return;
    const collections = Array.from(
        threadDocCollectionList.querySelectorAll('input[type=checkbox]:checked')).map(cb => cb.value);
    const documentIds = Array.from(
        threadDocList.querySelectorAll('input[type=checkbox]:checked')).map(cb => cb.value);
    thread.collections = collections;
    thread.documentIds = documentIds;
    saveThreads();
    renderThreadList();
    renderChatHeader();
    threadDocStatus.textContent = 'Sparat';
    threadDocStatus.className = 'status ok';
    setTimeout(() => {
        threadDocStatus.textContent = '';
        threadDocStatus.className = 'status';
    }, 2000);
}

threadDocClose.addEventListener('click', closeThreadDocSelector);
threadDocOverlay.addEventListener('click', (event) => {
    if (event.target === threadDocOverlay) closeThreadDocSelector();
});
threadDocOverlay.querySelectorAll('.tab-btn').forEach(btn => {
    btn.addEventListener('click', () => switchThreadDocTab(btn.dataset.tab));
});
threadDocSearch.addEventListener('input', renderThreadDocList);
threadDocFilterCollection.addEventListener('change', renderThreadDocList);
threadDocSave.addEventListener('click', saveThreadDocSelection);
