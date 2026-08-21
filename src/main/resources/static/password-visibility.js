(() => {
    const updateButton = (button, visible) => {
        const key = visible ? 'password.visibility.hide' : 'password.visibility.show';
        const icon = button.querySelector('i');

        button.dataset.i18nTitle = key;
        button.dataset.i18nAriaLabel = key;
        button.title = window.i18n.t(key);
        button.setAttribute('aria-label', window.i18n.t(key));
        button.setAttribute('aria-pressed', String(visible));
        icon?.classList.toggle('fa-eye', !visible);
        icon?.classList.toggle('fa-eye-slash', visible);
    };

    const setVisible = (button, input, visible) => {
        input.type = visible ? 'text' : 'password';
        updateButton(button, visible);
    };

    const buttonsIn = (root) => root.querySelectorAll('[data-password-target]');

    const init = (root = document) => {
        buttonsIn(root).forEach((button) => {
            const input = document.getElementById(button.dataset.passwordTarget);
            if (!input || button.dataset.passwordVisibilityReady) return;

            button.dataset.passwordVisibilityReady = 'true';
            button.addEventListener('click', () => {
                setVisible(button, input, input.type === 'password');
                input.focus();
            });
        });
    };

    const reset = (root = document) => {
        buttonsIn(root).forEach((button) => {
            const input = document.getElementById(button.dataset.passwordTarget);
            if (input) setVisible(button, input, false);
        });
    };

    const setDisabled = (root, disabled) => {
        buttonsIn(root).forEach((button) => {
            const input = document.getElementById(button.dataset.passwordTarget);
            button.disabled = disabled;
            if (input) input.disabled = disabled;
        });
    };

    document.addEventListener('DOMContentLoaded', () => init());

    window.passwordVisibility = { init, reset, setDisabled };
})();
