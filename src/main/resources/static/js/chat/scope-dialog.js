/* Val av kollektioner/dokument för en tråd. */
const threadDocOverlay = document.getElementById('threadDocOverlay');
const threadDocClose = document.getElementById('threadDocClose');
const threadDocCollectionList = document.getElementById('threadDocCollectionList');
const threadDocSave = document.getElementById('threadDocSave');
const threadDocStatus = document.getElementById('threadDocStatus');

let threadDocTargetId = null;
let scopeDocuments = [];

function openThreadDocSelector(threadId) {
    threadDocTargetId = threadId;
    threadDocOverlay.classList.remove('hidden');
    loadThreadDocCollections();
}

function closeThreadDocSelector() {
    threadDocOverlay.classList.add('hidden');
    threadDocTargetId = null;
    threadDocCollectionList.innerHTML = '';
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



function saveThreadDocSelection() {
    const thread = threads.find(t => t.id === threadDocTargetId);
    if (!thread) return;
    const collections = Array.from(
        threadDocCollectionList.querySelectorAll('input[type=checkbox]:checked')).map(cb => cb.value);
    thread.collections = collections;
    thread.documentIds = [];
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
threadDocSave.addEventListener('click', saveThreadDocSelection);
