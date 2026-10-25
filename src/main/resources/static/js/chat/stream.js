/* Skicka fråga och ta emot svar via SSE. */
isAnswering = () => streaming;
abortAnswer = () => { if (streamAbort) streamAbort.abort(); };

function sendQuestion(text) {
    if (streaming) return;
    questionInput.value = text;
    if (typeof chatForm.requestSubmit === 'function') {
        chatForm.requestSubmit();
    } else {
        chatForm.dispatchEvent(new Event('submit', {cancelable: true}));
    }
}

function parseEvent(raw) {
    let event = 'message';
    const dataLines = [];
    for (const line of raw.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''));
    }
    if (!dataLines.length) return null;
    return {event, data: dataLines.join('\n')};
}

chatForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    const question = questionInput.value.trim();
    if (!question || streaming) return;
    if (!activeThread()) createThread();

    const thread = activeThread();
    if (!thread.messages.length) messagesInner.innerHTML = '';
    thread.messages.push({role: 'user', text: question});
    touchThread(thread);
    saveThreads();
    renderThreadList();
    renderChatHeader();
    messagesInner.appendChild(renderUser({text: question}));

    questionInput.value = '';
    autoSizeQuestion();
    setStreaming(true);

    const startedAt = performance.now();
    const ui = createLiveTurn();
    messagesInner.appendChild(ui.el);
    scrollToBottom(true);

    const draft = {role: 'assistant', sources: [], searchQuery: '', thoughts: '', validation: null, searched: true};
    liveDraft = draft;
    let contentText = '';
    let originalText = '';
    let answerHtml = '';
    let followUps = [];
    let wasRefined = false;
    let finished = false;

    const fail = (text) => {
        ui.el.remove();
        if (panel.messageIndex === 'live') resetPanelSelection();
        renderSourcePanel();
        renderError(text);
    };

    try {
        streamAbort = new AbortController();
        const response = await fetch(API + '/stream', {
            signal: streamAbort.signal,
            method: 'POST',
            headers: {'Content-Type': 'application/json', 'Accept': 'text/event-stream'},
            body: JSON.stringify({
                message: question,
                sessionId: thread.id,
                collections: thread.collections,
                documentIds: thread.documentIds
            })
        });
        if (!response.ok || !response.body) {
            const error = await response.json().catch(() => ({}));
            fail(error.error || ('Fel: ' + response.status));
            return;
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        while (!finished) {
            const result = await reader.read();
            if (result.done) break;
            buffer += decoder.decode(result.value, {stream: true});
            let sep;
            while (!finished && (sep = buffer.indexOf('\n\n')) !== -1) {
                const raw = buffer.slice(0, sep);
                buffer = buffer.slice(sep + 2);
                const evt = parseEvent(raw);
                if (!evt) continue;

                if (evt.event === 'content') {
                    contentText += JSON.parse(evt.data);
                    ui.setPhase('Skriver svaret…');
                    ui.streamAnswer(contentText);
                } else if (evt.event === 'thought') {
                    draft.thoughts += JSON.parse(evt.data);
                    ui.setPhase('Modellen tänker…');
                    ui.setThoughts(draft.thoughts);
                } else if (evt.event === 'search') {
                    const info = JSON.parse(evt.data) || {};
                    draft.searchQuery = info.query || '';
                    if (info.needed === false) {
                        draft.searched = false;
                        ui.setPhase('Svarar direkt…');
                    }
                    ui.setDraft(draft);
                } else if (evt.event === 'final') {
                    contentText = JSON.parse(evt.data);
                    ui.setAnswerText(contentText);
                    if (draft.searched) ui.setPhase('Kontrollerar mot källorna…');
                    scrollToBottom();
                } else if (evt.event === 'refined') {
                    const refinedText = JSON.parse(evt.data);
                    if (contentText && contentText !== refinedText) originalText = contentText;
                    contentText = refinedText;
                    ui.setAnswerText(contentText);
                    wasRefined = true;
                    draft.refined = true;
                    ui.setDraft(draft);
                } else if (evt.event === 'answerHtml') {
                    answerHtml = JSON.parse(evt.data) || '';
                    if (answerHtml) ui.setAnswerHtml(answerHtml);
                    scrollToBottom();
                } else if (evt.event === 'sources') {
                    draft.sources = JSON.parse(evt.data) || [];
                    if (!contentText) ui.setPhase('Modellen tänker…');
                    ui.setDraft(draft);
                    if (panel.messageIndex === null || panel.messageIndex === 'live'
                        || panel.messageIndex === thread.messages.length - 2) {
                        panel.messageIndex = 'live';
                        renderSourcePanel();
                    }
                } else if (evt.event === 'validation') {
                    draft.validation = JSON.parse(evt.data);
                    ui.setVerdict(draft);
                    ui.setDraft(draft);
                } else if (evt.event === 'followups') {
                    followUps = JSON.parse(evt.data) || [];
                } else if (evt.event === 'error') {
                    const payload = JSON.parse(evt.data) || {};
                    fail(payload.message || 'Kunde inte slutföra svaret.');
                    finished = true;
                } else if (evt.event === 'done') {
                    const message = {
                        role: 'assistant',
                        text: contentText || '(inget svar)',
                        html: answerHtml || null,
                        sources: draft.sources,
                        validation: draft.validation,
                        thoughts: draft.thoughts || null,
                        followUps: followUps.length ? followUps : null,
                        refined: wasRefined,
                        originalText: originalText || null,
                        searched: draft.searched,
                        searchQuery: draft.searchQuery || null,
                        durationMs: Math.round(performance.now() - startedAt)
                    };
                    thread.messages.push(message);
                    touchThread(thread);
                    saveThreads();
                    const index = thread.messages.length - 1;
                    if (panel.messageIndex === 'live') panel.messageIndex = index;
                    else if (panel.messageIndex === null && message.sources && message.sources.length) panel.messageIndex = index;
                    ui.el.replaceWith(renderAssistant(message, index, ui.detailsOpen()));
                    renderThreadList();
                    renderSourcePanel();
                    scrollToBottom();
                    finished = true;
                }
            }
        }
        if (!finished) fail('Svaret avbröts innan det blev klart.');
    } catch (error) {
        fail(error.name === 'AbortError' ? 'Avbrutet: Ollama återställdes.' : 'Nätverksfel: ' + error.message);
    } finally {
        streamAbort = null;
        ui.dispose();
        liveDraft = null;
        setStreaming(false);
        questionInput.focus();
    }
});
