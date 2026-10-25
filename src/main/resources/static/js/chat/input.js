/* Inmatning, tangentbordsgenvägar och klickhändelser i chatten. */
function autoSizeQuestion() {
    questionInput.style.height = 'auto';
    questionInput.style.height = Math.min(questionInput.scrollHeight, 220) + 'px';
}

function isTyping(target) {
    return target && (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName));
}

function anyOverlayOpen() {
    return !docOverlay.classList.contains('hidden')
        || !threadDocOverlay.classList.contains('hidden') || shortcutsDialog.open;
}

newThreadBtn.addEventListener('click', () => createThread());
threadSearch.addEventListener('input', renderThreadList);
threadScopeBtn.addEventListener('click', () => {
    const thread = activeThread() || createThread();
    openThreadDocSelector(thread.id);
});
exportBtn.addEventListener('click', exportThread);
sourcesToggle.addEventListener('click', () => { setPanelOpen(true); renderSourcePanel(); });
sourcePanelClose.addEventListener('click', () => setPanelOpen(false));
questionInput.addEventListener('input', autoSizeQuestion);
questionInput.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
        event.preventDefault();
        if (!streaming) sendQuestion(questionInput.value);
    }
});

document.addEventListener('click', (event) => {
    const link = event.target.closest('a.citation');
    if (!link) return;
    event.preventDefault();
    const index = link.dataset.messageIndex === 'live' ? 'live' : Number(link.dataset.messageIndex);
    showPanelFor(index, link.dataset.sourceNumber);
});

document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
        if (!docOverlay.classList.contains('hidden')) closeDocumentPopup();
        else if (!threadDocOverlay.classList.contains('hidden')) closeThreadDocSelector();
        else if (panel.open && window.matchMedia('(max-width: 1180px)').matches) setPanelOpen(false);
        return;
    }
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        if (anyOverlayOpen()) return;
        event.preventDefault();
        threadSearch.focus();
        threadSearch.select();
        return;
    }
    if (event.ctrlKey || event.metaKey || event.altKey || isTyping(event.target) || anyOverlayOpen()) return;
    if (event.key === '/') {
        event.preventDefault();
        questionInput.focus();
    } else if (event.key === '?') {
        event.preventDefault();
        shortcutsDialog.showModal();
    }
});
