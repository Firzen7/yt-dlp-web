importScripts("config.local.js");

let downloaderUrl = globalThis.YTDLP_WEB_CONFIG.downloaderUrl;

// Load saved config on startup
chrome.storage.sync.get(['downloaderUrl'], function (result) {
    if (result.downloaderUrl) {
        downloaderUrl = result.downloaderUrl;
    }
});

// Listen for config updates from the popup
chrome.storage.onChanged.addListener(function (changes, namespace) {
    if (changes.downloaderUrl) {
        downloaderUrl = changes.downloaderUrl.newValue;
    }
});

// Intercept navigation to youtub.com
chrome.webNavigation.onBeforeNavigate.addListener(function (details) {
    if (details.frameId === 0) {
        const url = new URL(details.url);

        if (url.hostname === "youtub.com" || url.hostname === "www.youtub.com") {
            let trueYoutubeUrl = "";

            // Check if it has a ?v= parameter (e.g., youtub.com/watch?v=123)
            if (url.searchParams.has('v')) {
                const videoId = url.searchParams.get('v');
                trueYoutubeUrl = "https://www.youtube.com/watch?v=" + videoId;
            }
            // Or if the video ID is just the path (e.g., youtub.com/123)
            else {
                const path = url.pathname.replace(/^\/+/, ''); // Remove leading slashes
                if (path.length > 0) {
                    trueYoutubeUrl = "https://www.youtube.com/watch?v=" + path;
                } else {
                    return; // Just youtub.com home page, do nothing
                }
            }

            // Generate the base64 encoded URL payload
            // Unescape/encodeURIComponent handles utf-8 safely in btoa
            const base64Url = btoa(unescape(encodeURIComponent(trueYoutubeUrl)));

            // Ensure the downloaderUrl ends with a slash
            let finalBaseUrl = downloaderUrl;
            if (!finalBaseUrl.endsWith('/')) {
                finalBaseUrl += '/';
            }

            const redirectUrl = `${finalBaseUrl}?url=${base64Url}`;

            // Redirect the tab
            chrome.tabs.update(details.tabId, { url: redirectUrl });
        }
    }
}, { url: [{ hostContains: 'youtub.com' }] });
