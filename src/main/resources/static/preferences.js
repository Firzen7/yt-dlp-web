(() => {
    const storageKey = 'yt-dlp-web.preferences';
    const legacyLocaleKey = 'yt-dlp-web.locale';
    const schemaVersion = 2;
    const defaults = Object.freeze({
        locale: null,
        downloadMode: 'video',
        audioConversion: 'fastest',
        videoResolution: 'best'
    });

    const validators = {
        locale: value => [null, 'cs', 'en'].includes(value),
        downloadMode: value => ['video', 'audio'].includes(value),
        audioConversion: value => ['fastest', 'mp3'].includes(value),
        videoResolution: value => value === 'best' || /^[1-9]\d{0,4}x[1-9]\d{0,4}$/.test(value)
    };

    const normalizeValue = (key, value) => {
        return validators[key]?.(value) ? value : defaults[key];
    };

    const normalizedPreferences = (stored = {}) => {
        return Object.fromEntries(
            Object.keys(defaults).map(key => [key, normalizeValue(key, stored[key])])
        );
    };

    const readStoredPreferences = () => {
        try {
            const stored = JSON.parse(localStorage.getItem(storageKey) || '{}');
            return stored && typeof stored === 'object' && !Array.isArray(stored) ? stored : {};
        } catch (_) {
            return {};
        }
    };

    const readLegacyLocale = () => {
        try {
            return normalizeValue('locale', localStorage.getItem(legacyLocaleKey));
        } catch (_) {
            return null;
        }
    };

    const writePreferences = (preferences) => {
        try {
            localStorage.setItem(storageKey, JSON.stringify({
                version: schemaVersion,
                ...preferences
            }));
            return true;
        } catch (_) {
            return false;
        }
    };

    const removeLegacyLocale = () => {
        try {
            localStorage.removeItem(legacyLocaleKey);
        } catch (_) {
            // Local storage may be unavailable in privacy-restricted browsers.
        }
    };

    const loadPreferences = () => {
        const preferences = normalizedPreferences(readStoredPreferences());
        const legacyLocale = readLegacyLocale();

        if (!preferences.locale && legacyLocale) {
            preferences.locale = legacyLocale;
        }

        if (writePreferences(preferences) && legacyLocale) {
            removeLegacyLocale();
        }

        return preferences;
    };

    const preferences = loadPreferences();

    window.appPreferences = {
        get(key) {
            return preferences[key];
        },
        set(key, value) {
            if (!(key in defaults)) return false;

            const normalizedValue = normalizeValue(key, value);
            if (normalizedValue !== value) return false;

            preferences[key] = normalizedValue;
            return writePreferences(preferences);
        }
    };
})();
