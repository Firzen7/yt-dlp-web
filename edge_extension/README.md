# Edge Extension Configuration

Create the local extension configuration before loading or packaging the extension:

```bash
cp source/config.example.js source/config.local.js
```

Set `downloaderUrl` in `source/config.local.js` to the base URL of the yt-dlp-web
server. This local file is ignored by Git. A URL saved through the extension popup
continues to override this default through `chrome.storage.sync`.
