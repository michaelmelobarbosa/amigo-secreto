(() => {
    try {
        const stored = localStorage.getItem('amigo-theme');
        const prefersDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
        const theme = stored === 'dark' || stored === 'light' ? stored : (prefersDark ? 'dark' : 'light');
        document.documentElement.setAttribute('data-theme', theme);
        const meta = document.getElementById('meta-theme-color');
        if (meta) {
            meta.setAttribute('content', theme === 'dark' ? '#0b0f14' : '#f4f6f8');
        }
    } catch (error) {
        // Segue o padrão do CSS caso o armazenamento local esteja indisponível.
    }
})();
