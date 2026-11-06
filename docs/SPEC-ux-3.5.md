# Zapper 3.5: navigation UX

Design input: frontend-design guidance, plus the ui-ux-pro-max rules that apply to a 10-foot TV UI.
Those are: visible 3:1 focus on every control, no emoji as icons (vector icons instead), predictable
Back, loading feedback, and thousands separators. Its web palette and landing-page patterns were
not used. Zapper keeps its indigo/marigold/Lexend system.

## Structure
```
┌───────────────────────────────────────────────────────────────┐
│ [▣ Live TV]  [🎞 Movies]  [▤ Series]        [? Help]   9:41 PM │  ← section tabs (▲ from any list)
├──────────────┬────────────────────────────────────────────────┤
│ ⟲ Recently   │  12  [GT]  Global Toronto  [4K]                 │
│ ★ Favorites  │      Now: The National · until 10:00            │
│ ☰ All chan.  │  13  [CM]  CTV Montreal  [HD]                   │
│ ──────────── │                                                 │
│ English      │                                                 │
│ Sports …     │                                                 │
└──────────────┴────────────────────────────────────────────────┘
```
- Live TV, Movies and Series are separate sections, no longer mixed into the channel categories.
  OK on Movies or Series opens that section straight away.
- **Recently watched** (last 12 channels, newest first) sits above Favorites. Zapping from it
  follows channel numbers, so the list doesn't reshuffle under you.
- Movies / Series show the same tab row (Live TV returns to TV). **Continue watching** is the first
  row and the default. Focus lands on the first poster, so a single OK resumes. Series show one
  tile per show: the latest episode, labelled "Show · S2 E4". Finished titles drop off.
- Icons: Material vector icons tinted by focus state. Focus stays a solid marigold fill with dark
  text everywhere.
