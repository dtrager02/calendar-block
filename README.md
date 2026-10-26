# calendar-blocker

A cron-run script that reads the Google Calendars of everyone who has onboarded, including the calendars they subscribe to. It then places blocks of time for the activities declared in `config.yaml`, on a dedicated **Auto Blocks** calendar it creates for each person.

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
4. **Google Auth Platform → Branding → Authorized domains:** add your domain.
5. **Clients:** open a client of type **Web application**. Under **Authorized redirect URIs**, add `https://<your-domain>/calblock/callback`. Download the JSON.

### Server
```sh
./mill blocker.assembly                       # builds out/blocker/assembly.dest/out.jar (needs JDK 17+)
mkdir -p ~/.config/calendar-blocker && chmod 700 ~/.config/calendar-blocker
cp client_secret_*.json ~/.config/calendar-blocker/credentials.json
chmod 600 ~/.config/calendar-blocker/credentials.json
cp config.example.yaml ~/.config/calendar-blocker/config.yaml   # then edit
```
Route `/calblock/*` from your HTTPS reverse proxy to the onboarding port (default 8735), preserving the path:
```
# Caddy
reverse_proxy /calblock/* 127.0.0.1:8735
# nginx
location /calblock/ { proxy_pass http://127.0.0.1:8735; }
```
The listener runs only while an `onboard` command is waiting. At all other times the proxy returns 502.

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
`run.sh --dry-run` prints the plan without changing anything. A run exits non-zero if any calendar operation failed.

## Configuration
See [`config.example.yaml`](config.example.yaml). Every key is validated, and unknown keys are rejected.

| Request field | Meaning |
|---|---|
| `id` | Stable identifier that links existing blocks to the request. Renaming it re-creates the blocks. |
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
