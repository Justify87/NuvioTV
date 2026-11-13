# Android TV: classic channel plus three optional channels

**Baseline:** the original Nuvio `dev` Android TV Continue Watching channel. **Target:** Android TV 11 (API 30), including NVIDIA Shield TV Pro (2019). **No Plume or launcher-specific API changes.**

## User-visible channel contract

| Channel | Provider ID suffix | What appears | Opening a card | Default |
| --- | --- | --- | --- | --- |
| **Continue Watching** (classic, unchanged) | `""` (exact original package name) | A **single mixed row** of unfinished movies/episodes and cached already-aired Next Up episodes | Original stream intent when video is present | **Existing original channel** |
| **In Progress** (new) | `:in-progress` | Only unfinished movies/episodes | Play (details if no video ID) | Opt-in via launcher |
| **Upcoming** (new) | `:upcoming` | Next episodes not yet aired | **Details**, never start a future episode | Opt-in via launcher |
| **Available Episodes** (new) | `:available` | Next episodes already aired (not only *recent* releases) | Play (details if no video ID) | Opt-in via launcher |

All four are ordinary Android TV `Preview Channel` objects. There is **no Android TV platform limit of three** channels. Nuvio can register the three additions, but cannot unilaterally force the launcher to show or order them. An Android TV launcher may merge multiple channels or offer individual toggles.

### Classic compatibility guarantee

The original Continue Watching channel keeps **its original preference key, provider ID, display name, channel ID, approval state, list membership, filters, global mixed sort and deep links**. Do **not** build the classic feed by concatenating three categorized feeds. Instead, `TvChannelFeedBuilder.buildLegacy()` reproduces the pre-feature `AndroidTvChannelSyncService.buildChannelItems()` algorithm, including:

1. Progress items limited by the existing watching-days cap
2. Next Up included **only when cached `hasAired == true`**
3. Next Up filtered by the same days cap and dismissal key
4. Unfinished series taking priority over Next Up for the same `contentId`
5. One mixed list sorted by descending `lastWatched` / `sortTimestamp`, followed by `distinctBy(contentId)`

The existing `TvRecommendationManager.updateWatchNext()` receives **the original mixed selection and ordering**. The three new channels do not change the system Watch Next source.

Existing custom poster URL patterns, image choice, progress/duration fallback and per-profile CW cache continue to be reused. The classic channel additionally benefits from the new standard metadata mapping (episode descriptions and release dates where known), richer fingerprint comparisons and synchronization serialisation, without changing its row title or adding an extra status label.

### New channel details

- **In Progress:** unfinished items sorted by latest watch time
- **Upcoming:** earliest release first; known release instant or date determines current classification; future cards open series details
- **Available Episodes:** all next watchable/available episodes, latest Next Up sort timestamp first, with localized `New Episode` / `New Season` / `Next Up` descriptions
- **Priority:** started content suppresses a duplicate next-up entry for the same series **among the new channels**; the classic channel independently reproduces its original selection
- **Filtering:** the existing days cap and dismissed-next-up keys affect both classic and new feeds
- **Known limitation:** the cache is populated by Nuvio's Home pipeline. If the setting to hide unaired Next Up removes future episodes *before cache writing*, Upcoming cannot publish them. The launcher integration cannot recover entries that aren't in the cache
- **Release timing:** the new channel classifier reuses Nuvio's existing release-date parser (exact instants and UTC midnight for date-only values). The classic channel intentionally continues using cached `hasAired`; that means the two may briefly differ across a release boundary
- **Background:** the scheduler re-evaluates **local cache** without requiring an internet connection; it does not refresh remote release metadata. Jobs can be delayed by Android during standby

### Android TV fields published

For all feeds where source data is available: `title`, `type`, `season_display_number`, `episode_display_number`, `episode_title`, `poster_art_uri`, `logo_uri`, `poster_art_aspect_ratio`, `weight`, stable `internal_provider_id`, `intent_uri` and `last_engagement_time_utc_millis`.

Additional metadata (including the unchanged classic channel): `long_description` (cached episode synopsis), `release_date` (normalized yyyy-MM-dd) when available; position and duration are published only for in-progress entries (including the pre-existing synthetic progress fallback). The classic channel still has no added short status text to avoid changing its existing appearance. New Upcoming/Available channels publish `short_description` with status/countdown text.

This data is **not a custom Android TV widget**. A launcher may omit descriptions, release dates, and images even when Nuvio publishes them. In particular, the public Plume Launcher currently reads only some of these fields; it needs its own future work to display status badges/countdowns.

### Activation, migration and sign-out

- On an update, the original channel is reused from the same `continue_watching_channel_id` preference. Its provider ID remains precisely `context.packageName`. A new install uses the old channel request-for-browsable behavior.
- Three additional IDs are stored separately as `in_progress_channel_id`, `upcoming_channel_id`, and `available_channel_id` and can be independently enabled in TV home settings. Nuvio does not delete or rename the classic channel.
- Sign-out/account reset clears **programs from all four** registered channels, but retains channel registrations, matching the old channel's behavior.
- Switching profiles reuses the already active profile's cache and re-synchronizes all four feeds. A provider/launcher integration check is needed to verify refresh timing on an actual device.
- A user who enables both classic and categorized channels will see some titles twice across separate rows **by design**. The launcher can simply keep the unwanted channel turned off.

### Verification

Automated: `TvChannelFeedBuilderTest` includes legacy order/selection/metadata compatibility tests and new-category classification cases. `AndroidTvChannelManagerTest` covers the existing channel as well as four-channel publishing. PR CI runs these tests and builds `assembleFullDebug`.

Manual Shield Android TV 11 test checklist:
1. Upgrade an existing Nuvio installation, confirm the original channel row remains visible **with the same entries/order** and still opens playback
2. In the TV launcher channel settings, enable *In Progress*, *Upcoming*, and *Available Episodes*
3. Confirm In Progress displays progress bars and resumes playback; Upcoming shows an upcoming episode and opens details; Available Episodes can play an aired next episode
4. Check episode title, S/E, images, synopsis, localized date/countdown when supported by the selected launcher
5. Confirm a released item moves from Upcoming to Available only after the cache re-evaluation, without altering the classic channel's cached `hasAired` rule
6. Confirm that when both the classic and separate channels are enabled, duplicates across different rows are expected, not duplicate provider rows
7. Verify dismissal, age cap, profile switching, logout/re-login, background updates and the separate Android Watch Next list
