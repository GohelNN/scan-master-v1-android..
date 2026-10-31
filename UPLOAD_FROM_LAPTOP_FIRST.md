# Scan Master V1 — Upload from a laptop

This is an Android **starter app**, not the finished live scanner. It does not have live stock data, alerts, or Angel One integration.

## Upload correctly to GitHub
1. Download and extract `ScanMasterV1_UPLOAD_READY.zip` on your laptop.
2. Open the extracted folder. You should directly see `app`, `engine`, `tools`, `docs`, `.github`, `build.gradle.kts`, `settings.gradle.kts`, and `gradle.properties`.
3. On Windows, turn on **File Explorer > View > Show > Hidden items** if `.github` is not visible.
4. Open the **Code** tab of your GitHub repository and click **Add file > Upload files**.
5. In File Explorer, select the **folders and files inside** the extracted folder (Ctrl+A), not the enclosing folder and **not** the ZIP file. Drag and drop that selection into GitHub's upload area.
6. Before committing, verify that the upload shows nested paths such as `.github/workflows/build-apk.yml`, `app/build.gradle.kts` and `engine/src/main/kotlin/com/scanmaster/engine/Oscillators.kt`. If all files show as single filenames with no folders, cancel and retry with drag-and-drop.
7. Click **Commit changes**. If you previously uploaded loose files at the top level, those stray files will remain; they can be removed later. The build can work after correct folders are uploaded.
8. Open **Actions > Build Android APK > Run workflow**. If the build succeeds, download artifact `ScanMasterV1-debug-apk`, unzip and install `app-debug.apk`.

## Verify first
On GitHub **Code**, you must be able to click `.github` > `workflows` > `build-apk.yml`, and also click `app` > `build.gradle.kts`.

## Important limitations
- Local Kotlin calculation-engine checks are not equivalent to full TradingView indicator verification.
- The GitHub Android build has not been verified here; if the build reports a red X, open the failure log and share its screenshot or error text.
- Never add brokerage credentials or tokens to your GitHub repository.
