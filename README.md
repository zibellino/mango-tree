# MangoTree

A minimal Android git client for syncing GitHub repositories.

## Features

- Multiple repos via folder picker
- GitHub OAuth — you register your own OAuth app, your credentials never touch a third party
- Pull with rebase, push, branch switch
- Conflict resolution: cancel or force pull (discard local)
- OAuth token and client credentials stored encrypted via Android Keystore
- Built on JGit — the same git implementation powering Eclipse and Gerrit

## Storage permissions

MangoTree requests **All files access** (`MANAGE_EXTERNAL_STORAGE`) on Android 11+, not scoped storage.
This is required because JGit operates on real filesystem paths, not the `content://` URIs that scoped
storage APIs hand back — the folder picker is only used to let you choose *where*, the actual git
operations then work directly against the resolved path on disk.

## Behavior notes

- **Pull syncs local branches to match the remote.** After a successful pull, MangoTree fetches all
  remote branches and deletes any local branch that no longer has a remote counterpart. This keeps
  the local repo mirroring GitHub, but means locally-created branches that were never pushed (or whose
  remote branch was deleted) will be removed automatically on the next pull. This is intentional
  behavior, not a bug — be aware of it if you rely on local-only branches.

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

MangoTree isn't published on the Play Store and isn't intended to be. Get it as a GitHub Release APK,
a build artifact from Actions, or by building from source yourself.

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
- **EncryptedSharedPreferences** — AES256 storage backed by Android Keystore, used for the OAuth
  access token and the OAuth app's client ID/secret
- **Folder picker (SAF)** — used only to select a location; git operations then work on the
  resolved real filesystem path, which requires all-files access (see Storage permissions above)

## License

MIT
