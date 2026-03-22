document.addEventListener('DOMContentLoaded', () => {
    const pasteBtn = document.getElementById('paste-btn');
    const urlInput = document.getElementById('video-url');
    const formatToggle = document.getElementById('format-toggle');
    const videoLabel = document.querySelector('.video-label');
    const mp3Label = document.querySelector('.mp3-label');
    const form = document.getElementById('download-form');

    const submitBtn = document.getElementById('submit-btn');
    const statusMessage = document.getElementById('status-message');
    const downloadLink = document.getElementById('download-link');
    const errorMessage = document.getElementById('error-message');
    const logoutBtn = document.getElementById('logout-btn');

    let currentMaxProgress = 0;
    let currentTaskId = null;

    // Fetch and display app version
    fetch('/api/version')
        .then(res => res.json())
        .then(data => {
            const versionEl = document.getElementById('app-version');
            if (versionEl && data.version) {
                versionEl.textContent = `v${data.version}`;
            }
        }).catch(err => console.error('Failed to fetch version', err));

    // Fetch and display logged-in user
    fetch('/api/user')
        .then(res => {
            if (res.ok) return res.json();
            throw new Error('Not logged in');
        })
        .then(data => {
            const userEl = document.getElementById('logged-in-user');
            if (userEl && data.username) {
                userEl.innerHTML = `Přihlášen jako: <strong style="color: var(--text-main);">${data.username}</strong>`;
            }
        }).catch(err => console.error('Failed to fetch user', err));

    // Logout
    logoutBtn?.addEventListener('click', async (e) => {
        e.preventDefault();
        await fetch('/api/logout', { method: 'POST' });
        window.location.href = '/login.html';
    });

    // Paste button functionality
    pasteBtn?.addEventListener('click', async () => {
        try {
            const text = await navigator.clipboard.readText();
            urlInput.value = text;
        } catch (err) {
            console.error('Clipboard API read failed (likely due to HTTP security context): ', err);
            alert("Chyba: Váš prohlížeč blokuje automatické čtení schránky bez HTTPS. Prosím, vložte odkaz ručně (podržením a vložením).");
        }
    });

    // Toggle switch functionality for styling
    formatToggle?.addEventListener('change', (e) => {
        if (e.target.checked) {
            videoLabel.classList.remove('glow-text');
            mp3Label.classList.add('glow-text');
        } else {
            videoLabel.classList.add('glow-text');
            mp3Label.classList.remove('glow-text');
        }
    });

    // Reset UI state
    const resetUI = () => {
        submitBtn.classList.remove('hidden');
        statusMessage.classList.add('hidden');
        downloadLink.classList.add('hidden');
        errorMessage.classList.add('hidden');

        // Reset progress bar and text
        const progressBarBg = document.getElementById('progress-bar-bg');
        if (progressBarBg) progressBarBg.style.width = '0%';
        const statusText = document.getElementById('status-text');
        if (statusText) statusText.textContent = 'Zpracovávám...';
        currentMaxProgress = 0; // Reset max progress on UI reset
    };

    // Form submission
    form?.addEventListener('submit', async (e) => {
        e.preventDefault();

        errorMessage.classList.add('hidden');

        const url = document.getElementById('video-url').value.trim();
        const format = document.getElementById('format-toggle').checked ? 'mp3' : 'video';

        if (!url) {
            errorMessage.textContent = 'Prosím, vložte URL adresu videa.';
            errorMessage.classList.remove('hidden');
            submitBtn.classList.remove('hidden');
            statusMessage.classList.add('hidden');
            return;
        }

        // Reset max progress for a new download
        currentMaxProgress = 0;

        // Show loading state
        submitBtn.classList.add('hidden');
        statusMessage.classList.remove('hidden');
        downloadLink.classList.add('hidden');
        errorMessage.classList.add('hidden');

        try {
            const response = await fetch('/api/download', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({ url, format })
            });

            if (response.status === 401) {
                window.location.href = '/login.html';
                return;
            }

            if (!response.ok) throw new Error('Network response was not ok');

            const data = await response.json();
            if (data.error) throw new Error(data.error);

            currentTaskId = data.task_id;

            // Poll for status
            pollStatus(currentTaskId);

        } catch (error) {
            console.error('Error starting download:', error);
            showError();
        }
    });

    const pollStatus = async (taskId) => {
        try {
            const response = await fetch(`/api/status/${taskId}`);

            if (response.status === 401) {
                window.location.href = '/login.html';
                return;
            }

            const data = await response.json();

            if (data.status === 'completed') {
                showDownload(data.download_url);
            } else if (data.status === 'error') {
                console.error("Backend error: ", data.error);
                if (data.error) {
                    errorMessage.textContent = data.error;
                } else {
                    errorMessage.textContent = 'Při stahování došlo k chybě.';
                }
                showError();
            } else {
                // Update progress if available
                if (data.progress !== undefined && data.progress !== null) {
                    if (data.progress > currentMaxProgress) {
                        currentMaxProgress = data.progress;
                    }
                    const statusText = document.getElementById('status-text');
                    const progressBarBg = document.getElementById('progress-bar-bg');
                    if (statusText) statusText.textContent = `Stahuji... ${currentMaxProgress}%`;
                    if (progressBarBg) progressBarBg.style.width = `${currentMaxProgress}%`;
                }

                // Still processing, poll again after 2 seconds
                // Use a faster poll interval (1 second) when we have a progress bar to make it feel smoother
                setTimeout(() => pollStatus(taskId), 1000);
            }
        } catch (error) {
            console.error('Error polling status:', error);
            showError();
        }
    };

    const showDownload = (downloadUrl) => {
        statusMessage.classList.add('hidden');
        downloadLink.classList.remove('hidden');
        downloadLink.href = downloadUrl;

        // Clear input field on success
        document.getElementById('video-url').value = '';

        downloadLink.addEventListener('click', () => {
            setTimeout(resetUI, 3000);
        }, { once: true });
    };

    const showError = () => {
        statusMessage.classList.add('hidden');
        errorMessage.classList.remove('hidden');

        // Reset progress bar just in case
        const progressBarBg = document.getElementById('progress-bar-bg');
        if (progressBarBg) progressBarBg.style.width = '0%';
        const statusText = document.getElementById('status-text');
        if (statusText) statusText.textContent = 'Zpracovávám...';

        setTimeout(() => {
            resetUI();
        }, 5000);
    };
});
