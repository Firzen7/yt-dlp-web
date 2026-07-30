document.addEventListener('DOMContentLoaded', function () {
    const urlInput = document.getElementById('url');
    const saveBtn = document.getElementById('save');
    const statusMsg = document.getElementById('status');

    // Load current config
    chrome.storage.sync.get(['downloaderUrl'], function (result) {
        if (result.downloaderUrl) {
            urlInput.value = result.downloaderUrl;
        } else {
            urlInput.value = globalThis.YTDLP_WEB_CONFIG.downloaderUrl;
        }
    });

    // Save config
    saveBtn.addEventListener('click', function () {
        const url = urlInput.value.trim();

        if (!url) {
            statusMsg.textContent = "Please enter a valid URL";
            statusMsg.style.color = "#f44336";
            statusMsg.style.display = 'block';
            setTimeout(() => { statusMsg.style.display = 'none'; }, 2000);
            return;
        }

        chrome.storage.sync.set({ downloaderUrl: url }, function () {
            statusMsg.textContent = "✓ Settings saved successfully";
            statusMsg.style.color = "#4CAF50";
            statusMsg.style.display = 'block';
            setTimeout(() => {
                statusMsg.style.display = 'none';
            }, 2000);
        });
    });
});
