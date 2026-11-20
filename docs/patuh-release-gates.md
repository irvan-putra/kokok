# Kokok release consistency checks

Main pushes run the pinned Patuh source gate. A manual Patuh workflow run also verifies the currently published release. These checks do not publish an APK or write an alarm feed.

The existing build workflow keeps its qualification jobs. An explicit nonempty release-version dispatch now requires the source gate and unit tests before building a signed candidate. The candidate APK and SHA256SUMS are retained as an artifact. Patuh checks that candidate's actual packaged icon/version before the publish job can upload those same bytes and checksum. A final check verifies the published release and gallery.

The post-publication check may find a missing or stale gallery card after an APK has already published. Fix the catalog and rerun verification, do not rebuild unchanged signed bytes solely for the catalog. A green automated check does not replace packaged-icon visual inspection or prove physical-phone alarm reliability. Signed-upgrade evidence remains a separate release requirement.

Wiring these workflows changes no app code, permissions, signing keys, release versions, existing release assets, alarm feed or phone state. No release or feed workflow was dispatched to install this wiring.
