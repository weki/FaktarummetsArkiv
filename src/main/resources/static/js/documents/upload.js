/* Uppladdning av PDF-filer. */
const uploadForm = document.getElementById('uploadForm');
const uploadBtn = document.getElementById('uploadBtn');
const uploadTags = document.getElementById('uploadTags');
const uploadFolderBtn = document.getElementById('uploadFolderBtn');
const uploadFolderInput = document.getElementById('uploadFolderInput');
const uploadProgress = document.getElementById('uploadProgress');
const uploadProgressLabel = document.getElementById('uploadProgressLabel');
const uploadProgressPct = document.getElementById('uploadProgressPct');
const uploadProgressFill = document.getElementById('uploadProgressFill');
const uploadProgressDetail = document.getElementById('uploadProgressDetail');
const tagSuggestions = document.getElementById('tagSuggestions');

async function loadUploadOptions() {
    await loadTags();
    renderTagSuggestions();
}

function renderTagSuggestions() {
    tagSuggestions.innerHTML = '';
    if (!knownTags.length) return;
    const label = document.createElement('span');
    label.className = 'meta';
    label.textContent = 'Befintliga taggar:';
    tagSuggestions.appendChild(label);
    for (const tag of knownTags) {
        const button = document.createElement('button');
        button.type = 'button';
        button.textContent = tag;
        button.addEventListener('click', () => addTag(uploadTags, tag));
        tagSuggestions.appendChild(button);
    }
}

function addTag(input, tag) {
    const tags = input.value.split(',').map(t => t.trim()).filter(Boolean);
    if (!tags.includes(tag)) {
        tags.push(tag);
    }
    input.value = tags.join(', ');
}

function setUploadProgress(state, label, pct, detail) {
    uploadProgress.className = 'upload-progress' + (state ? ' ' + state : '');
    if (label !== undefined) uploadProgressLabel.textContent = label;
    if (pct !== undefined) {
        const clamped = pct === null ? 0 : Math.max(0, Math.min(100, pct));
        uploadProgressFill.style.width = clamped + '%';
        uploadProgressPct.textContent = pct === null ? '' : Math.round(pct) + '%';
    }
    if (detail !== undefined) uploadProgressDetail.textContent = detail;
}

function hideUploadProgress() {
    uploadProgress.className = 'upload-progress hidden';
    setUploadProgress('', '', 0, '');
}

function uploadFile(file, collection, tags, onProgress) {
    return new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        xhr.open('POST', DOC_API);
        xhr.upload.onprogress = (event) => {
            if (event.lengthComputable && onProgress) onProgress(event.loaded, event.total);
        };
        xhr.onload = () => {
            let data = {};
            try {
                data = JSON.parse(xhr.responseText || '{}');
            } catch (ignored) {
                data = {};
            }
            if (xhr.status >= 200 && xhr.status < 300) {
                resolve(Array.isArray(data) ? data : []);
            } else {
                reject(new Error(data.error || ('HTTP ' + xhr.status)));
            }
        };
        xhr.onerror = () => reject(new Error('Uppladdningen avbröts'));
        xhr.onabort = () => reject(new Error('Uppladdningen avbröts'));
        const formData = new FormData();
        formData.append('files', file);
        if (collection) formData.append('collection', collection);
        for (const tag of tags) formData.append('tags', tag);
        xhr.send(formData);
    });
}

let uploadInProgress = false;
window.addEventListener('beforeunload', (event) => {
    if (uploadInProgress) {
        event.preventDefault();
        event.returnValue = 'Uppladdning pågår – vill du lämna sidan?';
    }
});

uploadForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    const files = Array.from(uploadForm.elements.namedItem('files').files);
    if (!files.length) {
        setDocumentsStatus('Välj minst en fil', 'err');
        return;
    }
    const tags = uploadTags.value.split(',').map(t => t.trim()).filter(Boolean);
    const totalBytes = files.reduce((sum, file) => sum + file.size, 0);
    uploadBtn.disabled = true;
    uploadInProgress = true;
    setDocumentsStatus('Laddar upp...', '');
    setUploadProgress('', 'Förbereder uppladdning', 0,
        files.length + ' fil(er) · ' + formatBytes(totalBytes));

    const uploaded = [];
    let sentBytes = 0;
    try {
        for (let i = 0; i < files.length; i++) {
            const file = files[i];
            const base = sentBytes;
            setUploadProgress('', 'Fil ' + (i + 1) + ' av ' + files.length + ' · ' + file.name,
                totalBytes ? (base / totalBytes) * 100 : 100,
                formatBytes(base) + ' av ' + formatBytes(totalBytes));
            const added = await uploadFile(file, '', tags, (loaded) => {
                const pct = totalBytes ? ((base + loaded) / totalBytes) * 100 : 100;
                setUploadProgress('', 'Fil ' + (i + 1) + ' av ' + files.length + ' · ' + file.name,
                    pct, formatBytes(base + loaded) + ' av ' + formatBytes(totalBytes));
            });
            uploaded.push(...added);
            sentBytes += file.size;
        }

        setUploadProgress('processing', 'Alla filer skickade', 100,
            'Bearbetar, chunkar och indexerar i arkivet...');
        uploadForm.reset();
        uploadTags.value = '';
        qdrantCollections = null;
        await Promise.all([loadUploadOptions(), loadDocuments()]);
        const addedCount = uploaded.filter(d => !d.skipped).length;
        const skippedCount = uploaded.filter(d => d.skipped).length;
        let detail = addedCount + ' dokument tillagda i arkivet';
        const notice = uploaded.map(d => d.notice).filter(Boolean).join(' · ');
        if (notice) detail += ' · ' + notice;
        setUploadProgress('ok', 'Klart', 100, detail);
        let msg = addedCount + ' dokument uppladdade';
        if (skippedCount) msg += ' · ' + skippedCount + ' redan i arkivet';
        if (notice) msg += ' · ' + notice;
        setDocumentsStatus(msg, skippedCount && !addedCount ? '' : 'ok');
        window.setTimeout(hideUploadProgress, 5000);
    } catch (error) {
        setUploadProgress('err', 'Fel vid uppladdning', null, error.message);
        setDocumentsStatus('Fel: ' + error.message, 'err');
    } finally {
        uploadBtn.disabled = false;
        uploadInProgress = false;
    }
});

uploadFolderBtn.addEventListener('click', () => uploadFolderInput.click());
uploadFolderInput.addEventListener('change', () => {
    const picked = Array.from(uploadFolderInput.files || []).filter(f => /\.pdf$/i.test(f.name));
    if (!picked.length) {
        setDocumentsStatus('Inga PDF-filer i vald mapp', 'err');
        uploadFolderInput.value = '';
        return;
    }
    const dt = new DataTransfer();
    for (const f of picked) dt.items.add(f);
    const input = uploadForm.elements.namedItem('files');
    input.files = dt.files;
    setDocumentsStatus(picked.length + ' PDF-filer valda från mapp – klicka "Ladda upp"', '');
    uploadFolderInput.value = '';
    showPickedFiles();
});

const uploadList = document.getElementById('uploadList');
const dropzone = document.getElementById('dropzone');
function showPickedFiles() {
    const files = Array.from(uploadForm.elements.namedItem('files').files);
    uploadList.textContent = '';
    for (const f of files.slice(0, 50)) {
        const li = document.createElement('li');
        const name = document.createElement('span');
        name.textContent = f.name;
        const size = document.createElement('span');
        size.className = 'meta';
        size.textContent = formatBytes(f.size);
        li.append(name, size);
        uploadList.appendChild(li);
    }
    if (files.length > 50) {
        const li = document.createElement('li');
        li.className = 'meta';
        li.textContent = '… och ' + (files.length - 50) + ' till';
        uploadList.appendChild(li);
    }
}
uploadForm.elements.namedItem('files').addEventListener('change', showPickedFiles);
uploadForm.addEventListener('reset', () => setTimeout(showPickedFiles, 0));
['dragenter', 'dragover'].forEach(n => dropzone.addEventListener(n, (e) => { e.preventDefault(); dropzone.classList.add('over'); }));
['dragleave', 'drop'].forEach(n => dropzone.addEventListener(n, (e) => { e.preventDefault(); dropzone.classList.remove('over'); }));
dropzone.addEventListener('drop', (e) => {
    const pdfs = Array.from(e.dataTransfer ? e.dataTransfer.files : []).filter(f => /\.pdf$/i.test(f.name));
    if (!pdfs.length) { setDocumentsStatus('Inga PDF-filer i det du släppte', 'err'); return; }
    const dt = new DataTransfer();
    pdfs.forEach(f => dt.items.add(f));
    uploadForm.elements.namedItem('files').files = dt.files;
    showPickedFiles();
    setDocumentsStatus(pdfs.length + ' PDF-filer valda – klicka "Ladda upp"', '');
});
