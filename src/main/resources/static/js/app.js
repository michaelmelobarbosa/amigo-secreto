document.addEventListener('click', async (event) => {
    const button = event.target.closest('[data-copy]');
    if (!button) {
        return;
    }

    const value = button.getAttribute('data-copy');
    if (!value) {
        return;
    }

    try {
        if (navigator.clipboard && window.isSecureContext) {
            await navigator.clipboard.writeText(value);
        } else {
            const input = document.createElement('textarea');
            input.value = value;
            input.setAttribute('readonly', '');
            input.style.position = 'fixed';
            input.style.opacity = '0';
            document.body.appendChild(input);
            input.select();
            document.execCommand('copy');
            document.body.removeChild(input);
        }

        const original = button.textContent;
        button.textContent = 'Copiado!';
        window.setTimeout(() => {
            button.textContent = original;
        }, 1500);
    } catch (error) {
        window.prompt('Copie o link manualmente:', value);
    }
});
