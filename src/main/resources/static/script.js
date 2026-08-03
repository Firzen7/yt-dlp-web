document.addEventListener('DOMContentLoaded', async () => {
    await window.i18n.init();

    const { setText, t } = window.i18n;
    const urlInput = document.getElementById('video-url');
    const editFilenameBtn = document.getElementById('edit-filename-btn');
    const filenameGroup = document.getElementById('filename-group');
    const customFilenameInput = document.getElementById('custom-filename');
    const filenameLoader = document.getElementById('filename-loader');
    const clearFilenameBtn = document.getElementById('clear-filename-btn');
    const formatToggle = document.getElementById('format-toggle');
    const audioOptions = document.getElementById('audio-options');
    const audioConversionInputs = document.querySelectorAll('input[name="audio-conversion"]');
    const resolutionOptions = document.getElementById('resolution-options');
    const resolutionChoices = document.getElementById('resolution-options-choices');
    const resolutionResults = document.getElementById('resolution-results');
    const resolutionStatus = document.getElementById('resolution-status');
    const resolutionStatusText = document.getElementById('resolution-status-text');
    const resolutionLoader = document.getElementById('resolution-loader');
    const selectResolutionBtn = document.getElementById('select-resolution-btn');
    const videoLabel = document.querySelector('.video-label');
    const mp3Label = document.querySelector('.mp3-label');
    const form = document.getElementById('download-form');

    const submitBtn = document.getElementById('submit-btn');
    const statusMessage = document.getElementById('status-message');
    const errorMessage = document.getElementById('error-message');
    const cancelDownloadBtn = document.getElementById('cancel-download-btn');
    const logoutBtn = document.getElementById('logout-btn');

    const urlPlaceholderKey = () => {
        const platform = navigator.userAgentData?.platform || navigator.platform || '';
        const userAgent = navigator.userAgent || '';
        const touchMac = /mac/i.test(platform) && navigator.maxTouchPoints > 1;
        const mobile = /android|iphone|ipad|ipod/i.test(userAgent) || touchMac;

        if (mobile) return 'download.urlPlaceholder.generic';
        if (/mac/i.test(platform)) return 'download.urlPlaceholder.mac';
        if (/win|linux|cros/i.test(`${platform} ${userAgent}`)) {
            return 'download.urlPlaceholder.ctrl';
        }

        return 'download.urlPlaceholder.generic';
    };

    const applyUrlPlaceholder = () => {
        if (!urlInput) return;

        const key = urlPlaceholderKey();
        urlInput.dataset.i18nPlaceholder = key;
        urlInput.placeholder = t(key);
    };

    applyUrlPlaceholder();

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
        delete e.target.dataset.i18nValue;
        e.target.value = e.target.value.replace(/[<>:"/\\|?*\x00-\x1F]/g, '');
        toggleClearBtn();
    });

    // Clear filename button
    clearFilenameBtn?.addEventListener('click', () => {
        if (clearFilenameBtn.disabled) return;

        if (customFilenameInput) {
            delete customFilenameInput.dataset.i18nValue;
            customFilenameInput.value = '';
            toggleClearBtn();
            customFilenameInput.focus();
        }
    });

    let currentMaxProgress = 0;
    let currentTaskId = null;
    let currentAudioConversion = 'fastest';
    let resolutionRequestController = null;
    let loadedResolutionUrl = null;
    let loadedResolutions = [];
    let resolutionControlsDisabled = false;
    let resolutionLoading = false;

    const setAudioOptionsDisabled = (disabled) => {
        audioConversionInputs.forEach(input => {
            input.disabled = disabled;
        });
        audioOptions?.classList.toggle('disabled', disabled);
    };

    const setResolutionOptionsDisabled = (disabled) => {
        resolutionControlsDisabled = disabled;
        resolutionOptions?.classList.toggle('disabled', disabled);
        updateResolutionControlState();
    };

    const updateAudioOptionsVisibility = () => {
        if (formatToggle?.checked) {
            audioOptions?.classList.remove('hidden');
            resolutionOptions?.classList.add('hidden');
        } else {
            audioOptions?.classList.add('hidden');
            resolutionOptions?.classList.remove('hidden');
        }
    };

    const setResolutionStatus = (key = null, loading = false, error = false) => {
        resolutionStatus?.classList.toggle('is-empty', !key);
        resolutionStatus?.classList.toggle('error', error);

        if (resolutionLoader) resolutionLoader.hidden = !loading;

        if (key) {
            setText(resolutionStatusText, key);
        } else if (resolutionStatusText) {
            delete resolutionStatusText.dataset.i18n;
            resolutionStatusText.textContent = '';
        }
    };

    const clearDynamicResolutionOptions = () => {
        resolutionResults?.replaceChildren();
        resolutionResults?.classList.add('is-empty');
    };

    const cancelResolutionLoad = () => {
        resolutionRequestController?.abort();
        resolutionRequestController = null;
        resolutionLoading = false;
        setResolutionStatus();
        updateResolutionControlState();
    };

    const createResolutionOption = (resolution) => {
        const option = document.createElement('label');
        const input = document.createElement('input');
        const label = document.createElement('span');

        option.className = 'resolution-option';
        option.dataset.fetchedResolution = '';
        input.type = 'radio';
        input.name = 'video-resolution';
        input.value = `${resolution.width}x${resolution.height}`;
        label.textContent = `${resolution.width} × ${resolution.height}`;

        option.append(input, label);
        return option;
    };

    const selectPreferredResolution = () => {
        const preferred = window.appPreferences?.get('videoResolution') || 'best';
        const inputs = Array.from(resolutionOptions?.querySelectorAll('input') || []);
        const selected = inputs.find(input => input.value === preferred) || inputs[0];

        if (selected) selected.checked = true;
    };

    const renderResolutionOptions = (url, resolutions) => {
        clearDynamicResolutionOptions();

        resolutions.forEach(resolution => {
            resolutionResults?.appendChild(createResolutionOption(resolution));
        });
        resolutionResults?.classList.toggle('is-empty', !resolutions.length);

        loadedResolutionUrl = url;
        loadedResolutions = resolutions;
        selectPreferredResolution();

        const statusKey = resolutions.length ? null : 'download.video.noResolutions';
        setResolutionStatus(statusKey, false, !resolutions.length);
    };

    const validResolutions = (values) => {
        const unique = new Map();

        values.forEach(value => {
            if (!Number.isInteger(value?.width) || !Number.isInteger(value?.height)) return;
            if (value.width <= 0 || value.height <= 0) return;

            unique.set(`${value.width}x${value.height}`, value);
        });

        return Array.from(unique.values());
    };

    const currentResolutionUrl = () => urlInput?.value.trim() || '';

    const resolutionsAreStale = () => {
        return loadedResolutions.length > 0 && loadedResolutionUrl !== currentResolutionUrl();
    };

    const updateResolutionSelectTitle = (stale) => {
        if (!selectResolutionBtn) return;

        const key = stale
            ? 'download.video.reloadResolutions'
            : 'download.video.loadResolutions';
        selectResolutionBtn.dataset.i18nTitle = key;
        selectResolutionBtn.title = t(key);
    };

    function updateResolutionControlState() {
        const stale = resolutionsAreStale();
        const bestInput = resolutionChoices?.querySelector('[data-default-resolution] input');
        const fetchedOptions = resolutionResults?.querySelectorAll('[data-fetched-resolution]') || [];

        if (stale && bestInput) bestInput.checked = true;
        if (!stale && loadedResolutionUrl === currentResolutionUrl()) selectPreferredResolution();
        if (bestInput) bestInput.disabled = resolutionControlsDisabled;
        if (selectResolutionBtn) {
            selectResolutionBtn.disabled = resolutionControlsDisabled || resolutionLoading;
            const selectedResolution = resolutionOptions?.querySelector('input:checked')?.value;
            selectResolutionBtn.classList.toggle(
                'is-selected',
                Boolean(selectedResolution && selectedResolution !== 'best')
            );
        }

        fetchedOptions.forEach(option => {
            option.classList.toggle('is-stale', stale);
            option.querySelector('input').disabled = resolutionControlsDisabled || resolutionLoading || stale;
        });

        updateResolutionSelectTitle(stale);
    }

    const loadAvailableResolutions = async () => {
        const url = currentResolutionUrl();
        if (!url || !urlInput.checkValidity()) {
            urlInput?.reportValidity();
            return;
        }

        cancelResolutionLoad();
        const controller = new AbortController();
        resolutionRequestController = controller;
        resolutionLoading = true;
        setResolutionStatus('download.video.loadingResolutions', true);
        updateResolutionControlState();

        try {
            const response = await fetch('/api/resolutions', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ url }),
                signal: controller.signal
            });

            if (response.status === 401) {
                window.location.href = '/login.html';
                return;
            }
            if (!response.ok) throw new Error('Could not retrieve resolutions');

            const data = await response.json();
            if (url !== currentResolutionUrl()) return;

            renderResolutionOptions(url, validResolutions(data.resolutions || []));
        } catch (error) {
            if (error.name === 'AbortError') return;

            console.error('Failed to get resolutions:', error);
            setResolutionStatus('download.video.resolutionLoadFailed', false, true);
        } finally {
            if (resolutionRequestController === controller) {
                resolutionRequestController = null;
                resolutionLoading = false;
                updateResolutionControlState();
            }
        }
    };

    const handleResolutionUrlChange = () => {
        if (resolutionRequestController) cancelResolutionLoad();
        setResolutionStatus();
        updateResolutionControlState();
    };

    const selectedVideoResolution = () => {
        const selected = resolutionOptions?.querySelector('input:checked');
        const value = selected?.value;
        if (!value || value === 'best' || selected.disabled || resolutionsAreStale()) return null;

        const [width, height] = value.split('x').map(Number);
        return Number.isInteger(width) && Number.isInteger(height) ? { width, height } : null;
    };

    const updateFormatAppearance = () => {
        const audioSelected = formatToggle?.checked;

        videoLabel?.classList.toggle('glow-text', !audioSelected);
        mp3Label?.classList.toggle('glow-text', Boolean(audioSelected));
        updateAudioOptionsVisibility();
    };

    const applyDownloadPreferences = () => {
        const downloadMode = window.appPreferences?.get('downloadMode') || 'video';
        const audioConversion = window.appPreferences?.get('audioConversion') || 'fastest';

        if (formatToggle) {
            formatToggle.checked = downloadMode === 'audio';
        }

        audioConversionInputs.forEach(input => {
            input.checked = input.value === audioConversion;
        });

        updateFormatAppearance();
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
            const usernameEl = document.getElementById('logged-in-username');
            if (usernameEl && data.username) {
                usernameEl.textContent = data.username;
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
            alert(t('download.errors.urlRequiredBeforeFilename'));
            return;
        }

        filenameGroup.classList.toggle('hidden');
        if (!filenameGroup.classList.contains('hidden')) {
            filenameLoader.classList.remove('hidden');
            delete customFilenameInput.dataset.i18nValue;
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
                    delete customFilenameInput.dataset.i18nValue;
                    customFilenameInput.value = data.title.replace(/[<>:"/\\|?*\x00-\x1F]/g, '');
                    toggleClearBtn();
                }
            } catch (err) {
                console.error('Failed to get title:', err);
                customFilenameInput.dataset.i18nValue = 'download.filenameLoadFailed';
                customFilenameInput.value = t('download.filenameLoadFailed');
                toggleClearBtn();
            } finally {
                filenameLoader.classList.add('hidden');
            }
        } else {
            delete customFilenameInput.dataset.i18nValue;
            customFilenameInput.value = '';
            toggleClearBtn();
        }
    });

    // Toggle switch functionality for styling
    formatToggle?.addEventListener('change', (e) => {
        const downloadMode = e.target.checked ? 'audio' : 'video';

        window.appPreferences?.set('downloadMode', downloadMode);
        updateFormatAppearance();
    });

    audioConversionInputs.forEach(input => {
        input.addEventListener('change', () => {
            if (input.checked) {
                window.appPreferences?.set('audioConversion', input.value);
            }
        });
    });

    resolutionOptions?.addEventListener('change', (event) => {
        if (event.target.name === 'video-resolution') {
            window.appPreferences?.set('videoResolution', event.target.value);
            updateResolutionControlState();
        }
    });

    selectResolutionBtn?.addEventListener('click', loadAvailableResolutions);
    urlInput?.addEventListener('input', handleResolutionUrlChange);

    applyDownloadPreferences();
    updateResolutionControlState();

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
        setResolutionOptionsDisabled(false);

        filenameGroup?.classList.add('hidden');
        if (customFilenameInput) {
            delete customFilenameInput.dataset.i18nValue;
            customFilenameInput.value = '';
            toggleClearBtn();
            customFilenameInput.disabled = false;
        }
        if (clearFilenameBtn) clearFilenameBtn.disabled = false;
        if (urlInput) urlInput.disabled = false;
        setChangePasswordDisabled(false);

        updateResolutionControlState();

        // Reset progress bar and text
        const progressBarBg = document.getElementById('progress-bar-bg');
        if (progressBarBg) progressBarBg.style.width = '0%';
        const statusText = document.getElementById('status-text');
        setText(statusText, 'download.status.processing');
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
        const resolution = format === 'video' ? selectedVideoResolution() : null;

        let customFilename = null;
        if (!filenameGroup?.classList.contains('hidden') && customFilenameInput) {
            customFilename = customFilenameInput.value.trim();
        }

        if (!url) {
            setText(errorMessage, 'download.errors.urlRequired');
            errorMessage.classList.remove('hidden');
            submitBtn.classList.remove('hidden');
            statusMessage.classList.add('hidden');
            return;
        }

        // Reset max progress for a new download
        currentMaxProgress = 0;
        currentAudioConversion = audioConversion;

        // Show loading state
        cancelResolutionLoad();
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
        setResolutionOptionsDisabled(true);

        if (customFilenameInput) customFilenameInput.disabled = true;
        if (clearFilenameBtn) clearFilenameBtn.disabled = true;
        if (urlInput) urlInput.disabled = true;
        setChangePasswordDisabled(true);

        try {
            const bodyData = { url, format };
            if (format === 'mp3') {
                bodyData.audioConversion = audioConversion;
            }
            if (resolution) {
                bodyData.resolution = resolution;
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
            showError('download.errors.generic');
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
                setText(errorMessage, 'download.errors.failed');
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
                            const statusKey = isMp3 && currentAudioConversion === 'mp3'
                                ? 'download.status.converting'
                                : 'download.status.finishing';
                            setText(statusText, statusKey);
                        } else {
                            setText(statusText, 'download.status.downloading', {
                                progress: currentMaxProgress
                            });
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
            showError('download.errors.generic');
        }
    };

    const showDownload = (downloadUrl) => {
        // Change status text to indicate completion
        const statusText = document.getElementById('status-text');
        const progressBarBg = document.getElementById('progress-bar-bg');

        setText(statusText, 'download.status.completed');
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

        setText(statusText, 'download.status.cancelled');
        if (progressBarBg) progressBarBg.style.width = '0%';
        cancelDownloadBtn?.classList.add('hidden');

        setTimeout(resetUI, 1000);
    };

    const showError = (errorKey = null) => {
        if (errorKey) setText(errorMessage, errorKey);

        statusMessage.classList.add('hidden');
        errorMessage.classList.remove('hidden');
        cancelDownloadBtn?.classList.add('hidden');

        // Reset progress bar just in case
        const progressBarBg = document.getElementById('progress-bar-bg');
        if (progressBarBg) progressBarBg.style.width = '0%';
        const statusText = document.getElementById('status-text');
        setText(statusText, 'download.status.processing');

        setTimeout(() => {
            resetUI();
        }, 5000);
    };

    cancelDownloadBtn?.addEventListener('click', async () => {
        if (!currentTaskId || cancelDownloadBtn.disabled) return;

        cancelDownloadBtn.disabled = true;
        const statusText = document.getElementById('status-text');
        setText(statusText, 'download.status.cancelling');

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
            showError('download.errors.cancelFailed');
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
                    handleResolutionUrlChange();
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
    const currentPasswordInput = document.getElementById('current-password');
    const newPasswordInput = document.getElementById('new-password');
    const confirmNewPasswordInput = document.getElementById('confirm-new-password');
    const strengthBar = document.getElementById('password-strength-bar');
    const strengthLabel = document.getElementById('password-strength-label');
    const savePasswordBtn = document.getElementById('save-password-btn');

    const minimumPasswordEntropy = 45;
    let currentEntropy = 0;
    let measuredPassword = '';
    let entropyTimeout = null;
    let entropyRequestId = 0;

    const requestPasswordEntropy = async (password) => {
        const response = await fetch('/api/password-entropy', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ password })
        });

        if (!response.ok) {
            throw new Error('Password entropy request failed');
        }

        const data = await response.json();
        return Number(data.entropy) || 0;
    };

    const checkPasswordStrength = (password) => {
        if (entropyTimeout) clearTimeout(entropyTimeout);

        const requestId = ++entropyRequestId;
        currentEntropy = 0;
        measuredPassword = '';
        updateStrengthUI(0);

        if (!password) {
            return;
        }

        entropyTimeout = setTimeout(async () => {
            try {
                const entropy = await requestPasswordEntropy(password);
                if (requestId !== entropyRequestId) return;

                currentEntropy = entropy;
                measuredPassword = password;
                updateStrengthUI(entropy);
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
            } else if (entropy >= minimumPasswordEntropy) {
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
            let strengthKey = 'password.strength.tooWeak';
            if (entropy >= 65) {
                strengthKey = 'password.strength.veryStrong';
            } else if (entropy >= minimumPasswordEntropy) {
                strengthKey = 'password.strength.strong';
            } else if (entropy >= 25) {
                strengthKey = 'password.strength.weak';
            }
            setText(strengthLabel, strengthKey);
        }
    };

    const clearPasswordFeedback = () => {
        passwordErrorEl.classList.add('hidden');
        passwordSuccessEl.classList.add('hidden');

        currentPasswordInput.removeAttribute('aria-invalid');
        newPasswordInput.removeAttribute('aria-invalid');
        confirmNewPasswordInput.removeAttribute('aria-invalid');
    };

    const showPasswordError = (errorKey, input = null) => {
        passwordSuccessEl.classList.add('hidden');
        setText(passwordErrorEl, errorKey);
        passwordErrorEl.classList.remove('hidden');

        if (input) {
            input.setAttribute('aria-invalid', 'true');
            input.focus();
        }
    };

    const validatePasswordFields = (currentPassword, newPassword, confirmation) => {
        if (!currentPassword) {
            return { key: 'password.errors.currentRequired', input: currentPasswordInput };
        }

        if (!newPassword) {
            return { key: 'password.errors.newRequired', input: newPasswordInput };
        }

        if (!confirmation) {
            return { key: 'password.errors.confirmRequired', input: confirmNewPasswordInput };
        }

        if (newPassword !== confirmation) {
            return { key: 'password.errors.mismatch', input: confirmNewPasswordInput };
        }

        return null;
    };

    const ensureCurrentEntropy = async (password) => {
        if (measuredPassword === password) return true;

        if (entropyTimeout) clearTimeout(entropyTimeout);
        const requestId = ++entropyRequestId;

        try {
            const entropy = await requestPasswordEntropy(password);
            if (requestId !== entropyRequestId) return false;

            currentEntropy = entropy;
            measuredPassword = password;
            updateStrengthUI(entropy);
            return true;
        } catch (err) {
            console.error('Error fetching password entropy:', err);
            return false;
        }
    };

    const showPasswordModal = () => {
        changePasswordModal.classList.remove('hidden');
        currentPasswordInput.value = '';
        newPasswordInput.value = '';
        confirmNewPasswordInput.value = '';
        clearPasswordFeedback();
        
        // Re-enable inputs if they were disabled previously
        currentPasswordInput.disabled = false;
        newPasswordInput.disabled = false;
        confirmNewPasswordInput.disabled = false;
        savePasswordBtn.disabled = false;
        document.getElementById('cancel-password-btn').disabled = false;
        if (closePasswordBtn) closePasswordBtn.disabled = false;

        if (entropyTimeout) clearTimeout(entropyTimeout);
        entropyRequestId += 1;
        currentEntropy = 0;
        measuredPassword = '';
        updateStrengthUI(0);

        currentPasswordInput.focus();
    };

    const hidePasswordModal = () => {
        changePasswordModal.classList.add('hidden');
    };

    newPasswordInput?.addEventListener('input', (e) => {
        checkPasswordStrength(e.target.value);
    });

    [currentPasswordInput, newPasswordInput, confirmNewPasswordInput].forEach((input) => {
        input.addEventListener('input', () => {
            input.removeAttribute('aria-invalid');
            passwordErrorEl.classList.add('hidden');
        });
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
        clearPasswordFeedback();

        const currentPassword = currentPasswordInput.value;
        const newPassword = newPasswordInput.value;
        const confirmNewPassword = confirmNewPasswordInput.value;
        const validationError = validatePasswordFields(
            currentPassword,
            newPassword,
            confirmNewPassword
        );

        if (validationError) {
            showPasswordError(validationError.key, validationError.input);
            return;
        }

        if (!await ensureCurrentEntropy(newPassword)) {
            showPasswordError('password.errors.strengthUnavailable');
            return;
        }

        if (currentEntropy < minimumPasswordEntropy) {
            showPasswordError('password.errors.tooWeak', newPasswordInput);
            return;
        }

        savePasswordBtn.disabled = true;

        try {
            const response = await fetch('/api/change-password', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ currentPassword, newPassword, confirmNewPassword })
            });

            if (response.status === 401) {
                window.location.href = '/login.html';
                return;
            }

            if (response.ok) {
                setText(passwordSuccessEl, 'password.success');
                passwordSuccessEl.classList.remove('hidden');
                
                // Disable inputs and buttons
                currentPasswordInput.disabled = true;
                newPasswordInput.disabled = true;
                confirmNewPasswordInput.disabled = true;
                document.getElementById('cancel-password-btn').disabled = true;
                if (closePasswordBtn) closePasswordBtn.disabled = true;

                // Redirect to login after 2 seconds
                setTimeout(() => {
                    window.location.href = '/login.html';
                }, 2000);
            } else {
                const errorKey = response.status === 400
                    ? 'password.errors.currentInvalid'
                    : 'password.errors.changeFailed';
                const input = response.status === 400
                    ? currentPasswordInput
                    : null;

                showPasswordError(errorKey, input);
                savePasswordBtn.disabled = false;
            }
        } catch (err) {
            console.error('Failed to change password:', err);
            showPasswordError('password.errors.network');
            savePasswordBtn.disabled = false;
        }
    });
});
