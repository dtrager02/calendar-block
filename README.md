# calendar-blocker

A cron-run script that reads the Google Calendars of everyone who has onboarded, including the calendars they subscribe to. It then places blocks of time for the activities declared in a shared `config.yaml` (in its own repo), on a dedicated **Auto Blocks** calendar it creates for each person.

- **Priorities.** Requests share one priority scale. A request can take a slot held by a strictly lower-priority block. Events not managed by the app are never moved and outrank every request.
- **Joint requests.** A request with several attendees needs a slot that is free for every one of them. It becomes a single event on the organizer's Auto Blocks calendar, with Google invites sent to the other attendees.
- **Strategies.** When several slots fit, the slot with the highest weighted score wins.
  - `prefer-soonest` favors earlier slots.
  - `minimal-gaps` favors slots that sit flush against existing events or the window's edges.
- **Horizon.** Recurring requests are scheduled at most `horizonDays` (default 30) ahead. One-time requests are placed on the first run, however far out they are.
- **Stability.**
  - Blocks in the current week stay put unless an external event now conflicts with them, or a higher-priority request has no other free slot.
  - Blocks in later weeks are re-optimized on every run. A moved block is patched in place.
  - Blocks that have already started are never touched.
- **Removing a request.** Deleting a request from `config.yaml` deletes every one of its blocks that hasn't started yet, however far out. For joint blocks, Google removes the event from every guest's calendar too.
- **Event details.** Each request's `title`, `description` and `location` are declarative. When they change, every future block of that request is updated in place on the next run.
  - Each event stores a fingerprint of its details, so changes are found without comparing content, and unchanged events cost no API calls.
  - A move and a details change on the same event go out as one update.
  - Joint-block guests get an update email.
- **Declines.** If a guest declines a joint block, the app deletes it and doesn't reschedule that period.
- **No slot.** A request that can't fit is skipped.

## Privacy
- **Kept in memory only.** External events are reduced to bare start/end times as soon as they're fetched. Titles, descriptions, locations, attendees and calendar names are never stored, logged or sent anywhere.
- **Gone after each run.** Nothing about external events survives the run. The app keeps no database or cache, and the next run fetches everything again.
- **What's written to disk** (owner-only permissions):
  - each person's OAuth token (`<home>/google/tokens/`)
  - the ID of their Auto Blocks calendar (`<home>/google/users/<email>.json`)
- **Permissions requested:**
  - `calendar.readonly`
  - `calendar.app.created`: the app can create its own calendar and manage events **only on calendars it created**. It can't edit or delete anyone's existing events.
- **Only people named in the config are read.** Anyone can connect, but the app reads only the calendars of people named in a request. For anyone else, it reads only their own Auto Blocks calendar, if they have one.
- **Revoking.** Anyone can revoke access at https://myaccount.google.com/permissions. The next run skips their requests.

## Setup

### Google Cloud Console (once)
Use the project that owns the OAuth client.
1. **APIs & Services → Library → Google Calendar API → Enable.**
2. **Google Auth Platform → Data Access → Add or remove scopes:** add `…/auth/calendar.readonly` and `…/auth/calendar.app.created`.
3. **Google Auth Platform → Audience:** set User type to **External**, then click **Publish app** so the status reads **In production**.
   - In "Testing", refresh tokens expire after 7 days and only listed test users can sign in.
   - Unverified apps allow up to 100 users. Each person sees "Google hasn't verified this app" and clicks **Advanced → Go to … (unsafe)**.
4. **Google Auth Platform → Branding → Authorized domains:** add `jeopardy.fit`. This covers its subdomains.
5. **Clients:** open a client of type **Web application**. Under **Authorized redirect URIs**, add `https://calendar.jeopardy.fit/callback`, which is `publicBaseUrl` + `/callback`. Download the JSON.

### Server
The app reads from two directories, both under the home directory of whoever runs the jar:

| Location | Contents | Override |
|---|---|---|
| `~/.config/calendar-blocker-config/config.yaml` | The configuration: a checkout of the **public** config repo, [dtrager02/calendar-blocker-config](https://github.com/dtrager02/calendar-blocker-config), where friends propose changes by pull request. | `--config FILE` |
| `~/.config/calendar-blocker/credentials.json` | The OAuth client JSON downloaded from Google. **Private.** | `--home DIR` |
| `~/.config/calendar-blocker/google/` | Written by the app: one token file and one state file per person. **Private.** | `--home DIR` |

Never put secrets in the config repo.

```sh
./mill blocker.assembly                       # builds out/blocker/assembly.dest/out.jar (needs JDK 17+)
git clone https://github.com/dtrager02/calendar-blocker-config.git ~/.config/calendar-blocker-config
mkdir -p ~/.config/calendar-blocker && chmod 700 ~/.config/calendar-blocker
cp client_secret_*.json ~/.config/calendar-blocker/credentials.json
chmod 600 ~/.config/calendar-blocker/credentials.json
java -jar out/blocker/assembly.dest/out.jar validate   # checks the config
```

### Onboarding and event management (self-serve)
`serve` runs an always-on, server-rendered website. Scheduling itself stays a cron job. **Anyone** can connect their Google Calendar without needing a GitHub account:

1. Visit `https://calendar.jeopardy.fit/` and click **Sign in with Google**. Outlook, Apple, and more-provider buttons are disabled placeholders.
2. Sign in and approve both calendar permissions. Returning visitors with a valid browser session go directly to their events.
3. View a grid of approved event configurations that mention the signed-in email, including requests whose attendees default to the config owner.
4. Create an event, or propose editing/removing any event that mentions you. Forms support shared attendees, all recurrence types, multiple time windows, and advanced priorities/strategy weights, without JavaScript. The organizer is fixed at creation and cannot be transferred through an edit.
5. Submit to create a PR in the separate config repository. The PR body and commit message identify the provider-verified requester; commits use the token owner's GitHub identity. The receipt links to the PR.
6. A maintainer reviews and merges the PR. The next hourly scheduler run pulls the merged config and applies it. Pending proposals do not appear as active configurations.

Calendar authorization alone does not book anything. Only approved requests cause calendar reads and bookings. Removing a request deletes its future blocks, including joint-event invitations, after the PR is merged. Existing calendar events are never edited.

**Configure GitHub access before starting `serve`:** create a fine-grained personal access token restricted to the config repository, with **Contents: read/write** and **Pull requests: read/write** (Metadata read is implicit). Give it an expiration and replace it before it expires. Keep it on the server, outside both repositories. Create `~/.config/calendar-blocker/web.env`, owned by the user running the app with mode `600`:

```dotenv
CALENDAR_BLOCKER_GITHUB_TOKEN=your-fine-grained-token
CALENDAR_BLOCKER_CONFIG_REPO=dtrager02/calendar-blocker-config
CALENDAR_BLOCKER_CONFIG_PATH=config.yaml
# Optional; otherwise GitHub's default branch is used:
# CALENDAR_BLOCKER_CONFIG_BRANCH=main
```

`serve` reads this file directly at startup; no shell exports or systemd environment settings are needed. With `--home DIR`, it reads `DIR/web.env`. File values override environment variables; environment variables remain supported when a key or the file is absent. Blank lines, comments, optional `export` prefixes, and single/double-quoted values are accepted. Values are literal: shell expressions, variable substitutions, and escape sequences are not evaluated. Restart the app after changing the file.

To rebuild and run manually from the source checkout:

```sh
./mill blocker.assembly
java -jar out/blocker/assembly.dest/out.jar serve
```

The website reads the current config from GitHub, pins forms to a base commit, and creates each proposal on its own branch. It never edits the cron checkout. Forms based on an outdated commit must be reloaded. Retrying the same submission recovers an already-created branch/PR; double-clicking submit does not create duplicate PRs. Forms expire after an hour and open forms/receipts are lost on a service restart; check GitHub before starting a replacement proposal.

Generated changes preserve unrelated YAML text and comments. Simple scalar edits preserve the existing event layout; structural edits may normalize the changed event while retaining its comments. Configs containing YAML aliases, or a flow-style root without a requests field, require manual GitHub editing.

**Repository visibility:** the default config repository is public. Event titles, descriptions, locations, and attendee addresses submitted here become public in PRs and Git history. Use a private repository if these should be private, and point both the website and cron checkout at it. Calendar credentials and browser sessions never enter this repository.

Browser sessions last 30 days. Only hashed session identifiers, account identity, expiration, and CSRF secrets are stored in owner-only files under `<home>/sessions/`; calendar tokens never go in cookies. Sign out invalidates the browser session without revoking calendar access. Reconnect through Google to repair revoked/expired calendar credentials. HTTPS through the tunnel is required for the secure cookies.

Limits on who can connect:
- **Testing mode:** Google itself only lets your listed test users sign in.
- **Published but unverified:** Google caps the app at 100 users.

**Run it as a service.** Edit the user and paths in [`deploy/calendar-blocker-onboarding.service`](deploy/calendar-blocker-onboarding.service), then:
```sh
sudo cp deploy/calendar-blocker-onboarding.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now calendar-blocker-onboarding
```
- The page listens only on `127.0.0.1:<onboardPort>` (default 8735).
- It reads server settings from the local config at startup, so restart it if `publicBaseUrl` or `onboardPort` changes. Event forms and cards read the approved config directly from GitHub.

**Expose it with a Cloudflare Tunnel:**
1. In the Cloudflare dashboard, go to **Zero Trust → Networks → Tunnels**.
   - If you already have a tunnel for this server, open it.
   - Otherwise, click **Create a tunnel → Cloudflared** and run the install command it shows on the server.
2. Add a **Public hostname**:
   - Subdomain: `calendar`
   - Domain: `jeopardy.fit`
   - Path: leave empty
   - Service: **HTTP**, `localhost:8735`
3. Make sure `publicBaseUrl: https://calendar.jeopardy.fit` is set in the config repo's `config.yaml`.

**Where tokens live.** Each person's refresh token is its own owner-only file, `~/.config/calendar-blocker/google/tokens/<email>`. Only the onboarding page writes it, by atomically replacing the whole file. Runs only read tokens and keep access tokens in memory, so the page and a run can never overwrite each other.

### Cron
```cron
0 * * * * JAVA=/usr/bin/java /path/to/calendar-blocker/run.sh >> /path/to/calendar-blocker.log 2>&1
```
- **Each run pulls first.** `run.sh` runs `git pull --ff-only` in the config checkout, so merged pull requests take effect on the next run. If the pull fails, the run is aborted.
- **Different checkout location:** set `CONFIG_REPO`.
- **Dry run:** `run.sh --dry-run` prints the plan without changing anything.
- **Failures:** a run fails fast. The first error (network, API, bad config) stops it with a non-zero exit, and nothing is retried within the run.
  - Changes already applied stay applied, and the next cron run reconciles the rest, since every run recomputes the whole plan from the calendars.
  - The one exception is an account whose login has expired or been revoked. Its requests are skipped with a warning so everyone else still gets scheduled.
- **Runs never overlap.** A run holds a lock on `<home>/run.lock`, so one started while another is in progress exits immediately. The operating system releases the lock if a run dies.
- **API usage per person, per run:**
  - one call to list their calendars
  - one batched call for the first page of every calendar's events
  - one batched call per 50 changes; unchanged events cost nothing

  Each request inside a batch still counts against Google's quota. Batching saves round trips, not quota.

## Configuration
The live config is in [dtrager02/calendar-blocker-config](https://github.com/dtrager02/calendar-blocker-config). [`config.example.yaml`](config.example.yaml) here documents every option.

Every key is validated, and unknown keys are rejected. Run `validate --config FILE` to check a file locally; the config repo runs the same check on every pull request.

| Request field | Meaning |
|---|---|
| `id` | Stable identifier that links existing blocks to the request. Renaming it re-creates the blocks. |
| `title`, `description`, `location` | Event details. `title` defaults to `id`; the other two are optional. Editing them updates every future block of the request in place on the next run. |
| `priority` | Integer, higher wins. Ties are broken by order in the file. |
| `attendees` | Emails, the first being the organizer. Defaults to `owner`. Every attendee must be onboarded, or the request is skipped. |
| `recurrence` | `daily`, `weekly` (weeks begin on `weekStart`) or `once` (requires `between: {from, to}`). |
| `duration` | e.g. `2h`, `90m`, `1h30m`. |
| `windows` | List of `{days, from, to}`. A block must fit entirely inside one window. |
| `strategies` | `{prefer-soonest: w, minimal-gaps: w}` weights. Default `{prefer-soonest: 1}`. For joint requests, `minimal-gaps` is averaged across attendees. |

## Development
```sh
./mill blocker.test
```

### Layout
- **Provider-neutral core:** `Domain`, `Periods`, `Strategies`, `Scheduler`. Pure, with no IO.
- **Provider interfaces:** `Provider.scala` defines the `CalendarProvider` and `AuthProvider` traits, plus the `Executor` that turns a plan into `BlockOp`s and hands each provider its share in one call.
- **Google implementation:** `google/`.
- **Onboarding:** `Onboard.scala`, the self-serve onboarding page (provider-neutral).
- **Local state:** `TokenStore.scala`.

### Adding a provider (e.g. Outlook)
1. Implement `CalendarProvider` and `AuthProvider` in a new package. `applyAll` receives all of a run's writes at once, so map it onto the backend's batch API if it has one (Graph `$batch`); otherwise run the operations in sequence. Map `BlockTag` onto the provider's own event metadata (Graph `singleValueExtendedProperties`, CalDAV `X-` properties, and so on).
2. Register the provider and its display name in `Main.providers`. The website automatically offers a sign-in button; OAuth state selects the provider while sharing `/callback`.
3. Refer to that provider's people as `outlook:someone@example.com` in `attendees`. `serve` exposes all registered providers; `serve --provider outlook` restricts the page to one provider.
