(() => {
    const supportedLocales = ['cs', 'en'];
    const defaultLocale = 'cs';

    let locale = defaultLocale;
    let messages = {};
    let defaultMessages = {};
    let initialization = null;

    const normalizeLocale = (value) => {
        const language = String(value || '').toLowerCase().split(/[-_]/)[0];
        return supportedLocales.includes(language) ? language : null;
    };

    const storedLocale = () => {
        return normalizeLocale(window.appPreferences?.get('locale'));
    };

    const browserLocale = () => {
        const candidates = navigator.languages || [navigator.language];
        return candidates.map(normalizeLocale).find(Boolean) || defaultLocale;
    };

    const loadCatalog = async (language) => {
        const response = await fetch(`/i18n/${language}.json`);
        if (!response.ok) throw new Error(`Could not load locale: ${language}`);
        return response.json();
    };

    const formatMessage = (message, parameters) => {
        return Object.entries(parameters).reduce(
            (result, [key, value]) => result.replaceAll(`{${key}}`, String(value)),
            message
        );
    };

    const t = (key, parameters = {}) => {
        const message = messages[key] ?? defaultMessages[key] ?? key;
        return formatMessage(message, parameters);
    };

    const elementParameters = (element) => {
        try {
            return JSON.parse(element.dataset.i18nParams || '{}');
        } catch (_) {
            return {};
        }
    };

    const translateElement = (element) => {
        const parameters = elementParameters(element);

        if (element.dataset.i18n) {
            element.textContent = t(element.dataset.i18n, parameters);
        }
        if (element.dataset.i18nPlaceholder) {
            element.placeholder = t(element.dataset.i18nPlaceholder, parameters);
        }
        if (element.dataset.i18nTitle) {
            element.title = t(element.dataset.i18nTitle, parameters);
        }
        if (element.dataset.i18nAriaLabel) {
            element.setAttribute('aria-label', t(element.dataset.i18nAriaLabel, parameters));
        }
        if (element.dataset.i18nValue) {
            element.value = t(element.dataset.i18nValue, parameters);
        }
    };

    const translateDocument = (root = document) => {
        const selector = [
            '[data-i18n]',
            '[data-i18n-placeholder]',
            '[data-i18n-title]',
            '[data-i18n-aria-label]',
            '[data-i18n-value]'
        ].join(',');

        root.querySelectorAll(selector).forEach(translateElement);
        document.documentElement.lang = locale;

        document.querySelectorAll('[data-language-selector]').forEach((select) => {
            select.value = locale;
        });
    };

    const setLocale = async (requestedLocale, persist = true) => {
        const nextLocale = normalizeLocale(requestedLocale) || defaultLocale;
        locale = nextLocale;

        try {
            const selectedMessages = nextLocale === defaultLocale
                ? defaultMessages
                : await loadCatalog(nextLocale);
            messages = { ...defaultMessages, ...selectedMessages };
        } catch (error) {
            console.error('Failed to load selected locale:', error);
            locale = defaultLocale;
            messages = defaultMessages;
        }

        if (persist) window.appPreferences?.set('locale', locale);
        translateDocument();
        window.dispatchEvent(new CustomEvent('i18n:locale-changed', { detail: { locale } }));
    };

    const bindLanguageSelectors = () => {
        document.querySelectorAll('[data-language-selector]').forEach((select) => {
            select.addEventListener('change', () => setLocale(select.value));
        });
    };

    const init = () => {
        if (initialization) return initialization;

        initialization = (async () => {
            try {
                defaultMessages = await loadCatalog(defaultLocale);
                await setLocale(storedLocale() || browserLocale(), false);
                bindLanguageSelectors();
            } catch (error) {
                console.error('Failed to initialize localization:', error);
                messages = defaultMessages;
                translateDocument();
            } finally {
                document.documentElement.classList.remove('i18n-loading');
            }
        })();

        return initialization;
    };

    const setText = (element, key, parameters = {}) => {
        if (!element) return;

        element.dataset.i18n = key;
        element.dataset.i18nParams = JSON.stringify(parameters);
        element.textContent = t(key, parameters);
    };

    window.i18n = {
        init,
        setLocale,
        setText,
        t,
        translateDocument,
        get locale() {
            return locale;
        }
    };
})();
