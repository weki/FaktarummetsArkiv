/* Rendering av meddelanden, svar, detaljer och pågående svar. */
function formatSeconds(ms) {
    return (ms / 1000).toLocaleString('sv-SE', {minimumFractionDigits: 1, maximumFractionDigits: 1}) + ' s';
}

function formatScore(score) {
    return score.toLocaleString('sv-SE', {minimumFractionDigits: 2, maximumFractionDigits: 2});
}

function isNearBottom() {
    return messages.scrollHeight - messages.scrollTop - messages.clientHeight < 160;
}

function scrollToBottom(force) {
    if (force || isNearBottom()) messages.scrollTop = messages.scrollHeight;
}

function renderMessages() {
    messagesInner.innerHTML = '';
    const thread = activeThread();
    if (!thread || !thread.messages.length) {
        const empty = el('div', 'empty-state');
        empty.appendChild(el('h3', null, 'Vad vill du veta?'));
        empty.appendChild(el('p', null, 'Svaren bygger på dokumenten i ditt lokala arkiv. '
            + 'Varje påstående kontrolleras mot källorna och du kan granska utdragen i panelen till höger.'));
        messagesInner.appendChild(empty);
        return;
    }
    thread.messages.forEach((m, index) => {
        messagesInner.appendChild(m.role === 'assistant' ? renderAssistant(m, index) : renderUser(m));
    });
    scrollToBottom(true);
}

function renderUser(m) {
    return el('div', 'msg user', m.text);
}

function renderError(text) {
    messagesInner.appendChild(el('div', 'msg error', text));
    scrollToBottom(true);
}

function splitSources(sources) {
    const list = Array.isArray(sources) ? sources : [];
    const hasFlags = list.some(source => Object.prototype.hasOwnProperty.call(source, 'cited'));
    return {
        all: list,
        cited: hasFlags ? list.filter(source => source.cited === true) : list.slice(),
        uncited: hasFlags ? list.filter(source => source.cited !== true) : []
    };
}

function sourceNumber(source, index) {
    return source.citationNumber || index + 1;
}

function verdictInfo(m) {
    const v = m.validation;
    if (m.searched === false) return {cls: 'neutral', icon: 'info', label: 'Direkt svar utan sökning'};
    if (!v) return {cls: 'neutral', icon: 'info', label: 'Ej verifierat'};
    if (v.verdict === 'SUPPORTED' || (!v.verdict && v.supported === true)) {
        return {cls: 'ok', icon: 'check', label: 'Verifierat mot källorna'};
    }
    if (v.verdict === 'PARTIALLY_SUPPORTED') return {cls: 'warn', icon: 'warn', label: 'Delvis verifierat'};
    if (v.verdict === 'NOT_ENOUGH_INFO') return {cls: 'info', icon: 'info', label: 'Källorna räcker inte för att verifiera'};
    if (v.refused === true) return {cls: 'info', icon: 'info', label: 'Inget svar i källorna'};
    if (v.verdict === 'UNSUPPORTED' || v.supported === false) return {cls: 'bad', icon: 'warn', label: 'Ej styrkt av källorna'};
    return {cls: 'neutral', icon: 'info', label: 'Ej verifierat'};
}

function verdictBadge(info) {
    const badge = el('span', 'verdict ' + info.cls);
    badge.appendChild(icon(info.icon, 14));
    badge.appendChild(document.createTextNode(info.label));
    return badge;
}

function controlText(m) {
    const v = m.validation;
    if (!v) return 'Ingen verifiering';
    const claims = Array.isArray(v.unsupportedClaims) ? v.unsupportedClaims.length : 0;
    if (v.verdict === 'SUPPORTED' || (!v.verdict && v.supported === true)) return 'Alla påståenden stöds av källorna';
    if (v.verdict === 'PARTIALLY_SUPPORTED') {
        return claims ? claims + (claims === 1 ? ' påstående' : ' påståenden') + ' kunde inte styrkas' : 'Delvis styrkt av källorna';
    }
    if (v.verdict === 'NOT_ENOUGH_INFO') return 'Källorna räcker inte för att verifiera svaret';
    if (v.refused === true) return 'Modellen hittade inget svar i källorna';
    return 'Svaret stöds inte av källorna' + (v.reason ? ': ' + v.reason : '');
}

function buildDetails(rows) {
    const box = el('div', 'turn-details');
    for (const row of rows) {
        if (!row.value) continue;
        box.appendChild(el('div', 'detail-label', row.label));
        box.appendChild(el('div', 'detail-value' + (row.cls ? ' ' + row.cls : ''), row.value));
    }
    return box;
}

function detailRows(m) {
    const split = splitSources(m.sources);
    const search = m.searched === false
        ? 'Ingen sökning behövdes'
        : split.all.length + (split.all.length === 1 ? ' träff' : ' träffar') + ' · ' + split.cited.length + ' ingick i svaret';
    return [
        {label: 'Sökfråga', value: m.searchQuery, cls: 'mono'},
        {label: 'Sökning', value: search},
        {label: 'Kontroll', value: m.searched === false ? null : controlText(m)},
        {label: 'Svar', value: m.refined ? 'Omformulerat för att grundas i källorna' : null},
        {label: 'Tankar', value: m.thoughts, cls: 'thought-text'}
    ];
}

function buildStatusRow(index) {
    const row = el('div', 'turn-status');
    const badgeSlot = el('span', 'verdict-slot');
    const sourcesBtn = el('button', 'status-link');
    sourcesBtn.type = 'button';
    sourcesBtn.addEventListener('click', () => showPanelFor(index, null));
    const time = el('span', 'status-meta');
    const toggle = el('button', 'details-toggle', 'Detaljer');
    toggle.type = 'button';
    toggle.setAttribute('aria-expanded', 'false');
    toggle.appendChild(icon('chevron', 14));
    row.appendChild(badgeSlot);
    row.appendChild(sourcesBtn);
    row.appendChild(time);
    row.appendChild(toggle);
    return {row, badgeSlot, sourcesBtn, time, toggle};
}

function wireDetailsToggle(toggle, details, open) {
    details.hidden = !open;
    toggle.setAttribute('aria-expanded', String(open));
    toggle.addEventListener('click', () => {
        details.hidden = !details.hidden;
        toggle.setAttribute('aria-expanded', String(!details.hidden));
    });
}

function setSourcesLabel(status, m) {
    const split = splitSources(m.sources);
    if (m.searched === false || !split.all.length) {
        status.sourcesBtn.textContent = m.searched === false ? '' : 'Inga källor hittades';
        status.sourcesBtn.disabled = true;
        status.sourcesBtn.hidden = m.searched === false;
        return;
    }
    status.sourcesBtn.hidden = false;
    status.sourcesBtn.disabled = false;
    status.sourcesBtn.textContent = split.cited.length + ' av ' + split.all.length + ' källor ingick';
}

function decorateCitations(answer, index) {
    answer.querySelectorAll('a.citation').forEach(link => {
        const number = link.dataset.sourceNumber;
        link.textContent = number;
        link.setAttribute('role', 'button');
        link.setAttribute('aria-label', 'Källa ' + number);
        link.dataset.messageIndex = String(index);
    });
    markActiveCitations();
}

function markActiveCitations() {
    document.querySelectorAll('a.citation').forEach(link => {
        const active = String(panel.messageIndex) === link.dataset.messageIndex
            && String(panel.sourceNumber) === link.dataset.sourceNumber;
        link.classList.toggle('active', active);
    });
}

function markUnsupported(answer, claims) {
    if (!Array.isArray(claims)) return;
    for (const raw of claims) {
        const claim = (raw || '').trim().replace(/^["”“']+|["”“']+$/g, '');
        if (claim.length < 8) continue;
        const walker = document.createTreeWalker(answer, NodeFilter.SHOW_TEXT);
        let node;
        while ((node = walker.nextNode())) {
            const at = node.nodeValue.indexOf(claim);
            if (at === -1) continue;
            const range = document.createRange();
            range.setStart(node, at);
            range.setEnd(node, at + claim.length);
            const mark = el('span', 'unsupported-mark');
            mark.title = 'Kunde inte styrkas mot källorna';
            range.surroundContents(mark);
            break;
        }
    }
}

function fillAnswer(answer, m, index) {
    if (m.html) {
        answer.classList.add('md');
        setSafeHtml(answer, m.html);
        decorateCitations(answer, index);
    } else {
        answer.classList.remove('md');
        answer.textContent = m.text || '';
    }
}

function renderAssistant(m, index, detailsOpen) {
    const turn = el('div', 'turn');
    turn.dataset.index = String(index);

    const status = buildStatusRow(index);
    status.badgeSlot.appendChild(verdictBadge(verdictInfo(m)));
    setSourcesLabel(status, m);
    status.time.textContent = m.durationMs ? formatSeconds(m.durationMs) : '';
    turn.appendChild(status.row);

    const details = buildDetails(detailRows(m));
    wireDetailsToggle(status.toggle, details, !!detailsOpen);
    turn.appendChild(details);

    const card = el('div', 'answer-card');
    const answer = el('div', 'answer');
    fillAnswer(answer, m, index);
    card.appendChild(answer);
    const v = m.validation;
    if (v && Array.isArray(v.unsupportedClaims) && v.unsupportedClaims.length) {
        markUnsupported(answer, v.unsupportedClaims);
        const box = el('div', 'claims-box');
        box.appendChild(el('strong', null, 'Kunde inte styrkas mot källorna:'));
        const list = el('ul');
        for (const claim of v.unsupportedClaims) list.appendChild(el('li', null, '”' + claim + '”'));
        box.appendChild(list);
        card.appendChild(box);
    } else if (v && v.supported === false && v.reason && v.verdict !== 'NOT_ENOUGH_INFO') {
        const box = el('div', 'claims-box');
        box.appendChild(el('strong', null, 'Varför: '));
        box.appendChild(document.createTextNode(v.reason));
        card.appendChild(box);
    }
    if (m.originalText) {
        const original = el('details', 'original-answer');
        original.appendChild(el('summary', null, 'Ursprungligt svar (innan omformulering)'));
        original.appendChild(el('div', 'original-text', m.originalText));
        card.appendChild(original);
    }
    turn.appendChild(card);

    turn.appendChild(buildActions(m, index));
    if (m.followUps && m.followUps.length) turn.appendChild(buildFollowUps(m.followUps));
    return turn;
}

function actionButton(iconName, label, onClick, iconOnly) {
    const btn = el('button', iconOnly ? 'action-btn icon-only' : 'action-btn');
    btn.type = 'button';
    btn.appendChild(icon(iconName, 14, iconName === 'thumb' && label === 'Dåligt svar' ? 'flip' : ''));
    if (iconOnly) {
        btn.setAttribute('aria-label', label);
        btn.title = label;
    } else {
        btn.appendChild(el('span', null, label));
    }
    btn.addEventListener('click', onClick);
    return btn;
}

async function copyText(text, btn) {
    const label = btn.querySelector('span:not(.icon)');
    const original = label ? label.textContent : null;
    let ok = true;
    try {
        await navigator.clipboard.writeText(text);
    } catch (error) {
        ok = false;
    }
    if (label) label.textContent = ok ? 'Kopierat' : 'Kunde inte kopiera';
    btn.title = ok ? 'Kopierat' : 'Kunde inte kopiera';
    setTimeout(() => {
        if (label) label.textContent = original;
        btn.title = btn.getAttribute('aria-label') || '';
    }, 1500);
}

function buildActions(m, index) {
    const row = el('div', 'turn-actions');
    const copy = actionButton('copy', 'Kopiera', () => copyText(m.text || '', copy));
    const retry = actionButton('retry', 'Generera om', () => regenerate(index));
    retry.classList.add('regen-btn');
    retry.disabled = streaming;
    const up = actionButton('thumb', 'Bra svar', () => setFeedback(index, 'up'), true);
    const down = actionButton('thumb', 'Dåligt svar', () => setFeedback(index, 'down'), true);
    up.setAttribute('aria-pressed', String(m.feedback === 'up'));
    down.setAttribute('aria-pressed', String(m.feedback === 'down'));
    row.appendChild(copy);
    row.appendChild(retry);
    row.appendChild(up);
    row.appendChild(down);
    return row;
}

function buildFollowUps(questions) {
    const wrap = el('div', 'followups');
    for (const q of questions) {
        const btn = el('button', 'followup-btn', q);
        btn.type = 'button';
        btn.addEventListener('click', () => sendQuestion(q));
        wrap.appendChild(btn);
    }
    return wrap;
}

function replaceTurn(index) {
    const thread = activeThread();
    const current = messagesInner.querySelector('.turn[data-index="' + index + '"]');
    if (!thread || !current) return;
    const details = current.querySelector('.turn-details');
    current.replaceWith(renderAssistant(thread.messages[index], index, details && !details.hidden));
}

function setFeedback(index, value) {
    const thread = activeThread();
    const m = thread && thread.messages[index];
    if (!m) return;
    m.feedback = m.feedback === value ? null : value;
    saveThreads();
    replaceTurn(index);
}

function regenerate(index) {
    const thread = activeThread();
    if (!thread || streaming) return;
    let q = index - 1;
    while (q >= 0 && thread.messages[q].role !== 'user') q--;
    if (q < 0) return;
    const question = thread.messages[q].text;
    if (index === thread.messages.length - 1) {
        thread.messages.splice(q, index - q + 1);
        saveThreads();
        resetPanelSelection();
        renderMessages();
    }
    sendQuestion(question);
}

function setStreaming(value) {
    streaming = value;
    sendBtn.disabled = value;
    document.querySelectorAll('.regen-btn').forEach(btn => { btn.disabled = value; });
}

function createLiveTurn() {
    const turn = el('div', 'turn live');
    const status = buildStatusRow('live');
    const phase = el('span', 'verdict pending');
    phase.appendChild(icon('spinner', 14, 'spin'));
    const phaseText = el('span', null, 'Söker i källor…');
    phase.appendChild(phaseText);
    status.badgeSlot.appendChild(phase);
    status.sourcesBtn.hidden = true;
    turn.appendChild(status.row);

    let details = buildDetails([]);
    wireDetailsToggle(status.toggle, details, false);
    turn.appendChild(details);

    const thoughtBox = el('details', 'live-thoughts');
    thoughtBox.hidden = true;
    thoughtBox.open = true;
    const thoughtSummary = el('summary', null, 'Modellens tankar');
    thoughtSummary.addEventListener('click', () => { thoughtBox.dataset.touched = 'true'; });
    thoughtBox.appendChild(thoughtSummary);
    const thoughtText = el('div', 'live-thought-text');
    thoughtBox.appendChild(thoughtText);
    turn.appendChild(thoughtBox);

    const card = el('div', 'answer-card');
    const answer = el('div', 'answer draft');
    card.appendChild(answer);
    turn.appendChild(card);

    // Token events arrive far faster than the screen refreshes; coalesce
    // them so each frame does at most one text write and one scroll.
    let pendingThoughts = null;
    let pendingAnswer = null;
    let frame = 0;
    const flush = () => {
        frame = 0;
        if (pendingThoughts !== null) {
            thoughtBox.hidden = false;
            thoughtText.textContent = pendingThoughts;
            thoughtText.scrollTop = thoughtText.scrollHeight;
            pendingThoughts = null;
        }
        if (pendingAnswer !== null) {
            answer.classList.remove('md');
            answer.textContent = pendingAnswer;
            pendingAnswer = null;
        }
        scrollToBottom();
    };
    const schedule = () => { if (!frame) frame = requestAnimationFrame(flush); };
    const cancel = () => { if (frame) { cancelAnimationFrame(frame); frame = 0; } pendingAnswer = null; };

    return {
        el: turn,
        setPhase(text) { if (phaseText.textContent !== text) phaseText.textContent = text; },
        setThoughts(text) { pendingThoughts = text; schedule(); },
        streamAnswer(text) {
            if (thoughtBox.open && !thoughtBox.dataset.touched) thoughtBox.open = false;
            pendingAnswer = text;
            schedule();
        },
        setAnswerText(text) { cancel(); answer.classList.remove('md'); answer.textContent = text; },
        setAnswerHtml(html) { cancel(); answer.classList.add('md'); setSafeHtml(answer, html); decorateCitations(answer, 'live'); },
        dispose() { if (frame) cancelAnimationFrame(frame); frame = 0; },
        setDraft(draft) {
            // Thoughts are shown live in thoughtBox, not repeated here.
            const fresh = buildDetails(detailRows(Object.assign({}, draft, {thoughts: null})));
            fresh.hidden = details.hidden;
            details.replaceWith(fresh);
            details = fresh;
            setSourcesLabel(status, draft);
            if (!draft.sources || !draft.sources.length) status.sourcesBtn.hidden = true;
        },
        setVerdict(draft) {
            status.badgeSlot.innerHTML = '';
            status.badgeSlot.appendChild(verdictBadge(verdictInfo(draft)));
        },
        detailsOpen() { return !details.hidden; }
    };
}
