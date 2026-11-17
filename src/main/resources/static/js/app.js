document.documentElement.classList.add('js-enabled');

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

        const status = document.querySelector('[data-copy-status]');
        if (status) {
            status.textContent = '';
            window.requestAnimationFrame(() => {
                status.textContent = 'Link copiado.';
            });
        }

        const originalText = button.textContent;
        const originalLabel = button.getAttribute('aria-label');
        const originalTitle = button.getAttribute('title');
        const usesIcon = button.querySelector('.icon-check') !== null;

        if (usesIcon) {
            button.classList.add('is-copied');
            button.setAttribute('aria-label', 'Copiado!');
            if (originalTitle !== null) {
                button.setAttribute('title', 'Copiado!');
            }
        } else {
            button.textContent = 'Copiado!';
        }

        window.setTimeout(() => {
            if (usesIcon) {
                button.classList.remove('is-copied');
                if (originalLabel === null) {
                    button.removeAttribute('aria-label');
                } else {
                    button.setAttribute('aria-label', originalLabel);
                }
                if (originalTitle !== null) {
                    button.setAttribute('title', originalTitle);
                }
            } else {
                button.textContent = originalText;
            }
        }, 1500);
    } catch (error) {
        window.prompt('Copie o link manualmente:', value);
    }
});

document.querySelectorAll('[data-date-trigger-for]').forEach((trigger) => {
    const target = document.getElementById(trigger.getAttribute('data-date-trigger-for'));
    if (!target) {
        return;
    }

    const syncFromTarget = () => {
        trigger.value = target.value;
    };

    trigger.addEventListener('click', () => {
        if (typeof trigger.showPicker === 'function') {
            try {
                trigger.showPicker();
            } catch (error) {
                // O navegador pode já ter aberto o picker pelo clique nativo.
            }
        }
    });

    trigger.addEventListener('change', () => {
        target.value = trigger.value;
        target.dispatchEvent(new Event('input', { bubbles: true }));
        target.dispatchEvent(new Event('change', { bubbles: true }));
    });

    target.addEventListener('input', syncFromTarget);
    target.addEventListener('change', syncFromTarget);
    syncFromTarget();
});

const participantsList = document.querySelector('#participants-list');
const participantsToggle = document.querySelector('[data-participants-toggle]');
if (participantsList && participantsToggle) {
    const media = window.matchMedia('(max-width: 48rem)');
    const collapsedLabel = participantsToggle.querySelector('.when-collapsed');
    const expandedLabel = participantsToggle.querySelector('.when-expanded');

    const setCollapsed = (collapsed) => {
        participantsList.classList.toggle('is-collapsed', collapsed);
        participantsToggle.setAttribute('aria-expanded', String(!collapsed));
        if (collapsedLabel) {
            collapsedLabel.hidden = !collapsed;
        }
        if (expandedLabel) {
            expandedLabel.hidden = collapsed;
        }
    };

    const applyViewport = () => setCollapsed(media.matches);

    participantsToggle.addEventListener('click', () => {
        setCollapsed(!participantsList.classList.contains('is-collapsed'));
    });

    if (typeof media.addEventListener === 'function') {
        media.addEventListener('change', applyViewport);
    } else if (typeof media.addListener === 'function') {
        media.addListener(applyViewport);
    }

    applyViewport();
}

const organizerAccess = document.querySelector('#organizer-access');
const leaveDialog = document.querySelector('#leave-links-dialog');
let linksSaved = false;

const markLinksSaved = (event) => {
    linksSaved = true;
    const target = event.currentTarget;
    if (target instanceof HTMLElement && target.hasAttribute('data-links-ack')) {
        target.textContent = 'Aviso desativado';
        target.setAttribute('disabled', 'disabled');
    }
};

if (organizerAccess) {
    organizerAccess.querySelectorAll('[data-links-saved]').forEach((element) => {
        element.addEventListener('click', markLinksSaved);
    });

    organizerAccess.querySelectorAll('form').forEach((form) => {
        form.addEventListener('submit', () => {
            linksSaved = true;
        });
    });
}

const linksGuard = document.querySelector('[data-links-guard="true"]');
if (linksGuard) {
    window.addEventListener('beforeunload', (event) => {
        if (linksSaved) {
            return;
        }
        event.preventDefault();
        event.returnValue = '';
    });
}

if (leaveDialog) {
    let pendingHomeUrl = null;

    const openLeaveDialog = (homeUrl) => {
        pendingHomeUrl = homeUrl;
        if (typeof leaveDialog.showModal !== 'function') {
            if (window.confirm('Se você ainda não salvou os links, vai perdê-los ao sair. Continuar?')) {
                linksSaved = true;
                window.location.href = homeUrl;
            }
            return;
        }
        if (!leaveDialog.open) {
            leaveDialog.showModal();
        }
    };

    document.querySelectorAll('[data-nav-home]').forEach((link) => {
        link.addEventListener('click', (event) => {
            if (linksSaved || event.defaultPrevented || event.button !== 0
                    || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
                return;
            }
            event.preventDefault();
            openLeaveDialog(link.href);
        });
    });

    const cancelButton = leaveDialog.querySelector('[data-dialog-cancel]');
    if (cancelButton) {
        cancelButton.addEventListener('click', () => leaveDialog.close());
    }

    const confirmButton = leaveDialog.querySelector('[data-dialog-confirm]');
    if (confirmButton) {
        confirmButton.addEventListener('click', () => {
            const destination = pendingHomeUrl;
            linksSaved = true;
            leaveDialog.close();
            if (destination) {
                window.location.href = destination;
            }
        });
    }
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
