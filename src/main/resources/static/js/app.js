document.documentElement.classList.add('js-enabled');

/* ---------- Alternância de tema Claro / Escuro ---------- */
const THEME_STORAGE_KEY = 'amigo-theme';
const themeMeta = document.getElementById('meta-theme-color');
const themeMedia = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;

const currentEffectiveTheme = () => {
    const explicit = document.documentElement.getAttribute('data-theme');
    if (explicit === 'dark' || explicit === 'light') {
        return explicit;
    }
    return themeMedia && themeMedia.matches ? 'dark' : 'light';
};

const applyTheme = (theme, persist) => {
    document.documentElement.setAttribute('data-theme', theme);
    if (themeMeta) {
        themeMeta.setAttribute('content', theme === 'dark' ? '#0b0f14' : '#f4f6f8');
    }
    document.querySelectorAll('[data-theme-toggle]').forEach((btn) => {
        const isDark = theme === 'dark';
        btn.setAttribute('aria-pressed', String(isDark));
        btn.setAttribute('aria-label', isDark ? 'Mudar para tema claro' : 'Mudar para tema escuro');
        btn.setAttribute('title', isDark ? 'Mudar para tema claro' : 'Mudar para tema escuro');
    });
    if (persist) {
        try {
            localStorage.setItem(THEME_STORAGE_KEY, theme);
        } catch (error) {
            // Ignora falha de gravação em modo restrito.
        }
    }
};

applyTheme(currentEffectiveTheme(), false);

document.addEventListener('click', (event) => {
    const toggle = event.target.closest('[data-theme-toggle]');
    if (!toggle) {
        return;
    }
    const nextTheme = currentEffectiveTheme() === 'dark' ? 'light' : 'dark';
    applyTheme(nextTheme, true);
});

if (themeMedia && typeof themeMedia.addEventListener === 'function') {
    themeMedia.addEventListener('change', (event) => {
        try {
            if (!localStorage.getItem(THEME_STORAGE_KEY)) {
                applyTheme(event.matches ? 'dark' : 'light', false);
            }
        } catch (error) {
            applyTheme(event.matches ? 'dark' : 'light', false);
        }
    });
}

/* ---------- Cópia de links + Toast acessível ---------- */
const copyToast = document.getElementById('copy-toast');
let toastTimer = null;

const showCopyToast = (message) => {
    if (!copyToast) {
        return;
    }
    const textNode = copyToast.querySelector('[data-toast-text]');
    if (textNode && message) {
        textNode.textContent = message;
    }
    copyToast.classList.add('is-visible');
    if (toastTimer) {
        window.clearTimeout(toastTimer);
    }
    toastTimer = window.setTimeout(() => {
        copyToast.classList.remove('is-visible');
    }, 2200);
};

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
        showCopyToast('Link copiado para a área de transferência');

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
        }, 1600);
    } catch (error) {
        window.prompt('Copie o link manualmente:', value);
    }
});

/* ---------- Cortina de revelação na página do participante ---------- */
const revealCard = document.querySelector('[data-reveal-card]');
if (revealCard) {
    const openBtn = revealCard.querySelector('[data-reveal-open]');
    const toggleBtn = document.querySelector('[data-reveal-toggle]');
    const toggleLabel = document.querySelector('[data-reveal-toggle-label]');

    const setRevealed = (revealed) => {
        revealCard.classList.toggle('is-revealed', revealed);
        if (toggleBtn) {
            toggleBtn.hidden = !revealed;
        }
        if (toggleLabel) {
            toggleLabel.textContent = revealed ? 'Ocultar nome' : 'Mostrar nome';
        }
    };

    if (openBtn) {
        openBtn.addEventListener('click', () => setRevealed(true));
    }
    if (toggleBtn) {
        toggleBtn.addEventListener('click', () => {
            const currentlyRevealed = revealCard.classList.contains('is-revealed');
            setRevealed(!currentlyRevealed);
        });
    }
}

/* ---------- Contador de caracteres opcional em textareas ---------- */
document.querySelectorAll('textarea[data-char-count]').forEach((textarea) => {
    const counter = document.querySelector(`[data-char-for="${textarea.id}"]`);
    const max = textarea.getAttribute('maxlength');
    if (!counter || !max) {
        return;
    }

    const updateCount = () => {
        counter.textContent = `${textarea.value.length}/${max}`;
    };

    textarea.addEventListener('input', updateCount);
    updateCount();
});

/* ---------- Utilitário de hash não reversível para chaves por rota ---------- */
const hashPathname = (value) => {
    let hash = 0x811c9dc5;
    for (let i = 0; i < value.length; i += 1) {
        hash ^= value.charCodeAt(i);
        hash = Math.imul(hash, 0x01000193);
    }
    return (hash >>> 0).toString(16);
};

/* ---------- Persistência de recolhimento da Lista do grupo (<details>) ---------- */
const participantsCard = document.querySelector('[data-collapsible-section="participants"]');
if (participantsCard instanceof HTMLDetailsElement) {
    const participantsStorageKey = `amigo-participants-collapsed:${hashPathname(window.location.pathname)}`;
    try {
        if (sessionStorage.getItem(participantsStorageKey) === '1') {
            participantsCard.open = false;
        }
    } catch (error) {
        // Ignora falha de leitura em modo restrito.
    }

    participantsCard.addEventListener('toggle', () => {
        try {
            sessionStorage.setItem(participantsStorageKey, participantsCard.open ? '0' : '1');
        } catch (error) {
            // Ignora falha de gravação em modo restrito.
        }
    });
}

/* ---------- Guarda persistente de credenciais (organizador e participante) ---------- */

const accessVault = document.querySelector('[data-access-vault]');
const leaveDialog = document.querySelector('#leave-links-dialog');
const vaultStorageKey = accessVault ? `amigo-vault:${hashPathname(window.location.pathname)}` : null;
let linksSaved = false;
let isSubmittingForm = false;

if (vaultStorageKey) {
    try {
        linksSaved = localStorage.getItem(vaultStorageKey) === '1';
    } catch (error) {
        linksSaved = false;
    }
}

const syncVaultUi = (collapseIfAllowed) => {
    if (!accessVault) {
        return;
    }

    const vaultBadge = accessVault.querySelector('[data-vault-badge]');
    const stepVault = document.querySelector('[data-step-vault]');
    const stepVaultMeta = document.querySelector('[data-step-vault-meta]');
    const ackButtons = accessVault.querySelectorAll('[data-links-ack]');

    if (linksSaved) {
        accessVault.classList.remove('is-highlighted');
        if (vaultBadge) {
            vaultBadge.textContent = 'Salvo neste navegador';
            vaultBadge.classList.remove('badge-warning');
            vaultBadge.classList.add('badge-success');
        }
        if (stepVault) {
            stepVault.classList.remove('is-current');
            stepVault.classList.add('is-done');
            stepVault.removeAttribute('aria-current');
        }
        if (stepVaultMeta) {
            stepVaultMeta.textContent = 'Salvo neste navegador';
        }
        ackButtons.forEach((btn) => {
            btn.hidden = true;
        });
        if (collapseIfAllowed && accessVault instanceof HTMLDetailsElement
                && accessVault.getAttribute('data-force-open') !== 'true') {
            accessVault.open = false;
        }
    } else {
        if (stepVault) {
            stepVault.classList.add('is-current');
            stepVault.classList.remove('is-done');
            stepVault.setAttribute('aria-current', 'step');
        }
        if (stepVaultMeta) {
            stepVaultMeta.textContent = 'Salve seus links';
        }
    }
};

const persistLinksSaved = (collapseNow) => {
    linksSaved = true;
    if (vaultStorageKey) {
        try {
            localStorage.setItem(vaultStorageKey, '1');
        } catch (error) {
            // Ignora falha de gravação em modo restrito.
        }
    }
    syncVaultUi(false);
    if (collapseNow && accessVault instanceof HTMLDetailsElement) {
        accessVault.open = false;
    }
};

if (accessVault) {
    syncVaultUi(true);

    accessVault.querySelectorAll('[data-links-saved]').forEach((element) => {
        element.addEventListener('click', (event) => {
            const target = event.currentTarget;
            const shouldCollapse = target instanceof HTMLElement && target.hasAttribute('data-links-ack');
            persistLinksSaved(shouldCollapse);
        });
    });

    accessVault.querySelectorAll('form').forEach((form) => {
        form.addEventListener('submit', () => {
            persistLinksSaved(false);
        });
    });

    window.addEventListener('beforeunload', (event) => {
        if (linksSaved || isSubmittingForm) {
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
            if (window.confirm('Se você ainda não salvou seu link exclusivo, vai perder o acesso ao sair. Continuar?')) {
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

/* ---------- Rolagem e foco automático no primeiro erro de validação ---------- */
const firstFieldError = document.querySelector('.field-error');
if (firstFieldError) {
    let parentDetails = firstFieldError.closest('details');
    while (parentDetails) {
        parentDetails.open = true;
        parentDetails = parentDetails.parentElement ? parentDetails.parentElement.closest('details') : null;
    }

    const fieldContainer = firstFieldError.closest('.field, fieldset');
    const invalidControl = fieldContainer
        ? fieldContainer.querySelector('[aria-invalid="true"], input:not([type="hidden"]), textarea, select')
        : null;
    const scrollTarget = invalidControl || firstFieldError;
    const prefersReducedMotion = window.matchMedia
        && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    window.requestAnimationFrame(() => {
        scrollTarget.scrollIntoView({
            behavior: prefersReducedMotion ? 'auto' : 'smooth',
            block: 'center',
        });
        if (invalidControl instanceof HTMLElement) {
            if (invalidControl.tagName === 'FIELDSET') {
                const firstInput = invalidControl.querySelector('input, select, textarea');
                if (firstInput instanceof HTMLElement) {
                    firstInput.focus({ preventScroll: true });
                }
            } else {
                invalidControl.focus({ preventScroll: true });
            }
        }
    });
}

/* ---------- Modal <dialog> unificado para formulários com data-confirm ---------- */
const confirmDialog = document.getElementById('confirm-action-dialog');
let pendingForm = null;

if (confirmDialog) {
    const cancelBtn = confirmDialog.querySelector('[data-confirm-cancel]');
    const acceptBtn = confirmDialog.querySelector('[data-confirm-accept]');

    if (cancelBtn) {
        cancelBtn.addEventListener('click', () => {
            pendingForm = null;
            confirmDialog.close();
        });
    }

    if (acceptBtn) {
        acceptBtn.addEventListener('click', () => {
            const formToSubmit = pendingForm;
            pendingForm = null;
            confirmDialog.close();
            if (formToSubmit) {
                formToSubmit.dataset.confirmed = 'true';
                if (typeof formToSubmit.requestSubmit === 'function') {
                    formToSubmit.requestSubmit();
                } else {
                    formToSubmit.submit();
                }
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
    if (message && form.dataset.confirmed !== 'true') {
        if (confirmDialog && typeof confirmDialog.showModal === 'function') {
            event.preventDefault();
            pendingForm = form;

            const heading = confirmDialog.querySelector('[data-confirm-heading]');
            const body = confirmDialog.querySelector('[data-confirm-body]');
            const acceptBtn = confirmDialog.querySelector('[data-confirm-accept]');

            if (heading) {
                heading.textContent = form.getAttribute('data-confirm-title') || 'Confirmar ação';
            }
            if (body) {
                body.textContent = message;
            }
            if (acceptBtn) {
                acceptBtn.textContent = form.getAttribute('data-confirm-cta') || 'Confirmar';
                acceptBtn.classList.toggle('danger', form.getAttribute('data-confirm-variant') === 'danger');
            }

            if (!confirmDialog.open) {
                confirmDialog.showModal();
            }
            return;
        }

        if (!window.confirm(message)) {
            event.preventDefault();
            return;
        }
    }

    isSubmittingForm = true;
    delete form.dataset.confirmed;
    const submitter = form.querySelector('button[type="submit"]');
    if (submitter && !submitter.disabled) {
        window.requestAnimationFrame(() => {
            submitter.disabled = true;
        });
    }
});
