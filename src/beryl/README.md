# Local NeoForge Beryl adaptation

Derived from the user-supplied `beryl_26.1.2-0.2.1-alpha` artifact by Collateral.
Beryl's bundled Java classes were decompiled with Vineflower 1.11.2 and adapted
for this NeoForge VulkanMod fork. The original bundled shader assets are retained.
Beryl is All Rights Reserved; this source set is separate from VulkanMod's LGPL
sources and produces a separate companion jar. No redistribution permission is
implied by this local adaptation.

Build both local jars with `./gradlew assemble`. The `berylJar` task builds only
the companion jar. Both VulkanMod and Beryl jars are needed in a NeoForge instance.
`./gradlew runClient` loads both source sets in the development instance.

Beryl's settings page is registered in VulkanMod's settings menu; R is the default
shader toggle. Settings are saved in `config/beryl_settings.json`. The former
Iris implementation remains in the main source tree but is not initialized.
