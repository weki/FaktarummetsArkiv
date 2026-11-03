const VIDEO_API = '/api/video-jobs';
const videoStatus = document.getElementById('videoStatus');
const videoFileInput = document.getElementById('videoFileInput');
const videoSubmitBtn = document.getElementById('videoSubmitBtn');
const videoUploadForm = document.getElementById('videoUploadForm');
const videoDropzone = document.getElementById('videoDropzone');
const videoHint = document.getElementById('videoHint');
const videoLanguage = document.getElementById('videoLanguage');
const videoJobList = document.getElementById('videoJobList');
const draftView = document.getElementById('draftView');

let selectedVideoFile = null;
let currentJobId = null;

function setVideoStatus(text, cls) {
    videoStatus.textContent = text || '';
    videoStatus.className = 'status' + (cls ? ' ' + cls : '');
}

videoFileInput.addEventListener('change', () => {
    selectedVideoFile = videoFileInput.files && videoFileInput.files[0] ? videoFileInput.files[0] : null;
    videoSubmitBtn.disabled = !selectedVideoFile;
    if (selectedVideoFile) setVideoStatus('Vald: ' + selectedVideoFile.name, '');
});

videoDropzone.addEventListener('dragover', e => { e.preventDefault(); videoDropzone.classList.add('dragover'); });
videoDropzone.addEventListener('dragleave', () => videoDropzone.classList.remove('dragover'));
videoDropzone.addEventListener('drop', e => {
    e.preventDefault();
    videoDropzone.classList.remove('dragover');
    const f = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
    if (f) { selectedVideoFile = f; videoFileInput.value = ''; videoSubmitBtn.disabled = false; setVideoStatus('Vald: ' + f.name, ''); }
});

videoUploadForm.addEventListener('submit', async e => {
    e.preventDefault();
    if (!selectedVideoFile) return;
    videoSubmitBtn.disabled = true;
    setVideoStatus('Laddar upp...', '');
    const fd = new FormData();
    fd.append('file', selectedVideoFile);
    if (videoHint.value.trim()) fd.append('hint', videoHint.value.trim());
    fd.append('language', videoLanguage.value);
    try {
        const res = await fetch(VIDEO_API, { method: 'POST', body: fd });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
        currentJobId = data.id;
        setVideoStatus('Jobb startat: ' + data.id, 'ok');
        selectedVideoFile = null;
        videoFileInput.value = '';
        await refreshJobs();
        openEventStream(data.id);
    } catch (err) {
        setVideoStatus('Uppladdning misslyckades: ' + err.message, 'err');
    } finally {
        videoSubmitBtn.disabled = !selectedVideoFile;
    }
});

function openEventStream(jobId) {
    const es = new EventSource(VIDEO_API + '/' + encodeURIComponent(jobId) + '/events');
    es.addEventListener('progress', ev => {
        const job = findJob(jobId);
        if (job) { job.progressMessage = ev.data; renderJobs(); }
    });
    es.addEventListener('status', ev => {
        try {
            const d = JSON.parse(ev.data);
            const job = findJob(jobId);
            if (job) { job.status = d.status; job.progressMessage = d.message || job.progressMessage; renderJobs(); }
        } catch (ignored) { }
    });
    es.addEventListener('result', ev => {
        try {
            const d = JSON.parse(ev.data);
            const job = findJob(jobId);
            if (job) { job.status = 'DONE'; renderJobs(); }
        } catch (ignored) { }
        es.close();
        loadDraft(jobId);
    });
    es.addEventListener('error', ev => {
        let msg = 'Fel';
        try { msg = JSON.parse(ev.data).error || msg; } catch (ignored) { }
        const job = findJob(jobId);
        if (job) { job.status = 'FAILED'; job.error = msg; renderJobs(); }
        es.close();
    });
    es.addEventListener('done', ev => { es.close(); refreshJobs(); });
}

let jobs = [];
function findJob(id) { return jobs.find(j => j.id === id); }

async function refreshJobs() {
    try {
        const res = await fetch(VIDEO_API);
        if (!res.ok) throw new Error('HTTP ' + res.status);
        jobs = await res.json();
        renderJobs();
    } catch (err) {
        videoJobList.innerHTML = '<div class="thread-empty">Kunde inte hämta jobb: ' + escapeHtml(err.message) + '</div>';
    }
}

function renderJobs() {
    if (!jobs.length) {
        videoJobList.innerHTML = '<div class="thread-empty">Inga jobb ännu.</div>';
        return;
    }
    const rows = jobs.map(j => {
        const statusCls = j.status === 'DONE' ? 'ok' : (j.status === 'FAILED' ? 'err' : '');
        return '<div class="thread-row" style="padding:10px 12px;border:1px solid var(--border);border-radius:10px;margin-bottom:6px;">'
            + '<div style="display:flex;justify-content:space-between;gap:8px;">'
            + '<strong>' + escapeHtml(j.filename) + '</strong>'
            + '<span class="status ' + statusCls + '">' + escapeHtml(j.status) + '</span></div>'
            + '<div class="meta">' + escapeHtml(j.progressMessage || '') + '</div>'
            + (j.status === 'DONE' ? '<button type="button" class="btn-ghost" data-view="' + j.id + '">Visa utkast</button> ' : '')
            + (j.status === 'ANALYZING' || j.status === 'QUEUED' ? '<button type="button" class="danger" data-cancel="' + j.id + '">Avbryt</button> ' : '')
            + (j.status === 'DONE' || j.status === 'FAILED' ? '<button type="button" class="btn-ghost" data-delete="' + j.id + '">Radera</button>' : '')
            + '</div>';
    }).join('');
    videoJobList.innerHTML = rows;
    videoJobList.querySelectorAll('[data-view]').forEach(b => b.addEventListener('click', () => loadDraft(b.dataset.view)));
    videoJobList.querySelectorAll('[data-cancel]').forEach(b => b.addEventListener('click', async () => {
        await fetch(VIDEO_API + '/' + b.dataset.cancel, { method: 'DELETE' });
        refreshJobs();
    }));
    videoJobList.querySelectorAll('[data-delete]').forEach(b => b.addEventListener('click', async () => {
        await fetch(VIDEO_API + '/' + b.dataset.delete, { method: 'DELETE' });
        refreshJobs();
    }));
}

async function loadDraft(jobId) {
    try {
        const res = await fetch(VIDEO_API + '/' + jobId + '/draft');
        if (!res.ok) { const d = await res.json().catch(() => ({})); throw new Error(d.error || 'HTTP ' + res.status); }
        const draft = await res.json();
        renderDraft(draft, jobId);
    } catch (err) {
        setVideoStatus('Kunde inte hämta utkast: ' + err.message, 'err');
    }
}

function renderDraft(draft, jobId) {
    draftView.hidden = false;
    document.getElementById('draftTitle').textContent = draft.title || 'Procedurutkast';
    document.getElementById('draftPurpose').textContent = draft.purpose || '';
    const warnBox = document.getElementById('draftWarnings');
    warnBox.innerHTML = '';
    (draft.warnings || []).forEach(w => {
        const div = document.createElement('div');
        div.className = 'status err';
        div.textContent = w;
        warnBox.appendChild(div);
    });
    const prereqList = document.getElementById('draftPrereqs');
    prereqList.innerHTML = '';
    (draft.prerequisites || []).forEach(p => {
        const li = document.createElement('li'); li.textContent = p; prereqList.appendChild(li);
    });
    const stepsList = document.getElementById('draftSteps');
    stepsList.innerHTML = '';
    (draft.steps || []).forEach(s => {
        const li = document.createElement('li');
        const time = formatTime(s.startSecond);
        const frameUrl = s.frameImage ? VIDEO_API + '/' + jobId + '/frames/' + encodeURIComponent(s.frameImage.split('/').pop()) : null;
        li.innerHTML = '<strong>' + escapeHtml(s.title || '') + '</strong> '
            + '<span class="meta">(' + time + ')</span>'
            + (s.uncertain ? ' <span class="status err">osäker</span>' : '')
            + '<div>' + escapeHtml(s.instruction || '') + '</div>'
            + (s.inputText ? '<pre class="code">' + escapeHtml(s.inputText) + '</pre>' : '')
            + (s.expectedResult ? '<div class="meta">Förväntat: ' + escapeHtml(s.expectedResult) + '</div>' : '')
            + (frameUrl ? '<img src="' + frameUrl + '" alt="bildruta" style="max-width:320px;border-radius:8px;margin-top:6px;">' : '');
        stepsList.appendChild(li);
    });
    document.getElementById('draftDownloadMd').onclick = () => {
        // no markdown endpoint yet; build from draft client-side
        const md = buildMarkdown(draft);
        const blob = new Blob([md], { type: 'text/markdown' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url; a.download = (draft.title || 'procedur') + '.md';
        a.click(); URL.revokeObjectURL(url);
    };
    document.getElementById('draftDelete').onclick = async () => {
        await fetch(VIDEO_API + '/' + jobId, { method: 'DELETE' });
        draftView.hidden = true;
        refreshJobs();
    };
}

function buildMarkdown(draft) {
    let md = '# ' + (draft.title || '') + '\n\n' + (draft.purpose || '') + '\n\n';
    if (draft.prerequisites && draft.prerequisites.length) {
        md += '## Förkrav\n' + draft.prerequisites.map(p => '- ' + p).join('\n') + '\n\n';
    }
    if (draft.warnings && draft.warnings.length) {
        md += '## Varningar\n' + draft.warnings.map(w => '- ' + w).join('\n') + '\n\n';
    }
    md += '## Steg\n';
    (draft.steps || []).forEach(s => {
        md += s.number + '. **' + s.title + '** (' + formatTime(s.startSecond) + ')\n';
        md += '   ' + s.instruction + '\n';
        if (s.inputText) md += '   Inmatning: `' + s.inputText + '`\n';
        if (s.expectedResult) md += '   Förväntat: ' + s.expectedResult + '\n';
        if (s.uncertain) md += '   ⚠ Osäkert steg\n';
    });
    return md;
}

function formatTime(sec) {
    sec = sec || 0;
    return Math.floor(sec / 60) + ':' + String(sec % 60).padStart(2, '0');
}

function escapeHtml(s) {
    return String(s == null ? '' : s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

refreshJobs();
