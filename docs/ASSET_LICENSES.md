# Astravar visual assets and licenses

## Included assets

The interface uses native Android controls with independently drawn surfaces and icons. No mockup screenshot is embedded as an interactive screen.

- `drawable-nodpi/astravar_reference.jpg`: existing approved Astravar pistol art supplied by the owner. This asset is preserved; the fictional rifle in the mockups is not used.
- `drawable-nodpi/graphite_texture.png`: original seamless 384 × 384 RGB graphite grain tile, 121,155 bytes. Deterministic low-frequency periodic variation and fine grain provide depth without a large bitmap background.
- `drawable-nodpi/metal_texture.png`: original seamless 384 × 384 RGB brushed-metal tile, 105,214 bytes. Fine horizontal brushing and restrained warm gray variation are suitable for framed panels and controls.
- `drawable/arcane_geometry.xml`: original scalable, sparse engraved instrument rings, tick marks, and intersecting geometric paths.
- `drawable/ic_emblem.xml`: original compass-like Astravar instrument emblem, with bronze rings and azure centre.
- `drawable/ic_weapon.xml`, `ic_d20.xml`, `ic_core.xml`, `ic_payload.xml`, `ic_history.xml`, `ic_settings.xml`, `ic_more.xml`: original scalable navigation, roll, core, payload, and utility symbols.
- `drawable/ic_acid.xml`, `ic_cold.xml`, `ic_fire.xml`, `ic_lightning.xml`, `ic_poison.xml`, `ic_thunder.xml`, `ic_force.xml`, `ic_radiant.xml`, `ic_necrotic.xml`, `ic_psychic.xml`: original symbols for the damage types already implemented in the shared rules model. These icons do not introduce new selector availability or mechanics.
- `drawable/ic_status_ready.xml`, `ic_status_empty.xml`, `ic_status_warning.xml`: original status symbols paired with text labels in the UI.

All newly drawn textures, motifs, and vector symbols were created for this implementation. They contain no third-party icon package, game scene, fictional core catalogue, or reference screenshot. Vector strokes are scalable, and the textures are small reusable tiles rather than screen-sized allocations.

## Fonts

Cinzel provides the display typography; Inter provides functional text and numerical displays. Both fonts are distributed under the SIL Open Font License 1.1. The full notices below are also bundled in the APK as `raw/cinzel_ofl.txt` and `raw/inter_ofl.txt`. `raw/font_notices_keep.xml` prevents release resource shrinking from removing those notices.

The packaged files are static weight instances with Latin/Latin Extended, punctuation, currency, arrows, mathematical, and geometric glyphs retained. Characters absent from a bundled font use Android's normal font fallback. Inter's optical-size axis is fixed at 14 for readable interface text. The original family names are retained; neither source license declares Reserved Font Names.

| Resource | Weight | Bytes |
| --- | ---: | ---: |
| `font/cinzel.ttf` | 400 | 42,040 |
| `font/cinzel_semibold.ttf` | 600 | 42,204 |
| `font/inter.ttf` | 400 | 114,580 |
| `font/inter_medium.ttf` | 500 | 114,648 |
| `font/inter_semibold.ttf` | 600 | 115,012 |

Authoritative source downloads (retrieved 2026-10-10):

- Cinzel: https://github.com/google/fonts/tree/main/ofl/cinzel ; upstream project https://github.com/NDISCOVER/Cinzel
- Inter: https://github.com/google/fonts/tree/main/ofl/inter ; upstream project https://github.com/rsms/inter

Original variable font SHA-256 values, before static instancing and glyph subsetting:

- `cinzel`: `f4d83d34d1f6c741193e4acf4b3dff9531e5a67b6aa65228d00a7db72a4e0f34`
- `inter`: `29160a80ff49ddcab2c97711247e08b1fab27a484a329ce8b813d820dc559031`

## Cinzel — bundled copyright and license

```text
Copyright 2020 The Cinzel Project Authors (https://github.com/NDISCOVER/Cinzel)

This Font Software is licensed under the SIL Open Font License, Version 1.1.
This license is copied below, and is also available with a FAQ at:
http://scripts.sil.org/OFL


-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded, 
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
```

## Inter — bundled copyright and license

```text
Copyright 2020 The Inter Project Authors (https://github.com/rsms/inter)

This Font Software is licensed under the SIL Open Font License, Version 1.1.
This license is copied below, and is also available with a FAQ at:
https://scripts.sil.org/OFL


-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded, 
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
```

## Integrated weapon cutout

`drawable-nodpi/astravar_cutout.webp` is a transparent derivative of the owner-supplied `01_Astravar_Weapon_Art_Reference.png`. The built-in image generation editor was used only for background extraction, with the explicit instruction to preserve the pistol silhouette, perspective, bronze/gunmetal parts, grip, elemental dial, runes and blue chamber. The result was visually compared to the supplied image before integration. It is a real illustration asset, never a baked UI or mockup. The original approved `astravar_reference.jpg` remains in source. The cutout is downsampled to a 1024-pixel maximum edge and encoded as WebP with preserved alpha for Android.

Edit prompt: “Remove only the surrounding black/dark studio backdrop and floor. Preserve the exact pistol design, silhouette, camera perspective, grip, muzzle, every bronze/gunmetal mechanical part, engraved runes, elemental selection dial and luminous blue energy chamber. Output one cutout on transparent background; no new scene or text.”
