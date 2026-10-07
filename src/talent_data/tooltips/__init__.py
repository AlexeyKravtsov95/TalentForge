"""Client-rendered talent tooltips: capture manifest, raw capture import and analysis.

M5.V2A is the evidence slice. The exact game client renders every talent tooltip
(``C_TooltipInfo.GetTraitEntry``); this package generates what the capture addon asks
for, imports what it captured as a build-pinned audit artifact and describes its
structure. It deliberately defines no mobile tooltip contract - that is derived from the
real capture in M5.V2B.
"""
