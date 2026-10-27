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
| `~/.config/calendar-blocker/google/` | Written by the app: tokens and per-user state. **Private.** | `--home DIR` |

Never put secrets in the config repo.

```sh
./mill blocker.assembly                       # builds out/blocker/assembly.dest/out.jar (needs JDK 17+)
git clone https://github.com/dtrager02/calendar-blocker-config.git ~/.config/calendar-blocker-config
mkdir -p ~/.config/calendar-blocker && chmod 700 ~/.config/calendar-blocker
cp client_secret_*.json ~/.config/calendar-blocker/credentials.json
chmod 600 ~/.config/calendar-blocker/credentials.json
java -jar out/blocker/assembly.dest/out.jar validate   # checks the config
```

### Exposing onboarding (Cloudflare Tunnel)
`onboard` starts a small HTTP listener on `127.0.0.1:<onboardPort>` (default 8735). It runs only while an invite is pending, and it never listens on a public interface. A Cloudflare Tunnel gives it a public HTTPS hostname without opening any ports:

1. In the Cloudflare dashboard, go to **Zero Trust → Networks → Tunnels**.
   - If you already have a tunnel for this server, open it.
   - Otherwise, click **Create a tunnel → Cloudflared** and run the install command it shows on the server.
2. Add a **Public hostname**:
   - Subdomain: `calendar`
   - Domain: `jeopardy.fit`
   - Path: leave empty
   - Service: **HTTP**, `localhost:8735`
3. Make sure `publicBaseUrl: https://calendar.jeopardy.fit` is set in the config repo's `config.yaml`.

While no `onboard` command is running, the hostname shows a Cloudflare error page. That's expected.

### Onboarding a person (yourself first)
```sh
java -jar out/blocker/assembly.dest/out.jar onboard --email b@gmail.com [--timeout 1h]
```
1. The command prints a one-time invite link. Send it to the person.
2. They open it, sign in with that Google account, and approve.
3. The app checks that they signed in as the invited address and that they granted both permissions. It then stores the token and creates their Auto Blocks calendar.
4. The command exits once the invite is used, or when the timeout passes.

### Cron
```cron
0 * * * * JAVA=/usr/bin/java /path/to/calendar-blocker/run.sh >> /path/to/calendar-blocker.log 2>&1
```
- **Each run pulls first.** `run.sh` runs `git pull --ff-only` in the config checkout, so merged pull requests take effect on the next run. If the pull fails, it warns and uses the current checkout.
- **Different checkout location:** set `CONFIG_REPO`.
- **Dry run:** `run.sh --dry-run` prints the plan without changing anything.
- **Exit code:** a run exits non-zero if any calendar operation failed.

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
- **Provider interfaces:** `Provider.scala` defines the `CalendarProvider` and `AuthProvider` traits, plus the `Executor` that routes plan operations.
- **Google implementation:** `google/`.
- **Onboarding:** `Onboard.scala`, the invite listener (provider-neutral).
- **Local state:** `TokenStore.scala`.

### Adding a provider (e.g. Outlook)
1. Implement `CalendarProvider` and `AuthProvider` in a new package. Map `BlockTag` onto the provider's own event metadata (Graph `singleValueExtendedProperties`, CalDAV `X-` properties, and so on).
2. Register the provider in `Main.providers`.
3. Refer to that provider's people as `outlook:someone@example.com` in `attendees`, and onboard them with `--provider outlook`.
