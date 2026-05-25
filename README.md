# MangoTree

A free, minimal Android git client for syncing GitHub repositories. No F-Droid, no subscriptions, no broad file permissions.

## Features

- Multiple repos via folder picker (scoped storage, no all-files permission)
- GitHub OAuth — you register your own OAuth app, your credentials never touch a third party
- Pull with rebase, push, branch switch
- Conflict resolution: cancel or force pull (discard local)
- OAuth token stored encrypted via Android Keystore
- Built on JGit — the same git implementation powering Eclipse and Gerrit

## Setup

### 1. Register a GitHub OAuth App

1. Go to [github.com/settings/developers](https://github.com/settings/developers)
2. Click **New OAuth App**
3. Set **Authorization callback URL** to: `com.mangotree://oauth`
4. Note your **Client ID** and **Client Secret**

### 2. Build

Open in Android Studio (Hedgehog or newer), or from the command line:

```bash
./gradlew assembleDebug
```

APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

Alternatively, every push to `main` builds the APK automatically via GitHub Actions — grab it from the Actions tab as an artifact.

### 3. First run

1. Tap the login banner and enter your Client ID + Client Secret
2. Authenticate via GitHub in the browser
3. Tap **+** to add a repo — enter name, remote URL, then pick a local folder
4. MangoTree will clone if the folder is empty, or attach if it's already a git repo

### 4. Branch protection (recommended)

Before using MangoTree as your primary storage, set branch protection on any branch holding important work:

- Go to your repo on GitHub → Settings → Branches → Add rule
- Enable **Restrict deletions** and **Block force pushes**

This protects your work at the remote level regardless of what the app does.

## Architecture

- **JGit** — pure Java git implementation, no JNI, no native binaries
- **AppAuth** — OAuth 2.0 / PKCE flow
- **EncryptedSharedPreferences** — AES256 token storage backed by Android Keystore
- **Scoped storage** — folder picker only, no broad file permissions

## License

MIT
