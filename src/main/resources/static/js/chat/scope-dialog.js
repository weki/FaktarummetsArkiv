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
        const available = new Set(collections);
        const thread = threads.find(t => t.id === threadDocTargetId);
        const selected = new Set(thread?.collections || []);
        threadDocCollectionList.innerHTML = '';
        if (!collections.length && !selected.size) {
            const empty = document.createElement('div');
            empty.className = 'collection-item';
            const text = document.createElement('span');
            text.className = 'name';
            text.textContent = 'Inga källsystem hittades';
            empty.appendChild(text);
            threadDocCollectionList.appendChild(empty);
            return;
        }
        for (const name of collections) {
            threadDocCollectionList.appendChild(collectionItem(name, name, selected.has(name), false));
        }
        // Tidigare valda källsystem som inte längre finns i arkivet (t.ex. efter namnbyte).
        const stale = Array.from(selected).filter(name => !available.has(name));
        if (stale.length) {
            const note = document.createElement('div');
            note.className = 'collection-stale-note';
            note.textContent = 'Nedanför vala källsystem finns inte längre i arkivet '
                + '(de kan ha bytt namn). Valen ger inga sökträffar – avmarkera dem och välj de nuvarande i stället.';
            threadDocCollectionList.appendChild(note);
            for (const name of stale) {
                threadDocCollectionList.appendChild(collectionItem(name, name + ' (finns inte längre)', true, true));
            }
        }
    } catch (error) {
        threadDocStatus.textContent = 'Kunde inte hämta kollektioner: ' + error.message;
        threadDocStatus.className = 'status err';
    }
}

function collectionItem(value, labelText, checked, stale) {
    const label = document.createElement('label');
    label.className = 'collection-item' + (stale ? ' collection-item-stale' : '');
    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.value = value;
    checkbox.checked = checked;
    const text = document.createElement('span');
    text.className = 'name';
    text.textContent = labelText;
    label.appendChild(checkbox);
    label.appendChild(text);
    return label;
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
