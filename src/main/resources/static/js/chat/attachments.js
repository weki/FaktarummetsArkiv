/* Bilagor: ladda upp video i chatten och fäst runId på tråden. */
const VIDEO_API = '/api/video-jobs';
const videoAttachBtn = document.getElementById('videoAttachBtn');
const videoAttachInput = document.getElementById('videoAttachInput');

if (videoAttachBtn && videoAttachInput) {
    videoAttachBtn.addEventListener('click', () => videoAttachInput.click());
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
    setStreaming(true);
    try {
        const fd = new FormData();
        fd.append('file', file);
        const xhr = new XMLHttpRequest();
        const url = VIDEO_API;
        await new Promise((resolve, reject) => {
            xhr.open('POST', url);
            xhr.onload = () => {
                if (xhr.status >= 200 && xhr.status < 300) resolve(JSON.parse(xhr.responseText || '{}'));
                else reject(new Error('HTTP ' + xhr.status));
            };
            xhr.onerror = () => reject(new Error('Nätverksfel'));
            xhr.send(fd);
        }).then(async (data) => {
            const runId = data.runId || null;
            thread.attachments.push({type: 'video', runId: runId, jobId: data.id, filename: file.name, status: data.status});
            saveThreads();
            renderChatHeader();
            // wait for completion via SSE
            if (data.id) {
                const es = new EventSource(VIDEO_API + '/' + data.id + '/events');
                es.addEventListener('result', ev => {
                    try {
                        const d = JSON.parse(ev.data);
                        const att = thread.attachments.find(a => a.jobId === data.id);
                        if (att) { att.runId = d.runId || att.runId; att.status = 'DONE'; }
                        saveThreads();
                        renderChatHeader();
                    } catch (ignored) {}
                });
                es.addEventListener('error', ev => {
                    const att = thread.attachments.find(a => a.jobId === data.id);
                    if (att) att.status = 'FAILED';
                    saveThreads();
                    renderChatHeader();
                    es.close();
                });
                es.addEventListener('done', () => es.close());
            }
        });
    } catch (err) {
        alert('Uppladdning misslyckades: ' + err.message);
    } finally {
        setStreaming(false);
    }
}

// Render attachment chip in header
const origRenderChatHeader = typeof renderChatHeader === 'function' ? renderChatHeader : null;
function renderAttachmentChip() {
    const thread = activeThread();
    const chips = (thread && thread.attachments && thread.attachments.length)
        ? thread.attachments.map(a => a.filename + ' (' + (a.status || '') + ')').join(', ')
        : '';
    // Reuse threadScopeLabel area? Append separately if element exists
    let el = document.getElementById('attachmentChip');
    if (!el) {
        el = document.createElement('span');
        el.id = 'attachmentChip';
        el.className = 'thread-scope-label';
        const header = document.querySelector('.chat-header, .page-head');
        if (header) header.appendChild(el);
    }
    el.textContent = chips ? ('Bilaga: ' + chips) : '';
    el.hidden = !chips;
}
if (typeof renderChatHeader === 'function') {
    const prev = renderChatHeader;
    renderChatHeader = function () { prev(); renderAttachmentChip(); };
}
