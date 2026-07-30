document.addEventListener('DOMContentLoaded', () => {
    const urlInput = document.getElementById('video-url');
    const editFilenameBtn = document.getElementById('edit-filename-btn');
    const filenameGroup = document.getElementById('filename-group');
    const customFilenameInput = document.getElementById('custom-filename');
    const filenameLoader = document.getElementById('filename-loader');
    const clearFilenameBtn = document.getElementById('clear-filename-btn');
    const formatToggle = document.getElementById('format-toggle');
    const audioOptions = document.getElementById('audio-options');
    const audioConversionInputs = document.querySelectorAll('input[name="audio-conversion"]');
    const videoLabel = document.querySelector('.video-label');
    const mp3Label = document.querySelector('.mp3-label');
    const form = document.getElementById('download-form');

    const submitBtn = document.getElementById('submit-btn');
    const statusMessage = document.getElementById('status-message');
    const errorMessage = document.getElementById('error-message');
    const cancelDownloadBtn = document.getElementById('cancel-download-btn');
    const logoutBtn = document.getElementById('logout-btn');

    const setChangePasswordDisabled = (disabled) => {
        const btn = document.getElementById('change-password-btn');
        if (!btn) return;

        btn.classList.toggle('disabled', disabled);
        btn.setAttribute('aria-disabled', disabled ? 'true' : 'false');
    };

    // Toggle clear button visibility
    const toggleClearBtn = () => {
        if (customFilenameInput && customFilenameInput.value.length > 0) {
            clearFilenameBtn?.classList.remove('hidden');
        } else {
            clearFilenameBtn?.classList.add('hidden');
        }
    };

    // Prevent restricted characters in filename
    customFilenameInput?.addEventListener('input', (e) => {
        e.target.value = e.target.value.replace(/[<>:"/\\|?*\x00-\x1F]/g, '');
        toggleClearBtn();
    });

    // Clear filename button
    clearFilenameBtn?.addEventListener('click', () => {
        if (clearFilenameBtn.disabled) return;

        if (customFilenameInput) {
            customFilenameInput.value = '';
            toggleClearBtn();
            customFilenameInput.focus();
        }
    });

    let currentMaxProgress = 0;
    let currentTaskId = null;
    let currentAudioConversion = 'fastest';

    const setAudioOptionsDisabled = (disabled) => {
        audioConversionInputs.forEach(input => {
            input.disabled = disabled;
        });
        audioOptions?.classList.toggle('disabled', disabled);
    };

    const updateAudioOptionsVisibility = () => {
        if (formatToggle?.checked) {
            audioOptions?.classList.remove('hidden');
        } else {
            audioOptions?.classList.add('hidden');
        }
    };

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
                userEl.innerHTML = `Přihlášen(a) jako: <strong style="color: var(--text-main);">${data.username}</strong>`;
            }
        }).catch(err => console.error('Failed to fetch user', err));

    // Logout
    logoutBtn?.addEventListener('click', async (e) => {
        e.preventDefault();
        await fetch('/api/logout', { method: 'POST' });
        window.location.href = '/login.html';
    });

    // Custom filename functionality
    editFilenameBtn?.addEventListener('click', async () => {
        if (!urlInput.checkValidity()) {
            urlInput.reportValidity();
            return;
        }

        const url = urlInput.value.trim();
        if (!url) {
            alert('Nejprve zadejte odkaz na video.');
            return;
        }

        filenameGroup.classList.toggle('hidden');
        if (!filenameGroup.classList.contains('hidden')) {
            filenameLoader.classList.remove('hidden');
            customFilenameInput.value = '';
            toggleClearBtn();
            try {
                const response = await fetch('/api/title', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ url })
                });

                if (response.status === 401) {
                    window.location.href = '/login.html';
                    return;
                }

                if (!response.ok) throw new Error('Could not retrieve title');

                const data = await response.json();
                if (data.title) {
                    customFilenameInput.value = data.title.replace(/[<>:"/\\|?*\x00-\x1F]/g, '');
                    toggleClearBtn();
                }
            } catch (err) {
                console.error('Failed to get title:', err);
                customFilenameInput.value = 'Nepodařilo se načíst název';
                toggleClearBtn();
            } finally {
                filenameLoader.classList.add('hidden');
            }
        } else {
            customFilenameInput.value = '';
            toggleClearBtn();
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
        updateAudioOptionsVisibility();
    });
    updateAudioOptionsVisibility();

    // Reset UI state
    const resetUI = () => {
        submitBtn.classList.remove('hidden');
        statusMessage.classList.add('hidden');
        errorMessage.classList.add('hidden');
        cancelDownloadBtn?.classList.add('hidden');
        if (cancelDownloadBtn) cancelDownloadBtn.disabled = false;

        const formatToggleEl = document.getElementById('format-toggle');
        if (formatToggleEl) formatToggleEl.disabled = false;

        const settingsBtn = document.getElementById('edit-filename-btn');
        if (settingsBtn) settingsBtn.disabled = false;

        const toggleContainer = document.querySelector('.toggle-container');
        if (toggleContainer) toggleContainer.classList.remove('disabled');
        setAudioOptionsDisabled(false);

        filenameGroup?.classList.add('hidden');
        if (customFilenameInput) {
            customFilenameInput.value = '';
            toggleClearBtn();
            customFilenameInput.disabled = false;
        }
        if (clearFilenameBtn) clearFilenameBtn.disabled = false;
        if (urlInput) urlInput.disabled = false;
        setChangePasswordDisabled(false);

        // Reset progress bar and text
        const progressBarBg = document.getElementById('progress-bar-bg');
        if (progressBarBg) progressBarBg.style.width = '0%';
        const statusText = document.getElementById('status-text');
        if (statusText) statusText.textContent = 'Zpracovávám...';
        currentMaxProgress = 0; // Reset max progress on UI reset
        currentTaskId = null;
    };

    // Form submission
    form?.addEventListener('submit', async (e) => {
        e.preventDefault();

        errorMessage.classList.add('hidden');

        const url = document.getElementById('video-url').value.trim();
        const format = document.getElementById('format-toggle').checked ? 'mp3' : 'video';
        const audioConversion = document.querySelector('input[name="audio-conversion"]:checked')?.value || 'fastest';

        let customFilename = null;
        if (!filenameGroup?.classList.contains('hidden') && customFilenameInput) {
            customFilename = customFilenameInput.value.trim();
        }

        if (!url) {
            errorMessage.textContent = 'Prosím, vložte URL adresu videa.';
            errorMessage.classList.remove('hidden');
            submitBtn.classList.remove('hidden');
            statusMessage.classList.add('hidden');
            return;
        }

        // Reset max progress for a new download
        currentMaxProgress = 0;
        currentAudioConversion = audioConversion;

        // Show loading state
        submitBtn.classList.add('hidden');
        statusMessage.classList.remove('hidden');
        errorMessage.classList.add('hidden');
        cancelDownloadBtn?.classList.remove('hidden');
        if (cancelDownloadBtn) cancelDownloadBtn.disabled = false;

        const formatToggleEl = document.getElementById('format-toggle');
        if (formatToggleEl) formatToggleEl.disabled = true;

        const settingsBtn = document.getElementById('edit-filename-btn');
        if (settingsBtn) settingsBtn.disabled = true;

        const toggleContainer = document.querySelector('.toggle-container');
        if (toggleContainer) toggleContainer.classList.add('disabled');
        setAudioOptionsDisabled(true);

        if (customFilenameInput) customFilenameInput.disabled = true;
        if (clearFilenameBtn) clearFilenameBtn.disabled = true;
        if (urlInput) urlInput.disabled = true;
        setChangePasswordDisabled(true);

        try {
            const bodyData = { url, format };
            if (format === 'mp3') {
                bodyData.audioConversion = audioConversion;
            }
            if (customFilename) {
                bodyData.filename = customFilename;
            }

            const response = await fetch('/api/download', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(bodyData)
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
        if (taskId !== currentTaskId) return;

        try {
            const response = await fetch(`/api/status/${taskId}`);

            if (response.status === 401) {
                window.location.href = '/login.html';
                return;
            }

            const data = await response.json();
            if (taskId !== currentTaskId) return;

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
            } else if (data.status === 'cancelled') {
                showCancelled();
            } else {
                // Update progress if available
                if (data.progress !== undefined && data.progress !== null) {
                    if (data.progress > currentMaxProgress) {
                        currentMaxProgress = data.progress;
                    }
                    const statusText = document.getElementById('status-text');
                    const progressBarBg = document.getElementById('progress-bar-bg');

                    if (statusText) {
                        if (currentMaxProgress >= 100) {
                            const isMp3 = document.getElementById('format-toggle').checked;
                            statusText.textContent = isMp3 && currentAudioConversion === 'mp3' ? 'Konvertuji na zvuk ...' : 'Dokončuji ...';
                        } else {
                            statusText.textContent = `Stahuji... ${currentMaxProgress}%`;
                        }
                    }
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
        // Change status text to indicate completion
        const statusText = document.getElementById('status-text');
        const progressBarBg = document.getElementById('progress-bar-bg');

        if (statusText) statusText.textContent = 'Hotovo! Ukládám...';
        if (progressBarBg) progressBarBg.style.width = '100%';
        cancelDownloadBtn?.classList.add('hidden');

        // Clear input field on success
        document.getElementById('video-url').value = '';

        // Trigger automatic download
        const a = document.createElement('a');
        a.href = downloadUrl;
        a.download = '';
        a.style.display = 'none';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);

        // Reset UI after a few seconds
        setTimeout(resetUI, 1000);
    };

    const showCancelled = () => {
        const statusText = document.getElementById('status-text');
        const progressBarBg = document.getElementById('progress-bar-bg');

        if (statusText) statusText.textContent = 'Stahování zrušeno';
        if (progressBarBg) progressBarBg.style.width = '0%';
        cancelDownloadBtn?.classList.add('hidden');

        setTimeout(resetUI, 1000);
    };

    const showError = () => {
        statusMessage.classList.add('hidden');
        errorMessage.classList.remove('hidden');
        cancelDownloadBtn?.classList.add('hidden');

        // Reset progress bar just in case
        const progressBarBg = document.getElementById('progress-bar-bg');
        if (progressBarBg) progressBarBg.style.width = '0%';
        const statusText = document.getElementById('status-text');
        if (statusText) statusText.textContent = 'Zpracovávám...';

        setTimeout(() => {
            resetUI();
        }, 5000);
    };

    cancelDownloadBtn?.addEventListener('click', async () => {
        if (!currentTaskId || cancelDownloadBtn.disabled) return;

        cancelDownloadBtn.disabled = true;
        const statusText = document.getElementById('status-text');
        if (statusText) statusText.textContent = 'Ruším stahování...';

        try {
            const response = await fetch(`/api/cancel/${currentTaskId}`, { method: 'POST' });

            if (response.status === 401) {
                window.location.href = '/login.html';
                return;
            }

            if (!response.ok) throw new Error('Failed to cancel download');

            showCancelled();
        } catch (error) {
            console.error('Error cancelling download:', error);
            errorMessage.textContent = 'Stahování se nepodařilo zrušit.';
            showError();
        }
    });

    // Handle base64 encoded URL parameter
    const urlParams = new URLSearchParams(window.location.search);
    const encodedUrl = urlParams.get('url');
    if (encodedUrl) {
        const base64Str = encodedUrl.replace(/ /g, '+');
        fetch('/api/decode', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ base64: base64Str })
        })
            .then(res => {
                if (res.status === 401) {
                    window.location.href = '/login.html';
                    throw new Error('Not logged in');
                }
                if (!res.ok) throw new Error('Failed to decode URL on backend');
                return res.json();
            })
            .then(data => {
                if (data.url && urlInput) {
                    urlInput.value = data.url;
                    // Automatically fetch title and open filename settings
                    editFilenameBtn?.click();
                }
            })
            .catch(e => {
                 console.error('Failed to decode URL parameter:', e);
             });
     }

    // Change Password Modal handling
    const changePasswordBtn = document.getElementById('change-password-btn');
    const changePasswordModal = document.getElementById('change-password-modal');
    const closePasswordBtn = document.getElementById('close-password-btn');
    const cancelPasswordBtn = document.getElementById('cancel-password-btn');
    const changePasswordForm = document.getElementById('change-password-form');
    const passwordErrorEl = document.getElementById('password-error-message');
    const passwordSuccessEl = document.getElementById('password-success-message');
    const newPasswordInput = document.getElementById('new-password');
    const strengthBar = document.getElementById('password-strength-bar');
    const strengthLabel = document.getElementById('password-strength-label');
    const savePasswordBtn = document.getElementById('save-password-btn');

    const minimumPasswordEntropy = 45;
    let currentEntropy = 0;
    let entropyTimeout = null;

    const checkPasswordStrength = (password) => {
        if (!password) {
            currentEntropy = 0;
            updateStrengthUI(0);
            return;
        }

        if (entropyTimeout) clearTimeout(entropyTimeout);

        entropyTimeout = setTimeout(async () => {
            try {
                const response = await fetch('/api/password-entropy', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ password })
                });
                if (response.ok) {
                    const data = await response.json();
                    currentEntropy = data.entropy || 0;
                    updateStrengthUI(currentEntropy);
                }
            } catch (err) {
                console.error('Error fetching password entropy:', err);
            }
        }, 150);
    };

    const updateStrengthUI = (entropy) => {
        const percentage = Math.min((entropy / minimumPasswordEntropy) * 100, 100);
        
        if (strengthBar) {
            strengthBar.style.width = `${percentage}%`;

            let color = '#ff3b30'; // Red
            let glowColor = 'rgba(255, 59, 48, 0.5)';

            if (entropy >= 65) {
                color = '#00c3ff'; // Cyan
                glowColor = 'rgba(0, 195, 255, 0.5)';
            } else if (entropy >= 45) {
                color = '#34c759'; // Green
                glowColor = 'rgba(52, 199, 89, 0.5)';
            } else if (entropy >= 25) {
                color = '#ffcc00'; // Yellow
                glowColor = 'rgba(255, 204, 0, 0.5)';
            }

            strengthBar.style.backgroundColor = color;
            strengthBar.style.boxShadow = `0 0 8px ${glowColor}`;
        }
        
        if (strengthLabel) {
            let labelText = 'Příliš slabé';
            if (entropy >= 65) {
                labelText = 'Velmi silné';
            } else if (entropy >= minimumPasswordEntropy) {
                labelText = 'Silné';
            } else if (entropy >= 25) {
                labelText = 'Slabé';
            }
            strengthLabel.textContent = `Síla: ${labelText}`;
        }

        if (savePasswordBtn) {
            savePasswordBtn.disabled = (entropy < minimumPasswordEntropy);
        }
    };

    const showPasswordModal = () => {
        changePasswordModal.classList.remove('hidden');
        document.getElementById('current-password').value = '';
        document.getElementById('new-password').value = '';
        document.getElementById('confirm-new-password').value = '';
        passwordErrorEl.classList.add('hidden');
        passwordSuccessEl.classList.add('hidden');
        
        // Re-enable inputs if they were disabled previously
        document.getElementById('current-password').disabled = false;
        document.getElementById('new-password').disabled = false;
        document.getElementById('confirm-new-password').disabled = false;
        document.getElementById('save-password-btn').disabled = false;
        document.getElementById('cancel-password-btn').disabled = false;
        if (closePasswordBtn) closePasswordBtn.disabled = false;

        currentEntropy = 0;
        updateStrengthUI(0);

        document.getElementById('current-password').focus();
    };

    const hidePasswordModal = () => {
        changePasswordModal.classList.add('hidden');
    };

    newPasswordInput?.addEventListener('input', (e) => {
        checkPasswordStrength(e.target.value);
    });

    changePasswordBtn?.addEventListener('click', (e) => {
        e.preventDefault();
        if (changePasswordBtn.getAttribute('aria-disabled') === 'true') return;

        showPasswordModal();
    });

    closePasswordBtn?.addEventListener('click', hidePasswordModal);
    cancelPasswordBtn?.addEventListener('click', hidePasswordModal);

    // Close on click outside modal content
    changePasswordModal?.addEventListener('click', (e) => {
        if (e.target === changePasswordModal) {
            hidePasswordModal();
        }
    });

    changePasswordForm?.addEventListener('submit', async (e) => {
        e.preventDefault();
        passwordErrorEl.classList.add('hidden');
        passwordSuccessEl.classList.add('hidden');

        const currentPassword = document.getElementById('current-password').value;
        const newPassword = document.getElementById('new-password').value;
        const confirmNewPassword = document.getElementById('confirm-new-password').value;

        if (currentEntropy < minimumPasswordEntropy) {
            passwordErrorEl.textContent = 'Nové heslo není dostatečně silné.';
            passwordErrorEl.classList.remove('hidden');
            return;
        }

        if (newPassword !== confirmNewPassword) {
            passwordErrorEl.textContent = 'Nová hesla se neshodují.';
            passwordErrorEl.classList.remove('hidden');
            return;
        }

        try {
            const response = await fetch('/api/change-password', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ currentPassword, newPassword, confirmNewPassword })
            });

            const data = await response.json();

            if (response.ok) {
                passwordSuccessEl.textContent = 'Heslo bylo úspěšně změněno. Odhlašuji...';
                passwordSuccessEl.classList.remove('hidden');
                
                // Disable inputs and buttons
                document.getElementById('current-password').disabled = true;
                document.getElementById('new-password').disabled = true;
                document.getElementById('confirm-new-password').disabled = true;
                document.getElementById('save-password-btn').disabled = true;
                document.getElementById('cancel-password-btn').disabled = true;
                if (closePasswordBtn) closePasswordBtn.disabled = true;

                // Redirect to login after 2 seconds
                setTimeout(() => {
                    window.location.href = '/login.html';
                }, 2000);
            } else {
                passwordErrorEl.textContent = data.error || 'Nepodařilo se změnit heslo.';
                passwordErrorEl.classList.remove('hidden');
            }
        } catch (err) {
            console.error('Failed to change password:', err);
            passwordErrorEl.textContent = 'Síťová chyba. Zkuste to prosím znovu.';
            passwordErrorEl.classList.remove('hidden');
        }
    });
});
