/* Källpanelen till höger i chatten. */
// ---------- Source panel ----------

function setPanelOpen(open) {
    panel.open = open;
    localStorage.setItem(PANEL_KEY, String(open));
    app.classList.toggle('panel-closed', !open);
    sourcesToggle.classList.toggle('hidden', open);
}

function panelMessage() {
    if (panel.messageIndex === 'live') return liveDraft;
    const thread = activeThread();
    return thread && panel.messageIndex !== null ? thread.messages[panel.messageIndex] || null : null;
}

function resetPanelSelection() {
    panel.messageIndex = null;
    panel.sourceNumber = null;
    panel.showUncited = false;
    const thread = activeThread();
    if (!thread) return;
    for (let i = thread.messages.length - 1; i >= 0; i--) {
        const m = thread.messages[i];
        if (m.role === 'assistant' && m.sources && m.sources.length) {
            panel.messageIndex = i;
            return;
        }
    }
}

function showPanelFor(index, number) {
    if (panel.messageIndex !== index) panel.showUncited = false;
    panel.messageIndex = index;
    panel.sourceNumber = number;
    setPanelOpen(true);
    renderSourcePanel();
}

function selectedSource(split) {
    const pool = split.cited.concat(panel.showUncited || !split.cited.length ? split.uncited : []);
    const number = panel.sourceNumber;
    const all = split.all;
    const match = number !== null ? all.find((s, i) => String(sourceNumber(s, i)) === String(number)) : null;
    if (match) return match;
    return pool[0] || all[0] || null;
}

function sourceButton(source, index, selected) {
    const number = sourceNumber(source, index);
    const btn = el('button', 'source-card' + (source.cited === false ? ' uncited' : ''));
    btn.type = 'button';
    btn.setAttribute('aria-pressed', String(selected));
    btn.appendChild(el('span', 'source-badge', String(number)));
    const text = el('span', 'source-card-text');
    const title = source.title || source.name || source.source || 'Källa';
    text.appendChild(el('span', 'source-card-title', title));
    const bits = [];
    if (source.page !== undefined && source.page !== null) bits.push('Sida ' + source.page);
    if (source.collection) bits.push(source.collection);
    if (typeof source.score === 'number') bits.push('relevans ' + formatScore(source.score));
    text.appendChild(el('span', 'source-card-meta', bits.join(' · ')));
    btn.title = title;
    btn.appendChild(text);
    btn.addEventListener('click', () => {
        panel.sourceNumber = number;
        renderSourcePanel();
    });
    return btn;
}

function sourceRef(source) {
    const title = source.title || source.name || source.source || 'Källa';
    const bits = [];
    if (source.collection) bits.push(source.collection);
    if (source.page !== undefined && source.page !== null) bits.push('sida ' + source.page);
    return {title, meta: bits.join(' · '), documentId: source.documentId, page: source.page, text: source.text || source.content || ''};
}

function renderSourcePanel() {
    sourceBody.innerHTML = '';
    const m = panelMessage();
    const split = splitSources(m && m.sources);
    if (!split.all.length) {
        sourceCount.textContent = '';
        const empty = el('div', 'source-empty');
        empty.appendChild(el('p', null, m && m.searched === false
            ? 'Det här svaret byggde inte på någon sökning i dokumenten.'
            : 'Källorna som ett svar bygger på visas här. Klicka på en siffra i svaret för att se utdraget.'));
        sourceBody.appendChild(empty);
        markActiveCitations();
        return;
    }
    sourceCount.textContent = split.cited.length + ' av ' + split.all.length + ' ingick';
    const current = selectedSource(split);
    if (current) panel.sourceNumber = sourceNumber(current, split.all.indexOf(current));

    const list = el('div', 'source-list');
    const listed = split.cited.length ? split.cited : split.uncited;
    for (const source of listed) {
        list.appendChild(sourceButton(source, split.all.indexOf(source), source === current));
    }
    if (split.cited.length && split.uncited.length) {
        const note = el('div', 'source-note');
        note.appendChild(icon('info', 14));
        const n = split.uncited.length;
        note.appendChild(el('span', null, n + (n === 1 ? ' hämtad källa' : ' hämtade källor') + ' citerades inte i svaret.'));
        const toggle = el('button', 'link-btn', panel.showUncited ? 'Dölj' : 'Visa');
        toggle.type = 'button';
        toggle.setAttribute('aria-expanded', String(panel.showUncited));
        toggle.addEventListener('click', () => {
            panel.showUncited = !panel.showUncited;
            renderSourcePanel();
        });
        note.appendChild(toggle);
        list.appendChild(note);
        if (panel.showUncited) {
            for (const source of split.uncited) {
                list.appendChild(sourceButton(source, split.all.indexOf(source), source === current));
            }
        }
    }
    sourceBody.appendChild(list);
    if (current) sourceBody.appendChild(sourceDetail(current, split.all.indexOf(current)));
    markActiveCitations();
}

function sourceDetail(source, index) {
    const ref = sourceRef(source);
    const wrap = el('div', 'source-detail');
    wrap.appendChild(el('div', 'source-detail-label', 'Utdrag ur källa ' + sourceNumber(source, index)));
    wrap.appendChild(el('p', 'source-excerpt', ref.text || 'Inget utdrag tillgängligt.'));

    const pdf = docPdfUrl(ref);
    if (pdf) {
        const preview = el('div', 'pdf-preview');
        const bar = el('div', 'pdf-preview-bar');
        bar.appendChild(el('span', 'pdf-preview-title', ref.title));
        if (ref.page !== undefined && ref.page !== null) bar.appendChild(el('span', null, 'Sida ' + ref.page));
        preview.appendChild(bar);
        const frame = el('iframe', 'pdf-preview-frame');
        frame.title = 'Förhandsvisning av ' + ref.title;
        frame.loading = 'lazy';
        frame.setAttribute('referrerpolicy', 'no-referrer');
        frame.src = pdf + (pdf.includes('#') ? '&' : '#') + 'toolbar=0&navpanes=0&view=FitH';
        preview.appendChild(frame);
        wrap.appendChild(preview);
    }

    const actions = el('div', 'source-actions');
    if (ref.documentId) {
        const open = el('button', 'primary-btn source-open');
        open.type = 'button';
        open.appendChild(icon('external', 14));
        open.appendChild(el('span', null, ref.page !== undefined && ref.page !== null
            ? 'Öppna PDF på sidan ' + ref.page : 'Öppna dokument'));
        open.addEventListener('click', () => openDocumentPopup(ref));
        actions.appendChild(open);
        const download = el('a', 'icon-square');
        download.href = docDownloadUrl(ref);
        download.setAttribute('aria-label', 'Ladda ner');
        download.title = 'Ladda ner';
        download.appendChild(icon('download', 16));
        actions.appendChild(download);
    }
    const quote = el('button', 'icon-square');
    quote.type = 'button';
    quote.setAttribute('aria-label', 'Kopiera citat');
    quote.title = 'Kopiera citat';
    quote.appendChild(icon('copy', 16));
    quote.addEventListener('click', () => copyText('”' + ref.text.trim() + '” – ' + ref.title
        + (ref.page !== undefined && ref.page !== null ? ', sida ' + ref.page : ''), quote));
    actions.appendChild(quote);
    wrap.appendChild(actions);
    return wrap;
}
