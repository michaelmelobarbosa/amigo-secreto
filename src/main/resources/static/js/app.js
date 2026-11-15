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

const linksGuard = document.querySelector('[data-links-guard="true"]');
if (linksGuard) {
    let acknowledged = false;

    const acknowledge = (event) => {
        acknowledged = true;
        const target = event.currentTarget;
        if (target instanceof HTMLElement && target.hasAttribute('data-links-ack')) {
            target.textContent = 'Aviso desativado';
            target.setAttribute('disabled', 'disabled');
        }
    };

    linksGuard.querySelectorAll('[data-links-saved]').forEach((element) => {
        element.addEventListener('click', acknowledge);
    });

    linksGuard.querySelectorAll('form').forEach((form) => {
        form.addEventListener('submit', () => {
            acknowledged = true;
        });
    });

    window.addEventListener('beforeunload', (event) => {
        if (acknowledged) {
            return;
        }
        event.preventDefault();
        event.returnValue = '';
    });
}

document.addEventListener('submit', (event) => {
    const form = event.target;
    if (!(form instanceof HTMLFormElement)) {
        return;
    }

    const message = form.getAttribute('data-confirm');
    if (message && !window.confirm(message)) {
        event.preventDefault();
    }
});
