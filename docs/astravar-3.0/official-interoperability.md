# D&D Beyond interoperability investigation

Research date: 7 November 2026. Scope: official, publicly published sources; no private character endpoints were called, no credentials were accessed, and no content was copied from a paid compendium. Live interface findings are maintained separately.

## Engineering decision

Astravar should remain a local, standalone character manager. Provide a user-selected character-file import with preview, field-level reconciliation, retained source bytes, and explicit confirmation before applying mechanics. An optional user-initiated browser link can open D&D Beyond. Do not label either operation synchronization.

No current, supported public developer API contract or third-party OAuth authorization contract was verified in this investigation. This is a bounded research finding, not proof that no commercial partnership or future API exists. Do not build against endpoints discussed by forum users.

## Supported paths and boundaries

| Capability | Evidence | Astravar consequence |
|---|---|---|
| Character PDF export | Official support documents a form-fillable export from the builder's What's Next section or the character menu's Export to PDF action. [S1] | Accept files explicitly supplied by the user; inspect form fields locally and present an import preview. Preserve unknown and missing values. |
| JSON export / API | The official June 2020 changelog says old character routes were never publicly supported and describes a public API as a future possibility. It is historical evidence, not a current API guarantee. [S2] | No polling, scraping, credential extraction, or undocumented endpoint adapter. Current supported API availability remains unverified. |
| Campaign content sharing | Official help describes Master-tier campaign sharing and character source eligibility. [S3] | This is a D&D Beyond campaign capability. It does not establish permission to redistribute books or provide an external app API. |
| Account sharing | Current terms §2.2 say accounts and access are personal. [S4] | Astravar must never request the user's password or copy browser session credentials. |
| Native web navigation | Android Custom Tabs are browser-backed UI launched by an app; browser state remains managed by that browser. [S5] | Offer an HTTPS link or Custom Tab as optional navigation. It supplies neither character-data readback nor synchronization. |
| Rules content | Official SRD page provides separately licensed SRD 5.1 and 5.2.1, under CC BY 4.0. [S6] | Pin mechanics by source/version and include required attribution for any bundled SRD-derived text. Do not treat all D&D Beyond content as SRD. |

## Terms and content handling

The terms page states an update date of 25 June 2026. Sections 5.2 III, V, and VI restrict reverse engineering, unauthorized automation/extraction tools, protocol emulation, and nonpublic access. Section 7 distinguishes user-owned creations from platform content and limits the platform's sharing grant to its services. Purchased digital content carries a limited personal license; purchase is not ownership of the underlying material. [S4]

Implementation should therefore use the owner's supplied character facts and original homebrew, properly licensed rules, and reviewed local imports. A PDF export is a documented user workflow; its existence is not a blanket license to bundle its third-party prose, illustrations, or paid rules in a distributed app. Keep imported documents private and avoid copying compendium text into repository fixtures.

## Import adapter acceptance requirements

These are engineering recommendations, not claims of completed functionality:

1. Use Android's document picker; limit file size and MIME types; parse without executing embedded PDF JavaScript.
2. Prefer named form fields. Treat text extraction and OCR as uncertain evidence rather than authoritative mechanical effects.
3. Preserve the input document, its hash, and field mappings outside normal gameplay screens. Keep original stable record IDs during reconciliation.
4. Show permanent score, effective score, manual modifier, and source separately. Never infer an item effect from descriptive prose alone.
5. Do not overwrite HP, spent slots, currency, attunement, or homebrew from an older export without an explicit reviewed decision. Unknown is not zero.
6. Apply accepted changes transactionally with a pre-import backup, revision check, audit receipt, and rollback record.
7. Include no D&D Beyond account token, background fetch, or automatic web extraction. If a documented API is later authorized, isolate it behind a separate adapter and consent boundary.

PDF coverage must be measured with the user's actual export. The official page does not promise a stable machine-readable schema or complete representation of homebrew, item effects, roll history, current resources, or all application settings.

## Sources

- **S1:** D&D Beyond Support, “Export Sheet,” updated 17 September 2025; accessed 7 November 2026. https://dndbeyond-support.wizards.com/hc/en-us/articles/7747238449556-Export-Sheet
- **S2:** D&D Beyond, “Character Sheet Changelog — Early June Updates,” 8 June 2020, “Character Service API Removal”; accessed 7 November 2026. https://www.dndbeyond.com/old-changelog/827-character-sheet-changelog-early-june-updates
- **S3:** D&D Beyond Support, “Campaign Content Sharing and You,” updated 17 September 2025; accessed 7 November 2026. https://dndbeyond-support.wizards.com/hc/en-us/articles/7747210355604-Campaign-Content-Sharing-and-You
- **S4:** D&D Beyond Terms & Conditions, last updated 25 June 2026, §§1.4, 2.2, 5.2, 7; accessed 7 November 2026. https://www.dndbeyond.com/en/terms-conditions
- **S5:** Chrome for Developers, “Overview of Android Custom Tabs”; accessed 7 November 2026. https://developer.chrome.com/docs/android/custom-tabs
- **S6:** D&D Beyond, “System Reference Document,” lists SRD 5.2.1 and previous SRD releases; accessed 7 November 2026. https://www.dndbeyond.com/srd

## Unverified items

- Any supported third-party character-data API, OAuth scope contract, or official JSON round-trip format.
- Completeness or field naming of the owner's current PDF export.
- Live character-sheet and export interaction sequences; see the separate hands-on report.
- Physical-device Custom Tab behavior and D&D Beyond session continuity.

