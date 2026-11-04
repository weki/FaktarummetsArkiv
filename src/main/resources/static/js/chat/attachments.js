/* Bilagor: ladda upp video i chatten, visa progress, blockera chatten under analys. */
const VIDEO_API = '/api/video-jobs';
const videoAttachBtn = document.getElementById('videoAttachBtn');
const videoAttachInput = document.getElementById('videoAttachInput');

let videoInProgress = false;
let currentVideoJobId = null;

function setChatBlocked(blocked) {
    videoInProgress = blocked;
    const form = document.getElementById('chatForm');
    const input = document.getElementById('question');
    const send = document.getElementById('sendBtn');
    const attach = document.getElementById('videoAttachBtn');
    if (form) form.classList.toggle('disabled-during-video', blocked);
    if (input) input.disabled = blocked;
    if (send) send.disabled = blocked;
    if (attach) attach.disabled = blocked;
    if (blocked) {
        if (input) input.placeholder = 'Videoanalys pågår…';
    } else {
        if (input) input.placeholder = 'Ställ en fråga om dokumenten…';
    }
}

function showVideoProgress(filename) {
    let panel = document.getElementById('videoProgressPanel');
    if (!panel) {
        panel = document.createElement('div');
        panel.id = 'videoProgressPanel';
        panel.className = 'video-progress-panel';
        const messagesInner = document.getElementById('messagesInner');
        if (messagesInner) messagesInner.appendChild(panel);
        else document.body.appendChild(panel);
    }
    panel.innerHTML = '';
    const title = document.createElement('div');
    title.className = 'video-progress-title';
    title.textContent = 'Analyserar video: ' + filename;
    panel.appendChild(title);

    const barWrap = document.createElement('div');
    barWrap.className = 'video-progress-bar-wrap';
    const bar = document.createElement('div');
    bar.className = 'video-progress-bar';
    barWrap.appendChild(bar);
    panel.appendChild(barWrap);

    const text = document.createElement('div');
    text.className = 'video-progress-text';
    text.id = 'videoProgressText';
    text.textContent = 'Startar…';
    panel.appendChild(text);

    const cancelBtn = document.createElement('button');
    cancelBtn.type = 'button';
    cancelBtn.className = 'danger';
    cancelBtn.textContent = 'Avbryt';
    cancelBtn.addEventListener('click', async () => {
        if (!currentVideoJobId) return;
        cancelBtn.disabled = true;
        cancelBtn.textContent = 'Avbryter…';
        try {
            await fetch(VIDEO_API + '/' + currentVideoJobId, { method: 'DELETE' });
        } catch (ignored) {}
    });
    panel.appendChild(cancelBtn);
    scrollToBottom(true);
}

function updateVideoProgress(text) {
    const el = document.getElementById('videoProgressText');
    if (el) el.textContent = text;
}

function hideVideoProgress() {
    const panel = document.getElementById('videoProgressPanel');
    if (panel) panel.remove();
}

if (videoAttachBtn && videoAttachInput) {
    videoAttachBtn.addEventListener('click', () => {
        if (videoInProgress) return;
        videoAttachInput.click();
    });
    videoAttachInput.addEventListener('change', () => {
        const f = videoAttachInput.files && videoAttachInput.files[0];
        if (f) uploadVideoAttachment(f);
        videoAttachInput.value = '';
    });
}

async function uploadVideoAttachment(file) {
    if (!activeThread()) createThread();
    const thread = activeThread();
    if (!thread.attachments) thread.attachments = [];

    setChatBlocked(true);
    showVideoProgress(file.name);
    updateVideoProgress('Laddar upp…');

    let jobId = null;
    try {
        const fd = new FormData();
        fd.append('file', file);
        const res = await fetch(VIDEO_API, { method: 'POST', body: fd });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
        jobId = data.id;
        currentVideoJobId = jobId;
        thread.attachments.push({type: 'video', runId: data.runId || null, jobId: data.id, filename: file.name, status: data.status || 'QUEUED'});
        saveThreads();
        renderChatHeader();

        // SSE for progress
        await new Promise((resolve) => {
            const es = new EventSource(VIDEO_API + '/' + jobId + '/events');
            es.addEventListener('progress', ev => { updateVideoProgress(ev.data); });
            es.addEventListener('status', ev => {
                try {
                    const d = JSON.parse(ev.data);
                    updateVideoProgress(d.message || d.status);
                    const att = thread.attachments.find(a => a.jobId === jobId);
                    if (att && d.status) att.status = d.status;
                } catch (ignored) {}
            });
            es.addEventListener('result', ev => {
                try {
                    const d = JSON.parse(ev.data);
                    const att = thread.attachments.find(a => a.jobId === jobId);
                    let runId = (d && d.runId) || (att && att.runId) || null;
                    if (!runId) {
                        // fallback: fetch job to get runId
                        fetch(VIDEO_API + '/' + jobId).then(r => r.json()).then(j => {
                            if (j && j.runId) {
                                if (att) att.runId = j.runId;
                                saveThreads();
                                appendVideoAnalysisMessage(thread, att, Object.assign({}, d, {runId: j.runId}));
                            }
                        }).catch(err => {
        const msg = {role: 'assistant', text: 'Videoanalys klar (run ' + runId + '). Kunde inte hämta utkast: ' + err.message, sources: [], searched: false};
        thread.messages.push(msg);
        touchThread(thread);
        saveThreads();
        const messagesInner = document.getElementById('messagesInner');
        if (messagesInner && typeof renderAssistant === 'function') {
            messagesInner.appendChild(renderAssistant(msg, thread.messages.length - 1, false));
        }
        scrollToBottom(true);
    });
                        return;
                    }
                    if (att) { att.runId = runId; att.status = 'DONE'; }
                    saveThreads();
                    renderChatHeader();
                    appendVideoAnalysisMessage(thread, att, Object.assign({}, d, {runId: runId}));
                } catch (ignored) { console.error('result handler', ignored); }
            });
            es.addEventListener('error', ev => {
                let msg = 'Fel';
                try { msg = JSON.parse(ev.data).error || msg; } catch (ignored) {}
                const att = thread.attachments.find(a => a.jobId === jobId);
                if (att) att.status = 'FAILED';
                saveThreads();
                renderChatHeader();
                updateVideoProgress('Misslyckades: ' + msg);
            });
            es.addEventListener('done', () => { es.close(); resolve(); });
            // fallback close after long time
            setTimeout(() => { try { es.close(); } catch (ignored) {} resolve(); }, 1800000);
        });
    } catch (err) {
        updateVideoProgress('Uppladdning misslyckades: ' + err.message);
    } finally {
        currentVideoJobId = null;
        hideVideoProgress();
        setChatBlocked(false);
    }
}

function appendVideoAnalysisMessage(thread, att, data) {
    let runId = (att && att.runId) || (data && data.runId) || null;
    if (!runId && att && att.jobId) {
        // last resort: fetch job
        return;
    }
    if (!runId) return;
    fetch(VIDEO_API.replace('video-jobs','video-runs') + '/' + runId).then(r => {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
    }).then(draft => {
        const lines = [];
        lines.push('**' + (draft.title || 'Procedurutkast') + '**');
        if (draft.purpose) lines.push('', draft.purpose);
        if (draft.prerequisites && draft.prerequisites.length) {
            lines.push('', 'Förkrav:', ...draft.prerequisites.map(p => '- ' + p));
        }
        if (draft.warnings && draft.warnings.length) {
            lines.push('', 'Varningar:', ...draft.warnings.map(w => '- ' + w));
        }
        lines.push('', 'Steg:');
        (draft.steps || []).forEach(s => {
            const t = Math.floor(s.startSecond / 60) + ':' + String(s.startSecond % 60).padStart(2, '0');
            lines.push(s.number + '. **' + (s.title || '') + '** (' + t + ')');
            if (s.instruction) lines.push('   ' + s.instruction);
            if (s.inputText) lines.push('   Inmatning: `' + s.inputText + '`');
            if (s.expectedResult) lines.push('   Förväntat: ' + s.expectedResult);
            if (s.uncertain) lines.push('   ⚠ Osäkert steg');
        });
        const text = lines.join('\n');
        const msg = {role: 'assistant', text: text, sources: [], searched: false, searchMode: 'ATTACHMENT'};
        thread.messages.push(msg);
        touchThread(thread);
        saveThreads();
        if (typeof renderChatHeader === 'function') renderChatHeader();
        const messagesInner = document.getElementById('messagesInner');
        if (messagesInner) {
            // render via existing renderAssistant if available
            if (typeof renderAssistant === 'function') {
                const idx = thread.messages.length - 1;
                messagesInner.appendChild(renderAssistant(msg, idx, false));
            } else {
                const div = document.createElement('div');
                div.className = 'turn';
                div.textContent = text;
                messagesInner.appendChild(div);
            }
            scrollToBottom(true);
        }
    }).catch(err => {
        const msg = {role: 'assistant', text: 'Videoanalys klar (run ' + runId + '). Kunde inte hämta utkast: ' + err.message, sources: [], searched: false};
        thread.messages.push(msg);
        touchThread(thread);
        saveThreads();
        const messagesInner = document.getElementById('messagesInner');
        if (messagesInner && typeof renderAssistant === 'function') {
            messagesInner.appendChild(renderAssistant(msg, thread.messages.length - 1, false));
        }
        scrollToBottom(true);
    });
}

function scrollToBottom(force) {
    const el = document.getElementById('messages');
    if (el) el.scrollTop = el.scrollHeight;
}
