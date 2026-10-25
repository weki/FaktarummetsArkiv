/* Dokumentlistan: hämta, filtrera, sortera, markera, ta bort. */
const documentsStatus = document.getElementById('documentsStatus');
const docList = document.getElementById('docList');
const docsSelectAll = document.getElementById('docsSelectAll');
const docsBulkDownload = document.getElementById('docsBulkDownload');
const docsBulkDelete = document.getElementById('docsBulkDelete');
const docsBulkCount = document.getElementById('docsBulkCount');
const docsSearch = document.getElementById('docsSearch');
const docsFilterCollection = document.getElementById('docsFilterCollection');
const docsFilterTag = document.getElementById('docsFilterTag');
const docsSort = document.getElementById('docsSort');

const selectedDocIds = new Set(JSON.parse(localStorage.getItem('selectedDocIds') || '[]'));
function persistSelectedDocIds() { localStorage.setItem('selectedDocIds', JSON.stringify(Array.from(selectedDocIds))); }

function setDocumentsStatus(text, cls) {
    documentsStatus.textContent = text;
    documentsStatus.className = 'status' + (cls ? ' ' + cls : '');
}

let allDocuments = [];

async function loadDocuments() {
    setDocumentsStatus('Laddar...', '');
    try {
        const res = await fetch(DOC_API);
        const data = await res.json().catch(() => []);
        if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
        allDocuments = Array.isArray(data) ? data : [];
        const valid = new Set(allDocuments.map(d=>d.id));
        for (const id of Array.from(selectedDocIds)) if (!valid.has(id)) selectedDocIds.delete(id);
        persistSelectedDocIds();
        refreshDocFilters();
        const sc = localStorage.getItem('docs_filter_collection');
        if (sc && Array.from(docsFilterCollection.options).some(o=>o.value===sc)) docsFilterCollection.value=sc;
        const st = localStorage.getItem('docs_filter_tag');
        if (st && Array.from(docsFilterTag.options).some(o=>o.value===st)) docsFilterTag.value=st;
        const ss = localStorage.getItem('docs_sort');
        if (ss && Array.from(docsSort.options).some(o=>o.value===ss)) docsSort.value=ss;
        renderDocuments();
    } catch (error) {
        setDocumentsStatus('Kunde inte hämta: ' + error.message, 'err');
    }
}

function refreshDocFilters() {
    const collections = new Set();
    const tags = new Set();
    for (const doc of allDocuments) {
        if (doc.collection) collections.add(doc.collection);
        for (const tag of (doc.tags || [])) tags.add(tag);
    }
    fillFilterSelect(docsFilterCollection, 'Alla kollektioner', collections);
    fillFilterSelect(docsFilterTag, 'Alla taggar', tags);
}

function fillFilterSelect(select, allLabel, values) {
    const current = select.value;
    select.innerHTML = '';
    const all = document.createElement('option');
    all.value = '';
    all.textContent = allLabel;
    select.appendChild(all);
    for (const value of Array.from(values).sort((a, b) => a.localeCompare(b, 'sv'))) {
        const option = document.createElement('option');
        option.value = value;
        option.textContent = value;
        select.appendChild(option);
    }
    select.value = current && values.has(current) ? current : '';
}

function visibleDocuments() {
    const term = docsSearch.value.trim().toLowerCase();
    const collection = docsFilterCollection.value;
    const tag = docsFilterTag.value;
    const documents = allDocuments.filter(doc => {
        if (term && !(doc.name || '').toLowerCase().includes(term)) return false;
        if (collection && doc.collection !== collection) return false;
        if (tag && !(doc.tags || []).includes(tag)) return false;
        return true;
    });
    const byDate = (doc) => (doc.createdAt ? Date.parse(doc.createdAt) : 0);
    const byName = (doc) => (doc.name || '').toLowerCase();
    switch (docsSort.value) {
        case 'name-asc':
            documents.sort((a, b) => byName(a).localeCompare(byName(b), 'sv'));
            break;
        case 'name-desc':
            documents.sort((a, b) => byName(b).localeCompare(byName(a), 'sv'));
            break;
        case 'chunks-desc':
            documents.sort((a, b) => (b.chunks || 0) - (a.chunks || 0));
            break;
        case 'date-asc':
            documents.sort((a, b) => byDate(a) - byDate(b));
            break;
        default:
            documents.sort((a, b) => byDate(b) - byDate(a));
    }
    return documents;
}

function renderDocuments() {
    const documents = visibleDocuments();
    docList.innerHTML = '';
    if (!documents.length) {
        const empty = document.createElement('tr');
        const cell = document.createElement('td');
        cell.colSpan = 7;
        cell.className = 'empty-cell';
        cell.textContent = allDocuments.length ? 'Inga dokument matchar filtret.' : 'Arkivet är tomt. Ladda upp PDF-filer under fliken "Ladda upp".';
        empty.appendChild(cell);
        docList.appendChild(empty);
    } else {
        for (const doc of documents) {
            docList.appendChild(documentRow(doc));
        }
    }
    const total = allDocuments.length;
    updateBulkBar();
    setDocumentsStatus(documents.length === total
        ? total + ' dokument'
        : documents.length + ' av ' + total + ' dokument', '');
}

function documentRow(doc) {
    const row = document.createElement('tr');
    const cell = (cls) => { const td = document.createElement('td'); if (cls) td.className = cls; row.appendChild(td); return td; };

    const check = document.createElement('input');
    check.type = 'checkbox';
    check.className = 'doc-check';
    check.setAttribute('aria-label', 'Markera ' + doc.name);
    check.checked = selectedDocIds.has(doc.id);
    if (check.checked) row.classList.add('selected');
    check.addEventListener('change', () => {
        if (check.checked) selectedDocIds.add(doc.id); else selectedDocIds.delete(doc.id);
        persistSelectedDocIds();
        row.classList.toggle('selected', check.checked);
        updateBulkBar();
    });
    cell('col-check').appendChild(check);

    const nameBtn = document.createElement('button');
    nameBtn.type = 'button';
    nameBtn.className = 'doc-link';
    nameBtn.textContent = doc.name;
    nameBtn.title = doc.name;
    nameBtn.addEventListener('click', () => viewDocument(doc.id));
    cell('col-name').appendChild(nameBtn);

    cell().textContent = doc.collection || '–';

    const tagCell = cell();
    const tagList = document.createElement('div');
    tagList.className = 'tag-list tag-list-inline';
    for (const tag of (doc.tags || [])) {
        const chip = document.createElement('span');
        chip.className = 'tag';
        chip.textContent = tag;
        tagList.appendChild(chip);
    }
    tagCell.appendChild(tagList);

    cell('num').textContent = doc.chunks != null ? String(doc.chunks) : '';
    cell('col-date').textContent = doc.createdAt ? formatDate(doc.createdAt) : '';

    const actions = document.createElement('div');
    actions.className = 'doc-actions';
    const view = document.createElement('button');
    view.type = 'button';
    view.textContent = 'Visa';
    view.addEventListener('click', () => viewDocument(doc.id));
    const download = document.createElement('a');
    download.href = DOC_API + '/' + encodeURIComponent(doc.id) + '/download';
    download.textContent = 'Ladda ner';
    const del = document.createElement('button');
    del.type = 'button';
    del.className = 'danger';
    del.textContent = 'Ta bort';
    del.setAttribute('aria-label', 'Ta bort ' + doc.name);
    del.addEventListener('click', () => deleteDocument(doc.id));
    actions.append(view, download, del);
    cell('col-actions').appendChild(actions);
    return row;
}

async function viewDocument(id) {
    await openDocumentPopup({documentId: id});
}

async function deleteDocument(id) {
    if (!confirm('Ta bort dokumentet och alla dess vektorer från arkivet?')) return;
    try {
        const res = await fetch(DOC_API + '/' + encodeURIComponent(id), {method: 'DELETE'});
        const data = await res.json().catch(() => ({}));
        if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
        await loadDocuments();
    } catch (error) {
        setDocumentsStatus('Fel: ' + error.message, 'err');
    }
}

function updateBulkBar() {
    const n = selectedDocIds.size;
    docsBulkCount.textContent = n ? n + (n === 1 ? ' markerat' : ' markerade') : '';
    document.getElementById('docsBulk').hidden = n === 0;
    docsBulkDownload.disabled = !n;
    docsBulkDelete.disabled = !n;
    const visible = visibleDocuments();
    docsSelectAll.checked = visible.length > 0 && visible.every(d => selectedDocIds.has(d.id));
    docsSelectAll.indeterminate = visible.some(d => selectedDocIds.has(d.id)) && !docsSelectAll.checked;
}

docsSelectAll.addEventListener('change', () => {
    for (const doc of visibleDocuments()) {
        if (docsSelectAll.checked) selectedDocIds.add(doc.id); else selectedDocIds.delete(doc.id);
        persistSelectedDocIds();
    }
    renderDocuments();
});

document.getElementById('docsBulkClear').addEventListener('click', () => {
    selectedDocIds.clear();
    persistSelectedDocIds();
    renderDocuments();
});

docsBulkDownload.addEventListener('click', () => {
    for (const id of Array.from(selectedDocIds)) {
        const a = document.createElement('a');
        a.href = DOC_API + '/' + encodeURIComponent(id) + '/download';
        a.download = '';
        document.body.appendChild(a);
        a.click();
        a.remove();
    }
});

docsBulkDelete.addEventListener('click', async () => {
    if (!selectedDocIds.size) return;
    if (!confirm('Ta bort ' + selectedDocIds.size + ' markerade dokument och alla deras vektorer?')) return;
    const ids = Array.from(selectedDocIds);
    let failed = 0;
    for (const id of ids) {
        try {
            const res = await fetch(DOC_API + '/' + encodeURIComponent(id), {method: 'DELETE'});
            if (!res.ok) throw new Error('HTTP ' + res.status);
            selectedDocIds.delete(id);
            persistSelectedDocIds();
        } catch (e) {
            failed++;
        }
    }
    await loadDocuments();
    setDocumentsStatus((ids.length - failed) + ' dokument borttagna' + (failed ? ' · ' + failed + ' misslyckades' : ''), failed ? 'err' : 'ok');
});
