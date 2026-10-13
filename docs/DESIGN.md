# Implemented Android design

Native Kotlin / Jetpack Compose / Material 3. Direction **D, "Codex mobile, monochrome"**, approved by the owner on 2026-10-08. It replaces the graphite/lime direction. References are the Codex tab of the ChatGPT app and Telegram's list density. The app is a quiet instrument for sending tasks to a desktop agent. It is not a dashboard, not a WebView and has no brand colour. It has no gradients, glow, glass, cards in lists, avatars, attachment controls, typed tool icons or invented durations.

## Principles

1. **Ink on paper.** The ground is near-black in dark and white in light. Text is the only "decoration".
2. **One filled control.** The primary action (new task, send, connect, create) is the inverse of the ground: a white circle with a black glyph in dark, a black one with a white glyph in light. Everything else is outline, text or hairline.
3. **Colour is a status, never a style.** Colour appears only in small glyphs: the green dot for online, amber for offline/warning or "waiting", red for revoked/incompatible/errors. A running task uses a neutral spinner.
4. **System theme by default.** Both themes are designed, not derived.

## Tokens

| Role | Dark | Light |
| --- | --- | --- |
| background / surface | `#0B0B0C` | `#FFFFFF` |
| surfaceContainer low / · / high / highest | `#131315` / `#18181B` / `#202023` / `#2A2A2E` | `#F7F7F8` / `#F2F2F4` / `#ECECEF` / `#E4E4E8` |
| onSurface / onSurfaceVariant | `#F2F2F3` / `#9C9CA3` | `#0D0D0F` / `#65656C` |
| outlineVariant (hairlines) | `#26262A` | see Color.kt |
| action / onAction | `#F2F2F3` / `#0B0B0C` | `#0D0D0F` / `#FFFFFF` |
| success (online dot) | `#4CC38A` | `#1F8A55` |
| warning / container | `#E3B566` / `#2A2417` | `#8A5A00` / `#FBF0DA` |
| error | `#FF7B72` | `#C0362C` |

All roles live in [Color.kt](../android/app/src/main/java/dev/dshmobile/app/ui/theme/Color.kt). `primary` equals `onSurface`, so Material defaults stay monochrome. Surface tint is transparent and there is no dynamic wallpaper colour. Text pairs meet WCAG AA (≥4.5:1). onSurfaceVariant on the ground is about 7:1 in dark and 5.9:1 in light.

## Typography

[Type.kt](../android/app/src/main/java/dev/dshmobile/app/ui/theme/Type.kt) uses bundled **Geist** (Regular/Medium/SemiBold, Cyrillic included) and **Geist Mono** under SIL OFL 1.1 ([licence](../android/app/src/main/res/raw/geist_license.txt)). The scale is headlines 28/24/22sp, titles 20/16/14sp, body 16/14/13sp and labels 14/13/12sp, with slight negative tracking on headlines. All sizes follow the system font scale. Mono is used only for data: code, Markdown tables, addresses, fingerprints and the agent's raw activity text. Times use tabular figures (`tnum`).

Spacing follows a 4/8dp rhythm with 20dp gutters. Touch targets are at least 48dp. Shapes are 6/10/14/20/26dp, and fields and primary buttons are pills.

## Icons and motion

[MobileIcons.kt](../android/app/src/main/java/dev/dshmobile/app/ui/MobileIcons.kt) holds authored 24dp outline vectors with a 2-unit stroke and round caps. Filled exceptions are the stop square and the "more" dots. There is no icon dependency.

Motion:
- The only continuous motion is the running spinner ([StatusDot.kt](../android/app/src/main/java/dev/dshmobile/app/ui/components/StatusDot.kt)).
- The activity chevron rotates.
- Predictive Back uses a small scale/fade.
- With system animations disabled, the spinner is static.

## Screens

- **Home.**
  - The top bar has Settings on the left and "DSH · ● state" in the centre. The whole title block is the connection affordance (`connection_state`). Search is a toggle on the right.
  - Projects are text tabs with a 2dp underline, not chips.
  - Sessions sit in plain date sections. Each row has a spinner (running) or a hairline ring (idle), a two-line title, a tabular time and a grey "project · status" line.
  - A bottom pill reads "Новая задача для компьютера…" and ends in a round action button. It opens the New task sheet.
- **Chat.**
  - A centred title and a grey project line. When the connection is not online, the project line becomes the coloured connection state, and tapping the title opens the connection details.
  - Search and Refresh are in the "more" menu.
  - User messages are right-aligned `surfaceContainerHigh` bubbles. Assistant output is unboxed native Markdown with no mark or role header; system notices keep a small label. Copy and time sit under each message.
  - Agent service messages collapse into "Действия агента · N ›". When expanded, they show a hairline rail, one line per item with its time, and the full mono text in a dialog.
  - While a run is active, a status line shows a spinner and "Работает · N мин". This is an estimate from the last task message, because the protocol has no run-start time.
- **Composer.** A pill field (up to six lines) with the actions inside it: the outlined stop while running (confirmed by a dialog) and the round send. Existing gates are unchanged: online, capability, executable scope, known activity, 32 KB, busy and unresolved delivery.
- **New task sheet.**
  - Project rows show a folder, name, grey count and a check on the selected row. Read-only projects are visible but disabled.
  - Optional preset pills. The selected preset is filled with ink.
  - A multiline "Что сделать?" field and a full-width pill button.
  - Empty text keeps the old "Создать" behaviour. With text, the button reads "Отправить": the app creates the chat and, only after a confirmed creation, puts the text into the new chat's draft and sends it once.
  - If the send fails or is not possible, the text stays in the draft and the chat shows "Чат создан, но задача не отправлена…". An uncertain send appears in the existing delivery panel. Nothing is resent automatically, and an uncertain creation never sends.
- **Settings.**
  - Flat sections separated by hairlines, with no cards.
  - The computer identity comes first, with its connection state, followed by Connection, Theme, Device and the forget action.
  - Theme is a three-way segmented control: System / Light / Dark.
- **Pairing.**
  - A centred "DSH" bar, a round computer glyph, a heading and outlined step numbers.
  - The primary scan button uses the action colour; import and preview are outlined pills.
  - Trust review shows identity blocks in Geist Mono.
  - The scanner viewfinder uses white corners with a dark halo, legible over any camera image.

Behaviour, copy, IA, gates, secure storage and tags are unchanged from the previous design, except for the New task text field and the chat "more" menu (`chat_more`). The test tags `search_history` and `refresh_chat` are now menu items.

## Native Markdown and safety

[MarkdownParser.kt](../android/app/src/main/java/dev/dshmobile/app/ui/markdown/MarkdownParser.kt) parses:
- ATX headings 1–3, paragraphs, bold/italic and inline code;
- fenced code with partial-input state;
- bullets/numbers with one nested indent;
- blockquotes, rules and simple GFM tables.

The [renderer](../android/app/src/main/java/dev/dshmobile/app/ui/markdown/MarkdownRenderer.kt) uses selectable Compose text only:
- HTML stays literal. Links are inert underlined text, with no intents.
- Code and tables scroll horizontally.
- Parsing is linear and bounded: 131,072 characters, 512 blocks and 12×64 table cells. Messages up to 8,192 characters parse synchronously; longer ones parse on `Dispatchers.Default`.

This is a small supported subset, not a CommonMark compliance claim.

## Screenshot stages

The opt-in `redesignCapture` stage of [MobileAcceptanceTest](../android/app/src/androidTest/java/dev/dshmobile/app/acceptance/MobileAcceptanceTest.kt) and [tools/android-redesign-capture.ps1](../tools/android-redesign-capture.ps1) capture these names against the synthetic multi-project fixture only, on a disposable emulator:
- `pairing-dark`
- `home-dark`, `home-dark-en`, `home-project`, `home-light`, `home-font130`
- `chat-markdown-running`
- `new-chat-sheet`
- `settings-dark`

The stage never sends, creates, cancels or forgets. Synthetic screenshots illustrate the interface; they are not physical-device acceptance.

## Accessibility

- `connection_state` keeps its enum `stateDescription` and polite live region on Home and Chat.
- Headings, radio and tab semantics, meaningful icon descriptions and text-labelled states are preserved; colour is never the only signal.
- The new task button is a single labelled button that is disabled when creation is impossible.
- The "not sent" notice is a polite live region.
- Font scale up to 2.0, IME insets and predictive Back are part of the design.
- TalkBack, physical devices and mobile networks remain separate operator gates.
