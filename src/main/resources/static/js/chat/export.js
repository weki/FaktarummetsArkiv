/* Export av tråd. */
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
