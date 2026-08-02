document.addEventListener('DOMContentLoaded', async () => {
    await window.i18n.init();

    const form = document.getElementById('login-form');
    const flash = document.getElementById('flash-message');

    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        flash.style.display = 'none';

        const username = document.getElementById('username').value;
        const password = document.getElementById('password').value;

        try {
            const response = await fetch('/api/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, password })
            });

            if (response.ok) {
                window.location.href = '/' + window.location.search;
                return;
            }

            window.i18n.setText(flash, 'login.errors.invalidCredentials');
            flash.style.display = 'block';
        } catch (_) {
            window.i18n.setText(flash, 'login.errors.connection');
            flash.style.display = 'block';
        }
    });
});
