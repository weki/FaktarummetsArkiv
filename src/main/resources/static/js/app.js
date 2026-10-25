const API = '/api/chat';
    const DOC_API = '/api/documents';

    const app = document.getElementById('app');
    const messages = document.getElementById('messages');
    const messagesInner = document.getElementById('messagesInner');
    const chatForm = document.getElementById('chatForm');
    const questionInput = document.getElementById('question');
    const sendBtn = document.getElementById('sendBtn');
    const newThreadBtn = document.getElementById('newThreadBtn');
    const threadList = document.getElementById('threadList');
    const threadSearch = document.getElementById('threadSearch');
    const threadTitleEl = document.getElementById('threadTitle');
    const threadScopeBtn = document.getElementById('threadScopeBtn');
    const threadScopeLabel = document.getElementById('threadScopeLabel');
    const exportBtn = document.getElementById('exportBtn');
    const sourcesToggle = document.getElementById('sourcesToggle');
    const sourcePanelClose = document.getElementById('sourcePanelClose');
    const sourceCount = document.getElementById('sourceCount');
    const sourceBody = document.getElementById('sourceBody');
    const modelStatus = document.getElementById('modelStatus');
    const modelStatusText = document.getElementById('modelStatusText');
    const resetOllamaBtn = document.getElementById('resetOllamaBtn');
    const shortcutsDialog = document.getElementById('shortcutsDialog');
    const collectionList = document.getElementById('collectionList');
    const documentsBtn = document.getElementById('documentsBtn');
    const documentsPage = document.getElementById('documentsPage');
    const documentsClose = document.getElementById('documentsClose');
    const uploadForm = document.getElementById('uploadForm');
    const uploadBtn = document.getElementById('uploadBtn');
    const uploadCollection = document.getElementById('uploadCollection');
    const newCollectionField = document.getElementById('newCollectionField');
    const newCollectionName = document.getElementById('newCollectionName');
    const uploadTags = document.getElementById('uploadTags');
    const uploadFolderBtn = document.getElementById('uploadFolderBtn');
    const uploadFolderInput = document.getElementById('uploadFolderInput');
    const uploadProgress = document.getElementById('uploadProgress');
    const uploadProgressLabel = document.getElementById('uploadProgressLabel');
    const uploadProgressPct = document.getElementById('uploadProgressPct');
    const uploadProgressFill = document.getElementById('uploadProgressFill');
    const uploadProgressDetail = document.getElementById('uploadProgressDetail');
    const tagSuggestions = document.getElementById('tagSuggestions');
    const documentsStatus = document.getElementById('documentsStatus');
    const docList = document.getElementById('docList');
    const docsSelectAll = document.getElementById('docsSelectAll');
    const docsBulkDownload = document.getElementById('docsBulkDownload');
    const docsBulkDelete = document.getElementById('docsBulkDelete');
    const docsBulkCount = document.getElementById('docsBulkCount');
    const selectedDocIds = new Set(JSON.parse(localStorage.getItem('selectedDocIds') || '[]'));
    function persistSelectedDocIds() { localStorage.setItem('selectedDocIds', JSON.stringify(Array.from(selectedDocIds))); }
    const docsSearch = document.getElementById('docsSearch');
    const docsFilterCollection = document.getElementById('docsFilterCollection');
    const docsFilterTag = document.getElementById('docsFilterTag');
    const docsSort = document.getElementById('docsSort');
    const collectionsStatus = document.getElementById('collectionsStatus');
    const collectionsSave = document.getElementById('collectionsSave');
    const newCollectionInput = document.getElementById('newCollectionInput');
    const createCollectionBtn = document.getElementById('createCollectionBtn');
    const docOverlay = document.getElementById('docOverlay');
    const docModalClose = document.getElementById('docModalClose');
    const docModalTitle = document.getElementById('docModalTitle');
    const docModalMeta = document.getElementById('docModalMeta');
    const docModalActions = document.getElementById('docModalActions');
    const docModalFrame = document.getElementById('docModalFrame');
    const docModalText = document.getElementById('docModalText');

    const threadDocOverlay = document.getElementById('threadDocOverlay');
    const threadDocClose = document.getElementById('threadDocClose');
    const threadDocCollectionList = document.getElementById('threadDocCollectionList');
    const threadDocList = document.getElementById('threadDocList');
    const threadDocSearch = document.getElementById('threadDocSearch');
    const threadDocFilterCollection = document.getElementById('threadDocFilterCollection');
    const threadDocSave = document.getElementById('threadDocSave');
    const threadDocStatus = document.getElementById('threadDocStatus');

    let threadDocTargetId = null;

    const STORAGE_KEY = 'fakturummets_arkiv_rag_threads';
    const PANEL_KEY = 'source_panel_open';

    const ICONS = {
        check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
        warn: '<path d="M12 3l10 18H2z"/><path d="M12 10v5M12 18v.5"/>',
        info: '<circle cx="12" cy="12" r="9"/><path d="M12 8v5M12 16v.5"/>',
        cross: '<path d="M6 6l12 12M18 6L6 18"/>',
        chevron: '<path d="M6 9l6 6 6-6"/>',
        copy: '<rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V6a2 2 0 012-2h9"/>',
        retry: '<path d="M20 12a8 8 0 11-2.3-5.7M20 4v5h-5"/>',
        thumb: '<path d="M7 11v9H4v-9zM7 11l4-8a2 2 0 012 2v4h6a2 2 0 012 2.3l-1.2 7A2 2 0 0117.8 20H7"/>',
        download: '<path d="M12 4v11M7 11l5 5 5-5M5 20h14"/>',
        external: '<path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 01-1 1H5a1 1 0 01-1-1V7a1 1 0 011-1h5"/>',
        spinner: '<path d="M12 3a9 9 0 109 9"/>'
    };

    function icon(name, size, extraClass) {
        const span = document.createElement('span');
        span.className = 'icon' + (extraClass ? ' ' + extraClass : '');
        span.setAttribute('aria-hidden', 'true');
        span.innerHTML = '<svg width="' + size + '" height="' + size + '" viewBox="0 0 24 24" fill="none" '
            + 'stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">'
            + ICONS[name] + '</svg>';
        return span;
    }

    function el(tag, className, text) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined && text !== null) node.textContent = text;
        return node;
    }

    let threads = loadThreads();
    let activeId = threads.length ? sortedThreads()[0].id : null;
    let streaming = false;
    let liveDraft = null;
    let streamAbort = null;
    const panel = {
        messageIndex: null,
        sourceNumber: null,
        showUncited: false,
        open: localStorage.getItem(PANEL_KEY) !== 'false'
    };

    function loadThreads() {
        try {
            const raw = localStorage.getItem(STORAGE_KEY);
            const parsed = raw ? JSON.parse(raw) : [];
            return Array.isArray(parsed) ? parsed : [];
        } catch (e) {
            return [];
        }
    }

    function saveThreads() {
        try {
            localStorage.setItem(STORAGE_KEY, JSON.stringify(threads));
        } catch (e) { /* ignore quota errors */ }
    }

    function activeThread() {
        return threads.find(t => t.id === activeId) || null;
    }

    function threadTitle(thread) {
        const firstUser = thread.messages.find(m => m.role === 'user');
        return firstUser ? firstUser.text : 'Ny tråd';
    }

    function sortedThreads() {
        return threads.slice().sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
    }

    function touchThread(thread) {
        thread.updatedAt = Date.now();
    }

    function scopeLabel(thread) {
        const docs = (thread && thread.documentIds) ? thread.documentIds.length : 0;
        const cols = (thread && thread.collections) ? thread.collections.length : 0;
        if (docs) return docs + (docs === 1 ? ' dokument valt' : ' dokument valda');
        if (cols) return cols + (cols === 1 ? ' kollektion' : ' kollektioner');
        return 'alla dokument';
    }

    function isToday(ts) {
        return ts && new Date(ts).toDateString() === new Date().toDateString();
    }

    function formatThreadTime(ts) {
        if (!ts) return '';
        const date = new Date(ts);
        if (isToday(ts)) return date.toLocaleTimeString('sv-SE', {hour: '2-digit', minute: '2-digit'});
        const yesterday = new Date();
        yesterday.setDate(yesterday.getDate() - 1);
        if (date.toDateString() === yesterday.toDateString()) return 'Igår';
        return date.toLocaleDateString('sv-SE', {day: 'numeric', month: 'short'}).replace(/\.$/, '');
    }

    function formatSeconds(ms) {
        return (ms / 1000).toLocaleString('sv-SE', {minimumFractionDigits: 1, maximumFractionDigits: 1}) + ' s';
    }

    function formatScore(score) {
        return score.toLocaleString('sv-SE', {minimumFractionDigits: 2, maximumFractionDigits: 2});
    }

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
        threadList.innerHTML = '';
        const term = threadSearch.value.trim().toLowerCase();
        const visible = sortedThreads().filter(thread => !term
            || thread.messages.some(m => (m.text || '').toLowerCase().includes(term))
            || threadTitle(thread).toLowerCase().includes(term));
        if (!visible.length) {
            threadList.appendChild(el('div', 'thread-empty', 'Inga trådar matchar sökningen.'));
            return;
        }
        const groups = [
            {label: 'Idag', items: visible.filter(t => isToday(t.updatedAt))},
            {label: 'Tidigare', items: visible.filter(t => !isToday(t.updatedAt))}
        ];
        for (const group of groups) {
            if (!group.items.length) continue;
            threadList.appendChild(el('div', 'thread-group', group.label));
            const list = el('ul', 'thread-ul');
            for (const thread of group.items) {
                const item = el('li', 'thread-row');
                const btn = el('button', 'thread-item');
                btn.type = 'button';
                if (thread.id === activeId) btn.setAttribute('aria-current', 'true');
                btn.title = threadTitle(thread);
                btn.appendChild(el('span', 'thread-name', threadTitle(thread)));
                const time = formatThreadTime(thread.updatedAt);
                btn.appendChild(el('span', 'thread-meta', (time ? time + ' · ' : '') + scopeLabel(thread)));
                btn.addEventListener('click', () => selectThread(thread.id));

                const del = el('button', 'thread-delete');
                del.type = 'button';
                del.title = 'Ta bort tråd';
                del.setAttribute('aria-label', 'Ta bort tråden ' + threadTitle(thread));
                del.appendChild(icon('cross', 14));
                del.addEventListener('click', () => deleteThread(thread.id));

                item.appendChild(btn);
                item.appendChild(del);
                list.appendChild(item);
            }
            threadList.appendChild(list);
        }
    }

    function renderChatHeader() {
        const thread = activeThread();
        const title = thread ? threadTitle(thread) : 'Ny tråd';
        threadTitleEl.textContent = title;
        threadTitleEl.title = title;
        const scope = scopeLabel(thread);
        threadScopeLabel.textContent = scope.charAt(0).toUpperCase() + scope.slice(1);
        threadScopeBtn.classList.toggle('has-selection', scope !== 'alla dokument');
        exportBtn.disabled = !thread || !thread.messages.length;
        document.title = (thread && thread.messages.length ? title + ' – ' : '') + 'Fakturummets Arkiv';
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
            answer.innerHTML = m.html;
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
            setAnswerHtml(html) { cancel(); answer.classList.add('md'); answer.innerHTML = html; decorateCitations(answer, 'live'); },
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

    // ---------- Export ----------

    function exportThread() {
        const thread = activeThread();
        if (!thread || !thread.messages.length) return;
        const lines = ['# ' + threadTitle(thread), '', '_Exporterad ' + formatDate(new Date().toISOString())
            + ' · ' + scopeLabel(thread) + '_', ''];
        for (const m of thread.messages) {
            if (m.role === 'user') {
                lines.push('## Fråga', '', m.text, '');
                continue;
            }
            lines.push('## Svar', '', m.text || '', '');
            if (m.searched !== false) lines.push('**Kontroll:** ' + verdictInfo(m).label, '');
            const cited = splitSources(m.sources).cited;
            if (cited.length) {
                lines.push('**Källor:**', '');
                cited.forEach((source, i) => {
                    const ref = sourceRef(source);
                    lines.push(sourceNumber(source, i) + '. ' + ref.title + (ref.meta ? ' (' + ref.meta + ')' : ''));
                });
                lines.push('');
            }
        }
        const blob = new Blob([lines.join('\n')], {type: 'text/markdown;charset=utf-8'});
        const link = document.createElement('a');
        link.href = URL.createObjectURL(blob);
        const slug = threadTitle(thread).toLowerCase().replace(/[^a-z0-9åäö]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60);
        link.download = (slug || 'trad') + '.md';
        document.body.appendChild(link);
        link.click();
        link.remove();
        setTimeout(() => URL.revokeObjectURL(link.href), 1000);
    }

    // ---------- Model status ----------

    async function loadModelStatus() {
        try {
            const [settingsRes, modelsRes] = await Promise.all([fetch(API + '/settings'), fetch(API + '/models')]);
            const settings = await settingsRes.json().catch(() => ({}));
            const models = modelsRes.ok ? await modelsRes.json().catch(() => []) : [];
            const chat = settings.chatModel || 'okänd modell';
            const embed = settings.embeddingModel ? settings.embeddingModel.split('/').pop() : null;
            const label = chat + (embed ? ' + ' + embed : '');
            const available = Array.isArray(models) && models.length;
            const missing = available && settings.chatModel && !models.includes(settings.chatModel);
            modelStatus.className = 'model-status ' + (!available ? 'err' : missing ? 'warn' : 'ok');
            modelStatusText.textContent = !available ? 'Ollama svarar inte' : missing ? label + ' · saknas' : label;
            modelStatus.title = label;
        } catch (error) {
            modelStatus.className = 'model-status err';
            modelStatusText.textContent = 'Kunde inte nå servern';
        }
    }

    async function resetOllama() {
        if (streaming && !confirm('Ett svar genereras just nu. Avbryta det och återställa Ollama?')) return;
        resetOllamaBtn.disabled = true;
        modelStatus.className = 'model-status';
        modelStatusText.textContent = 'Återställer Ollama…';
        if (streamAbort) streamAbort.abort();
        try {
            const res = await fetch(API + '/ollama/reset', {
                method: 'POST',
                headers: {'X-Requested-With': 'FakturummetsArkiv'}
            });
            const data = await res.json().catch(() => ({}));
            if (!res.ok) throw new Error(data.error || ('Fel: ' + res.status));
            const unloaded = (data.unloadedModels || []).length;
            const failed = (data.failedModels || []).length;
            await loadModelStatus();
            const summary = 'Återställd: ' + unloaded + (unloaded === 1 ? ' modell urladdad' : ' modeller urladdade')
                + (failed ? ', ' + failed + ' misslyckades' : '');
            modelStatus.title = summary + (data.cancelledRequests ? ' · ' + data.cancelledRequests + ' anrop avbrutna' : '');
            modelStatusText.textContent = summary;
            setTimeout(loadModelStatus, 4000);
        } catch (error) {
            modelStatus.className = 'model-status err';
            modelStatusText.textContent = 'Återställning misslyckades';
            modelStatus.title = error.message;
        } finally {
            resetOllamaBtn.disabled = false;
        }
    }

    resetOllamaBtn.addEventListener('click', resetOllama);

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

    function autoSizeQuestion() {
        questionInput.style.height = 'auto';
        questionInput.style.height = Math.min(questionInput.scrollHeight, 220) + 'px';
    }

    function isTyping(target) {
        return target && (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName));
    }

    function anyOverlayOpen() {
        return !documentsPage.classList.contains('hidden') || !docOverlay.classList.contains('hidden')
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
            else if (!documentsPage.classList.contains('hidden')) closeDocuments();
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

    let qdrantCollections = null;

    async function loadCollections(force) {
        if (qdrantCollections && !force) return qdrantCollections;
        try {
            const res = await fetch(DOC_API + '/collections');
            const data = await res.json().catch(() => null);
            qdrantCollections = Array.isArray(data) ? data : [];
        } catch (error) {
            qdrantCollections = [];
        }
        return qdrantCollections;
    }

    let knownTags = [];

    async function loadTags() {
        try {
            const res = await fetch(DOC_API + '/tags');
            const data = await res.json().catch(() => null);
            knownTags = Array.isArray(data) ? data : [];
        } catch (error) {
            knownTags = [];
        }
        return knownTags;
    }

    function renderCollections(available, selected) {
        const chosen = new Set(Array.isArray(selected) ? selected : []);
        const names = new Set(available || []);
        chosen.forEach(name => names.add(name));
        collectionList.innerHTML = '';
        if (!names.size) {
            const empty = document.createElement('div');
            empty.className = 'collection-item';
            const text = document.createElement('span');
            text.className = 'name';
            text.textContent = 'Inga kollektioner hittades';
            empty.appendChild(text);
            collectionList.appendChild(empty);
            return;
        }
        for (const name of names) {
            const label = document.createElement('label');
            label.className = 'collection-item';
            const checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.value = name;
            checkbox.checked = chosen.has(name);
            const text = document.createElement('span');
            text.className = 'name';
            text.textContent = name;
            label.appendChild(checkbox);
            label.appendChild(text);
            collectionList.appendChild(label);
        }
    }

    docModalClose.addEventListener('click', closeDocumentPopup);
    docOverlay.addEventListener('click', (event) => {
        if (event.target === docOverlay) closeDocumentPopup();
    });

    threadDocClose.addEventListener('click', closeThreadDocSelector);
    threadDocOverlay.addEventListener('click', (event) => {
        if (event.target === threadDocOverlay) closeThreadDocSelector();
    });
    threadDocOverlay.querySelectorAll('.tab-btn').forEach(btn => {
        btn.addEventListener('click', () => switchThreadDocTab(btn.dataset.tab));
    });
    threadDocSearch.addEventListener('input', renderThreadDocList);
    threadDocFilterCollection.addEventListener('change', renderThreadDocList);
    threadDocSave.addEventListener('click', saveThreadDocSelection);

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
            const empty = document.createElement('div');
            empty.className = 'doc-item';
            empty.innerHTML = '<span>Inga dokument att visa.</span>';
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
        const row = document.createElement('div');
        row.className = 'doc-item';

        const check = document.createElement('input');
        check.type = 'checkbox';
        check.className = 'doc-check';
        check.checked = selectedDocIds.has(doc.id);
        if (check.checked) row.classList.add('selected');
        check.addEventListener('change', () => {
            if (check.checked) selectedDocIds.add(doc.id); else selectedDocIds.delete(doc.id);
            persistSelectedDocIds();
            row.classList.toggle('selected', check.checked);
            updateBulkBar();
        });

        const main = document.createElement('div');
        main.className = 'doc-main';
        const nameEl = document.createElement('div');
        nameEl.className = 'doc-name';
        nameEl.textContent = doc.name;
        nameEl.title = doc.name;
        const metaEl = document.createElement('div');
        metaEl.className = 'meta';
        let meta = doc.chunks + ' textstycken';
        if (doc.collection) meta += ' · ' + doc.collection;
        if (doc.createdAt) meta += ' · ' + formatDate(doc.createdAt);
        metaEl.textContent = meta;
        main.appendChild(nameEl);
        main.appendChild(metaEl);
        if (doc.tags && doc.tags.length) {
            const tagList = document.createElement('div');
            tagList.className = 'tag-list';
            for (const tag of doc.tags) {
                const chip = document.createElement('span');
                chip.className = 'tag';
                chip.textContent = tag;
                tagList.appendChild(chip);
            }
            main.appendChild(tagList);
        }

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
        del.addEventListener('click', () => deleteDocument(doc.id));

        actions.appendChild(view);
        actions.appendChild(download);
        actions.appendChild(del);

        row.appendChild(check);
        row.appendChild(main);
        row.appendChild(actions);
        return row;
    }

    function formatDate(value) {
        const date = new Date(value);
        if (isNaN(date.getTime())) return '';
        return date.toLocaleDateString('sv-SE') + ' ' +
            date.toLocaleTimeString('sv-SE', {hour: '2-digit', minute: '2-digit'});
    }

    function docPdfUrl(ref) {
        if (!ref.documentId) return null;
        const url = DOC_API + '/' + encodeURIComponent(ref.documentId) + '/pdf';
        return ref.page !== undefined && ref.page !== null ? url + '#page=' + encodeURIComponent(ref.page) : url;
    }

    function docDownloadUrl(ref) {
        return ref.documentId ? DOC_API + '/' + encodeURIComponent(ref.documentId) + '/download' : null;
    }

    function documentMeta(doc) {
        let meta = (doc.chunks || 0) + ' textstycken \u00b7 ' + (doc.characters || 0) + ' tecken';
        if (doc.collection) meta += ' \u00b7 ' + doc.collection;
        if (doc.tags && doc.tags.length) meta += ' \u00b7 ' + doc.tags.join(', ');
        return meta;
    }

    function setDocModalMeta(text) {
        docModalMeta.textContent = text || '';
        docModalMeta.classList.toggle('hidden', !text);
    }

    function showDocModalText(text) {
        docModalText.innerHTML = '';
        if (Array.isArray(text)) {
            if (!text.length) docModalText.textContent = 'Inget textinneh\u00e5ll.';
            for (const chunk of text) {
                const block = document.createElement('div');
                block.className = 'doc-chunk';
                if (chunk.page !== null && chunk.page !== undefined) {
                    const page = document.createElement('div');
                    page.className = 'doc-chunk-page';
                    page.textContent = 'Sida ' + chunk.page;
                    block.appendChild(page);
                }
                const body = document.createElement('div');
                body.textContent = chunk.text || '';
                block.appendChild(body);
                docModalText.appendChild(block);
            }
        } else {
            docModalText.textContent = text || 'Inget textinneh\u00e5ll.';
        }
        docModalText.classList.remove('hidden');
        docModalFrame.classList.add('hidden');
    }

    function showDocModalPdf(url, text) {
        docModalText.classList.add('hidden');
        docModalText.innerHTML = '';
        docModalFrame.src = url;
        docModalFrame.classList.remove('hidden');
        if (!text) return;
        const toggle = document.createElement('button');
        toggle.type = 'button';
        toggle.textContent = 'Visa text';
        toggle.addEventListener('click', () => {
            const showText = docModalText.classList.contains('hidden');
            if (showText) showDocModalText(text);
            else {
                docModalText.classList.add('hidden');
                docModalFrame.classList.remove('hidden');
            }
            toggle.textContent = showText ? 'Visa PDF' : 'Visa text';
        });
        docModalActions.insertBefore(toggle, docModalActions.firstChild);
    }

    async function openDocumentPopup(ref) {
        docOverlay.classList.remove('hidden');
        docModalTitle.textContent = ref.title || 'Dokument';
        setDocModalMeta(ref.meta);
        docModalActions.innerHTML = '';
        docModalFrame.removeAttribute('src');
        docModalFrame.classList.add('hidden');
        docModalText.classList.add('hidden');
        docModalText.innerHTML = '';

        if (ref.documentId && !ref.text) {
            setDocModalMeta('Laddar...');
            try {
                const res = await fetch(DOC_API + '/' + encodeURIComponent(ref.documentId));
                const doc = await res.json().catch(() => ({}));
                if (!res.ok) throw new Error(doc.error || ('HTTP ' + res.status));
                ref = Object.assign({}, ref, {
                    title: ref.title || doc.name,
                    meta: ref.meta || documentMeta(doc),
                    text: ref.text || doc.content || null
                });
            } catch (error) {
                setDocModalMeta('Kunde inte h\u00e4mta: ' + error.message);
            }
        }
        if (ref.title) docModalTitle.textContent = ref.title;
        setDocModalMeta(ref.meta);

        const download = docDownloadUrl(ref);
        if (download) {
            const link = document.createElement('a');
            link.href = download;
            link.textContent = 'Ladda ner';
            docModalActions.appendChild(link);
        }

        const pdf = docPdfUrl(ref);
        if (pdf) showDocModalPdf(pdf, ref.text);
        else showDocModalText(ref.text);
    }

    function closeDocumentPopup() {
        docOverlay.classList.add('hidden');
        docModalFrame.removeAttribute('src');
        docModalFrame.classList.add('hidden');
        docModalText.classList.add('hidden');
        docModalText.innerHTML = '';
        docModalActions.innerHTML = '';
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
        docsBulkCount.textContent = n ? n + ' markerade' : '';
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

    function openDocuments() {
        closeDocumentPopup();
        documentsPage.classList.remove('hidden');
        loadDocuments();
        loadUploadOptions();
    }

    function closeDocuments() {
        closeDocumentPopup();
        documentsPage.classList.add('hidden');
    }

    function openThreadDocSelector(threadId) {
        threadDocTargetId = threadId;
        threadDocOverlay.classList.remove('hidden');
        threadDocOverlay.querySelectorAll('.tab-btn').forEach(btn => {
            btn.classList.toggle('active', btn.dataset.tab === 'collections');
        });
        threadDocOverlay.querySelectorAll('.tab-panel').forEach(panel => {
            panel.classList.toggle('active', panel.dataset.panel === 'collections');
        });
        threadDocSearch.value = '';
        threadDocFilterCollection.value = '';
        loadThreadDocCollections();
        loadThreadDocDocuments();
    }

    function closeThreadDocSelector() {
        threadDocOverlay.classList.add('hidden');
        threadDocTargetId = null;
        threadDocCollectionList.innerHTML = '';
        threadDocList.innerHTML = '';
        threadDocStatus.textContent = '';
        threadDocStatus.className = 'status';
    }

    function switchThreadDocTab(name) {
        threadDocOverlay.querySelectorAll('.tab-btn').forEach(btn => {
            btn.classList.toggle('active', btn.dataset.tab === name);
        });
        threadDocOverlay.querySelectorAll('.tab-panel').forEach(panel => {
            panel.classList.toggle('active', panel.dataset.panel === name);
        });
    }

    async function loadThreadDocCollections() {
        try {
            const res = await fetch(DOC_API + '/collections');
            const collections = await res.json().catch(() => []);
            const thread = threads.find(t => t.id === threadDocTargetId);
            const selected = new Set(thread?.collections || []);
            threadDocCollectionList.innerHTML = '';
            if (!collections.length) {
                const empty = document.createElement('div');
                empty.className = 'collection-item';
                const text = document.createElement('span');
                text.className = 'name';
                text.textContent = 'Inga kollektioner hittades';
                empty.appendChild(text);
                threadDocCollectionList.appendChild(empty);
                return;
            }
            for (const name of collections) {
                const label = document.createElement('label');
                label.className = 'collection-item';
                const checkbox = document.createElement('input');
                checkbox.type = 'checkbox';
                checkbox.value = name;
                checkbox.checked = selected.has(name);
                const text = document.createElement('span');
                text.className = 'name';
                text.textContent = name;
                label.appendChild(checkbox);
                label.appendChild(text);
                threadDocCollectionList.appendChild(label);
            }
        } catch (error) {
            threadDocStatus.textContent = 'Kunde inte hämta kollektioner: ' + error.message;
            threadDocStatus.className = 'status err';
        }
    }

    async function loadThreadDocDocuments() {
        try {
            const res = await fetch(DOC_API);
            const documents = await res.json().catch(() => []);
            allDocuments = Array.isArray(documents) ? documents : [];
            const thread = threads.find(t => t.id === threadDocTargetId);
            const selectedDocIds = new Set(thread?.documentIds || []);
            const collections = new Set();
            for (const doc of allDocuments) {
                if (doc.collection) collections.add(doc.collection);
            }
            threadDocFilterCollection.innerHTML = '<option value="">Alla kollektioner</option>';
            for (const name of Array.from(collections).sort((a, b) => a.localeCompare(b, 'sv'))) {
                const option = document.createElement('option');
                option.value = name;
                option.textContent = name;
                threadDocFilterCollection.appendChild(option);
            }
            renderThreadDocList();
        } catch (error) {
            threadDocStatus.textContent = 'Kunde inte hämta dokument: ' + error.message;
            threadDocStatus.className = 'status err';
        }
    }

    function renderThreadDocList() {
        const term = threadDocSearch.value.trim().toLowerCase();
        const collection = threadDocFilterCollection.value;
        const thread = threads.find(t => t.id === threadDocTargetId);
        const selectedDocIds = new Set(thread?.documentIds || []);
        const documents = allDocuments.filter(doc => {
            if (term && !(doc.name || '').toLowerCase().includes(term)) return false;
            if (collection && doc.collection !== collection) return false;
            return true;
        });
        threadDocList.innerHTML = '';
        if (!documents.length) {
            const empty = document.createElement('div');
            empty.className = 'doc-item';
            empty.innerHTML = '<span>Inga dokument att visa.</span>';
            threadDocList.appendChild(empty);
        } else {
            for (const doc of documents) {
                threadDocList.appendChild(threadDocumentRow(doc, selectedDocIds));
            }
        }
    }

    function threadDocumentRow(doc, selectedDocIds) {
        const row = document.createElement('div');
        row.className = 'doc-item';

        const check = document.createElement('input');
        check.type = 'checkbox';
        check.className = 'doc-check';
        check.value = doc.id;
        check.checked = selectedDocIds.has(doc.id);
        if (check.checked) row.classList.add('selected');
        check.addEventListener('change', () => {
            if (check.checked) selectedDocIds.add(doc.id); else selectedDocIds.delete(doc.id);
            row.classList.toggle('selected', check.checked);
        });

        const main = document.createElement('div');
        main.className = 'doc-main';
        const nameEl = document.createElement('div');
        nameEl.className = 'doc-name';
        nameEl.textContent = doc.name;
        nameEl.title = doc.name;
        const metaEl = document.createElement('div');
        metaEl.className = 'meta';
        let meta = doc.chunks + ' textstycken';
        if (doc.collection) meta += ' · ' + doc.collection;
        if (doc.createdAt) meta += ' · ' + formatDate(doc.createdAt);
        metaEl.textContent = meta;
        main.appendChild(nameEl);
        main.appendChild(metaEl);
        if (doc.tags && doc.tags.length) {
            const tagList = document.createElement('div');
            tagList.className = 'tag-list';
            for (const tag of doc.tags) {
                const chip = document.createElement('span');
                chip.className = 'tag';
                chip.textContent = tag;
                tagList.appendChild(chip);
            }
            main.appendChild(tagList);
        }

        row.appendChild(check);
        row.appendChild(main);
        return row;
    }

    function saveThreadDocSelection() {
        const thread = threads.find(t => t.id === threadDocTargetId);
        if (!thread) return;
        const collections = Array.from(
            threadDocCollectionList.querySelectorAll('input[type=checkbox]:checked')).map(cb => cb.value);
        const documentIds = Array.from(
            threadDocList.querySelectorAll('input[type=checkbox]:checked')).map(cb => cb.value);
        thread.collections = collections;
        thread.documentIds = documentIds;
        saveThreads();
        renderThreadList();
        renderChatHeader();
        threadDocStatus.textContent = 'Sparat';
        threadDocStatus.className = 'status ok';
        setTimeout(() => {
            threadDocStatus.textContent = '';
            threadDocStatus.className = 'status';
        }, 2000);
    }

    function switchDocumentsTab(name) {
        documentsPage.querySelectorAll('.tab-btn').forEach(btn => {
            btn.classList.toggle('active', btn.dataset.tab === name);
        });
        documentsPage.querySelectorAll('.tab-panel').forEach(panel => {
            panel.classList.toggle('active', panel.dataset.panel === name);
        });
        if (name === 'collections') loadCollectionsTab();
        if (name === 'upload') loadUploadOptions();
    }

    async function loadUploadOptions() {
        const [collections] = await Promise.all([loadCollections(true), loadTags()]);
        const current = uploadCollection.value;
        uploadCollection.innerHTML = '';
        for (const name of collections) {
            const option = document.createElement('option');
            option.value = name;
            option.textContent = name;
            uploadCollection.appendChild(option);
        }
        const create = document.createElement('option');
        create.value = '__new__';
        create.textContent = '＋ Ny kollektion…';
        uploadCollection.appendChild(create);
        if (current && Array.from(uploadCollection.options).some(o => o.value === current)) {
            uploadCollection.value = current;
        }
        toggleNewCollectionField();
        renderTagSuggestions();
    }

    function toggleNewCollectionField() {
        newCollectionField.classList.toggle('hidden', uploadCollection.value !== '__new__');
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

    async function loadCollectionsTab() {
        setCollectionsStatus('Laddar...', '');
        try {
            const [collections, settingsRes] = await Promise.all([loadCollections(), fetch(API + '/settings')]);
            const settings = await settingsRes.json().catch(() => ({}));
            renderCollections(collections, settings.collections);
            setCollectionsStatus('', '');
        } catch (error) {
            setCollectionsStatus('Kunde inte hämta: ' + error.message, 'err');
        }
    }

    function setCollectionsStatus(text, cls) {
        collectionsStatus.textContent = text;
        collectionsStatus.className = 'status' + (cls ? ' ' + cls : '');
    }

    collectionsSave.addEventListener('click', async () => {
        collectionsSave.disabled = true;
        setCollectionsStatus('Sparar...', '');
        const collections = Array.from(
            collectionList.querySelectorAll('input[type=checkbox]:checked')).map(cb => cb.value);
        try {
            const res = await fetch(API + '/settings', {
                method: 'POST',
                headers: {'Content-Type': 'application/json', 'X-Requested-With': 'FakturummetsArkiv'},
                body: JSON.stringify({collections: collections})
            });
            const data = await res.json().catch(() => ({}));
            if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
            setCollectionsStatus('Sparat', 'ok');
        } catch (error) {
            setCollectionsStatus('Fel: ' + error.message, 'err');
        } finally {
            collectionsSave.disabled = false;
        }
    });

    documentsBtn.addEventListener('click', openDocuments);
    documentsClose.addEventListener('click', closeDocuments);
    docsSearch.addEventListener('input', renderDocuments);
    docsFilterCollection.addEventListener('change', () => { localStorage.setItem('docs_filter_collection', docsFilterCollection.value); renderDocuments(); });
    docsFilterTag.addEventListener('change', () => { localStorage.setItem('docs_filter_tag', docsFilterTag.value); renderDocuments(); });
    docsSort.addEventListener('change', () => { localStorage.setItem('docs_sort', docsSort.value); renderDocuments(); });
    documentsPage.querySelectorAll('.tab-btn').forEach(btn => {
        btn.addEventListener('click', () => switchDocumentsTab(btn.dataset.tab));
    });
    uploadCollection.addEventListener('change', () => {
        if (uploadCollection.value !== '__new__') {
            newCollectionName.value = '';
        }
        toggleNewCollectionField();
    });

    createCollectionBtn.addEventListener('click', async () => {
        const name = newCollectionInput.value.trim();
        if (!name) return;
        createCollectionBtn.disabled = true;
        setCollectionsStatus('Skapar...', '');
        try {
            const res = await fetch(DOC_API + '/collections', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({name})
            });
            const data = await res.json().catch(() => ({}));
            if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
            newCollectionInput.value = '';
            qdrantCollections = Array.isArray(data) ? data : null;
            setCollectionsStatus('Skapad', 'ok');
            renderCollections(await loadCollections(true), getSelectedCollections());
        } catch (error) {
            setCollectionsStatus('Fel: ' + error.message, 'err');
        } finally {
            createCollectionBtn.disabled = false;
        }
    });

    function getSelectedCollections() {
        return Array.from(collectionList.querySelectorAll('input[type=checkbox]:checked')).map(cb => cb.value);
    }

    function formatBytes(bytes) {
        if (!bytes) return '0 B';
        const units = ['B', 'kB', 'MB', 'GB'];
        let value = bytes;
        let i = 0;
        while (value >= 1024 && i < units.length - 1) {
            value /= 1024;
            i++;
        }
        return (i === 0 ? Math.round(value) : value.toFixed(1)) + ' ' + units[i];
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
        let collection = uploadCollection.value;
        if (collection === '__new__') {
            collection = newCollectionName.value.trim();
            if (!collection) {
                setDocumentsStatus('Ange namn på den nya kollektionen', 'err');
                return;
            }
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
                const added = await uploadFile(file, collection, tags, (loaded) => {
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
            newCollectionName.value = '';
            uploadTags.value = '';
            toggleNewCollectionField();
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
    });

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

    setPanelOpen(panel.open);
    if (!threads.length) {
        createThread();
    } else {
        renderAll();
    }
    loadModelStatus();
