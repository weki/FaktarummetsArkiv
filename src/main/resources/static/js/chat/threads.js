/* Trådhantering i chatten: skapa, välj, ta bort, trådlista, rubrik. */
function createThread() {
    const now = Date.now();
    const thread = {id: crypto.randomUUID(), messages: [], collections: [], documentIds: [], createdAt: now, updatedAt: now};
    threads.unshift(thread);
    activeId = thread.id;
    saveThreads();
    renderAll();
    questionInput.focus();
    return thread;
}

function selectThread(id) {
    activeId = id;
    renderAll();
    questionInput.focus();
}

function deleteThread(id) {
    const thread = threads.find(t => t.id === id);
    if (!thread) return;
    if (!confirm('Ta bort tråden "' + threadTitle(thread) + '"?')) return;

    threads = threads.filter(t => t.id !== id);
    if (activeId === id) {
        activeId = threads.length ? sortedThreads()[0].id : null;
    }
    saveThreads();

    if (!threads.length) {
        createThread();
        return;
    }
    renderAll();
    questionInput.focus();
}

function renderAll() {
    resetPanelSelection();
    renderThreadList();
    renderChatHeader();
    renderMessages();
    renderSourcePanel();
}

function renderThreadList() {
    renderThreadListInto(threadList, threadSearch.value, {
        activeId,
        onSelect: selectThread,
        onDelete: deleteThread
    });
}

function renderChatHeader() {
    const thread = activeThread();
    const title = thread ? threadTitle(thread) : 'Ny tråd';
    threadTitleEl.textContent = title;
    threadTitleEl.title = title;
    const scope = scopeLabel(thread);
    const stale = thread ? staleCollections(thread) : [];
    let scopeText = scope.charAt(0).toUpperCase() + scope.slice(1);
    if (stale.length) {
        scopeText += ' · ' + stale.length + (stale.length === 1 ? ' källsystem saknas' : ' källsystem saknas') + ' ⚠';
    }
    threadScopeLabel.textContent = scopeText;
    threadScopeLabel.classList.toggle('scope-stale', stale.length > 0);
    threadScopeBtn.classList.toggle('has-selection', scope !== 'alla dokument');
    exportBtn.disabled = !thread || !thread.messages.length;
    document.title = (thread && thread.messages.length ? title + ' – ' : '') + 'Fakturummets Arkiv';
}
