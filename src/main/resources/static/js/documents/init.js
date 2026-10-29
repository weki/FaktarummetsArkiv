/* Flikar, filter och start av dokumentsidan. */
const documentsPage = document.getElementById('documentsPage');

function switchDocumentsTab(name) {
    documentsPage.querySelectorAll('.tab-btn').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.tab === name);
    });
    documentsPage.querySelectorAll('.tab-panel').forEach(panel => {
        panel.classList.toggle('active', panel.dataset.panel === name);
    });
    if (name === 'upload') loadUploadOptions();
}

docsSearch.addEventListener('input', renderDocuments);
docsFilterTag.addEventListener('change', () => { localStorage.setItem('docs_filter_tag', docsFilterTag.value); renderDocuments(); });
docsSort.addEventListener('change', () => { localStorage.setItem('docs_sort', docsSort.value); renderDocuments(); });
documentsPage.querySelectorAll('.tab-btn').forEach(btn => {
    btn.addEventListener('click', () => switchDocumentsTab(btn.dataset.tab));
});

setDocumentsStatus('Laddar...', '');
loadDocuments();
loadUploadOptions();
