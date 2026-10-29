/* Gemensam sidopanel: varumärke, trådlista, navigering. Byggs av JS så att alla sidor delar samma markup.
   Körs efter threads-store.js. <nav id="sidebar" data-page="chat|documents|settings"> */

const SIDEBAR_ICONS = {
    plus: '<path d="M12 5v14M5 12h14"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/>',
    docs: '<path d="M6 3h8l4 4v14H6z"/><path d="M14 3v4h4M9 12h6M9 16h6"/>',
    settings: '<circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M19.1 4.9L17 7M7 17l-2.1 2.1"/>',
    gpu: '<rect x="4" y="7" width="16" height="10" rx="2"/><path d="M9 7V4M15 7V4M9 17v3M15 17v3"/>',
    reset: '<path d="M3 12a9 9 0 019-9 9 9 0 016.4 2.6L21 8M21 3v5h-5M21 12a9 9 0 01-9 9 9 9 0 01-6.4-2.6L3 16M3 21v-5h5"/>'
};

function sidebarSvg(name, size) {
    return '<svg width="' + size + '" height="' + size + '" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
        + 'stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + SIDEBAR_ICONS[name] + '</svg>';
}

function buildSidebar(nav) {
    const page = nav.dataset.page;
    const isChat = page === 'chat';
    const link = (href, id, label, icon, key) =>
        '<a href="' + href + '" id="' + id + '" class="nav-link' + (page === key ? ' active' : '') + '"'
        + (page === key ? ' aria-current="page"' : '') + '>' + sidebarSvg(icon, 18) + label + '</a>';
    nav.className = 'sidebar';
    nav.setAttribute('aria-label', 'Trådar');
    nav.innerHTML =
        '<div class="brand">'
        + '<svg width="32" height="32" viewBox="0 0 32 32" fill="none" aria-hidden="true"><rect x="1" y="1" width="30" height="30" rx="8" fill="#18233b" stroke="#35476b"/>'
        + '<path d="M9 11l7-3 7 3-7 3-7-3zM9 16l7 3 7-3M9 21l7 3 7-3" stroke="#38bdf8" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>'
        + '<div><h1>Fakturummets Arkiv</h1><div class="brand-sub">Lokalt dokumentarkiv</div></div></div>'
        + '<div class="sidebar-actions">'
        + (isChat
            ? '<button id="newThreadBtn" type="button" class="primary-btn">' + sidebarSvg('plus', 16) + 'Ny tråd</button>'
            : '<a id="newThreadBtn" href="/?new=1" class="primary-btn">' + sidebarSvg('plus', 16) + 'Ny tråd</a>')
        + '<div class="thread-search"><label for="threadSearch" class="sr-only">Sök trådar</label>' + sidebarSvg('search', 16)
        + '<input id="threadSearch" type="search" placeholder="Sök trådar" autocomplete="off"><kbd>Ctrl K</kbd></div></div>'
        + '<div id="threadList" class="thread-list"></div>'
        + '<div class="sidebar-foot">'
        + link('/documents.html', 'documentsLink', 'Dokument', 'docs', 'documents')
        + link('/settings.html', 'settingsLink', 'Inställningar', 'settings', 'settings')
        + '<div id="gpuSidebarCard" class="gpu-sidebar-card" style="display:none" role="button" tabindex="0" aria-label="Öppna GPU-panel"></div>'
        + '<div class="model-row"><div id="modelStatus" class="model-status" role="status"><span class="dot"></span>'
        + '<span id="modelStatusText">Kontrollerar modeller…</span></div>'
        + '<button id="resetOllamaBtn" type="button" class="reset-ollama-btn" title="Återställ Ollama: avbryt pågående svar och ladda om modellerna">'
        + sidebarSvg('reset', 14) + 'Återställ</button></div></div>';
}

/** Ritar trådlistan. opts: {activeId, onSelect(id)?, onDelete(id)?}. Utan onSelect blir raderna länkar till chatten. */
function renderThreadListInto(box, searchText, opts) {
    box.innerHTML = '';
    const term = (searchText || '').trim().toLowerCase();
    const visible = sortedThreads().filter(thread => !term
        || thread.messages.some(m => (m.text || '').toLowerCase().includes(term))
        || threadTitle(thread).toLowerCase().includes(term));
    if (!visible.length) {
        box.appendChild(el('div', 'thread-empty', threads.length ? 'Inga trådar matchar sökningen.' : 'Inga trådar än.'));
        return;
    }
    const groups = [
        {label: 'Idag', items: visible.filter(t => isToday(t.updatedAt))},
        {label: 'Tidigare', items: visible.filter(t => !isToday(t.updatedAt))}
    ];
    for (const group of groups) {
        if (!group.items.length) continue;
        box.appendChild(el('div', 'thread-group', group.label));
        const list = el('ul', 'thread-ul');
        for (const thread of group.items) {
            const item = el('li', 'thread-row');
            const btn = opts.onSelect ? el('button', 'thread-item') : el('a', 'thread-item');
            if (opts.onSelect) btn.type = 'button';
            else btn.href = '/?thread=' + encodeURIComponent(thread.id);
            if (thread.id === opts.activeId) btn.setAttribute('aria-current', 'true');
            btn.title = threadTitle(thread);
            btn.appendChild(el('span', 'thread-name', threadTitle(thread)));
            const time = formatThreadTime(thread.updatedAt);
            btn.appendChild(el('span', 'thread-meta', (time ? time + ' · ' : '') + scopeLabel(thread)));
            const stale = staleCollections(thread);
            if (stale.length) {
                const badge = el('span', 'thread-stale-badge', '⚠ saknas');
                badge.title = 'Valt källsystem finns inte längre i arkivet: ' + stale.join(', ')
                    + '. Sökningen i tråden ger inga träffar.';
                btn.appendChild(badge);
            }
            if (opts.onSelect) btn.addEventListener('click', () => opts.onSelect(thread.id));
            item.appendChild(btn);
            if (opts.onDelete) {
                const del = el('button', 'thread-delete');
                del.type = 'button';
                del.title = 'Ta bort tråd';
                del.setAttribute('aria-label', 'Ta bort tråden ' + threadTitle(thread));
                del.appendChild(icon('cross', 14));
                del.addEventListener('click', () => opts.onDelete(thread.id));
                item.appendChild(del);
            }
            list.appendChild(item);
        }
        box.appendChild(list);
    }
}

const sidebarNav = document.getElementById('sidebar');
buildSidebar(sidebarNav);
if (sidebarNav.dataset.page !== 'chat') {
    const box = document.getElementById('threadList');
    const search = document.getElementById('threadSearch');
    if (box && search) {
        const draw = () => renderThreadListInto(box, search.value, {activeId: null});
        search.addEventListener('input', draw);
        draw();
        if (typeof refreshThreadStaleMarkers === 'function') {
            refreshThreadStaleMarkers().then(draw).catch(() => {});
        }
        document.addEventListener('keydown', (event) => {
            if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
                event.preventDefault();
                search.focus();
                search.select();
            }
        });
    }
} else if (typeof refreshThreadStaleMarkers === 'function') {
    refreshThreadStaleMarkers().catch(() => {});
}

(function initGpuSidebar() {
    var card = document.getElementById('gpuSidebarCard');
    if (!card) return;
    card.addEventListener('click', function (e) {
        if (e.target.tagName === 'A') return;
        e.preventDefault();
        if (typeof window.GpuModal !== 'undefined') {
            window.GpuModal.open();
        } else {
            window.location.href = 'gpu.html';
        }
    });
    card.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            if (typeof window.GpuModal !== 'undefined') {
                window.GpuModal.open();
            } else {
                window.location.href = 'gpu.html';
            }
        }
    });
    if (typeof window.GpuPoller === 'undefined') {
        var s = document.createElement('script');
        s.src = 'js/shared/gpu-poller.js';
        s.onload = initGpuSidebar;
        document.head.appendChild(s);
        return;
    }
    function render(data) {
        if (!data) return;
        var gpu = data.gpu || {};
        if (gpu.reason === 'disabled') {
            card.style.display = 'none';
            return;
        }
        card.style.display = 'block';
        var util = typeof gpu.utilPct === 'number' ? gpu.utilPct : null;
        var mu = typeof gpu.memUsedMiB === 'number' ? gpu.memUsedMiB : null;
        var mt = typeof gpu.memTotalMiB === 'number' ? gpu.memTotalMiB : null;
        var muGb = mu != null ? (mu / 1024).toFixed(1) : '-';
        var mtGb = mt != null ? (mt / 1024).toFixed(1) : '-';
        var warn = (data.warnings && data.warnings.length > 0) || data._stale;
        card.innerHTML = '<div class="gpu-sidebar-link">'
            + '<div class="gpu-sidebar-row"><span>GPU</span><span class="gpu-sidebar-val">' + (util != null ? util + '%' : '-') + '</span></div>'
            + '<div class="gpu-sidebar-bar"><span style="width:' + (util != null ? Math.max(0, Math.min(100, util)) : 0) + '%"></span></div>'
            + '<div class="gpu-sidebar-row"><span>VRAM</span><span class="gpu-sidebar-val">' + muGb + ' / ' + mtGb + ' GB</span></div>'
            + '<div class="gpu-sidebar-bar"><span style="width:' + (mu != null && mt != null && mt > 0 ? Math.max(0, Math.min(100, (mu/mt)*100)) : 0) + '%"></span></div>'
            + '</div>'
            + '<span class="gpu-sidebar-dot ' + (warn ? 'warn' : '') + '"></span>';
        card.classList.toggle('gpu-sidebar-stale', !!data._stale);
    }
    window.GpuPoller.subscribe(render);
    window.addEventListener('pagehide', function () {
        try { window.GpuPoller.unsubscribe(render); } catch (e) { }
    });
})();
