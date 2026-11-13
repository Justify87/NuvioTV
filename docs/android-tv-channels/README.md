# Android TV: three Nuvio preview channels

Base: Nuvio's existing cached Continue Watching / Next Up pipeline. Target: Android TV 11 (API 30), including SHIELD TV Pro (2019). **No launcher-specific dependencies or Plume changes.**

## Published channels

| Channel | Stable provider suffix | Contents | Click |
| --- | --- | --- | --- |
| Continue Watching | (legacy package name, unchanged) | Unfinished films/episodes | Play, or detail if no video |
| Nuvio · Upcoming | `:upcoming` | Next episode whose release date is in the future / not yet aired | Detail only |
| Nuvio · Available Episodes | `:available` | Next episode already aired (including newly released episodes) | Play, or detail if no video |

Existing Continue Watching channel IDs and user approval are preserved through the original DataStore key; the two new channels have their own IDs. Secondary channels are discoverable in the launcher channel settings, and the app avoids multiple automatic authorization dialogs.

- Filters: the configured Continue Watching history age limit, dismissed next-up entries, one item per series, and unfinished content taking priority over Next Up.
- Release transition: an exact release timestamp is checked again on each reconciliation even if cached `hasAired` was stale. Date-only information becomes available **after** the release day, not during it (the precise release time is unknown). A known future timestamp takes priority over a coarse date.
- Ordering: unfinished items by last watched, upcoming by earliest release, available by the Next Up sort timestamp.
- Background behavior: the existing periodic job only reprocesses the **local cache** (without requiring internet). It does not fetch new provider metadata. Android JobScheduler is inexact; release-date changes can be delayed. The old network-constrained job is migrated.
- Reset/profile: channel entries are replaced on reconciliation with the active profile's cached snapshot; sign-out/account reset clears programs from all three channels.
- Watch Next remains a separate Android system feed and contains **only playable** items. A launcher combining Watch Next with the custom channels may need to deduplicate them.

## Program data written to Android TV

Standard `PreviewProgram` fields:

- `title`, `type`, `episode_title`, `season_display_number`, `episode_display_number`
- `short_description`: localized status, e.g. Next Up, New Episode, New Season, or In N days
- `long_description`: cached episode description, when available
- `release_date`: ISO `yyyy-MM-dd`, if known, usable by a launcher to calculate its own up-to-date countdown
- `poster_art_uri`, `logo_uri`, `poster_art_aspect_ratio`, image preference inherited from Nuvio
- `duration_millis`, `last_playback_position_millis` for started items
- `weight`, `last_engagement_time_utc_millis`, stable `internal_provider_id`
- `intent_uri`: `stream` for playable items, `detail` for not-yet-released items

A preview channel is a **data source, not a custom UI widget**. The operating system and launcher decide which standard fields to display; the status text is not a dedicated Android badge field. The countdown string can be stale during standby, while the ISO release date remains authoritative.

## Validation

1. Install an APK with the same application ID over the existing Nuvio installation. Verify the legacy Continue Watching channel is reused.
2. On a Shield TV Pro with Android TV 11, open the launcher's TV channels/rows settings and enable both new Nuvio channels.
3. Start a film and partially watch a series episode. Confirm the progress row and return-to-stream.
4. Check an upcoming episode for title, season, episode, description, date and detail-page action.
5. Confirm an already available episode appears only in Available Episodes and a newly published episode displays a New Episode/New Season text when flagged.
6. Verify a countdown changes after time/date rollover and the record switches from Upcoming to Available after refresh.
7. Check account reset, profile switch, dismissal, history cap, app background/foreground and offline-cache behavior.
8. Check Android's separate Watch Next row for duplicates when the launcher combines sources.

Automated JVM test: `TvChannelFeedBuilderTest`. Existing manager tests cover reconciliation, stable program IDs and removal. A device/provider integration test remains necessary to confirm OEM rendering and permissions.
