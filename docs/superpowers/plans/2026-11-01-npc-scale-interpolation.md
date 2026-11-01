# NPC scale interpolation correction

The client-visible symptom is torso scaling before static legs, briefly separating the
body. A regression reproduces a dynamic part with four interpolation ticks and a static
part retaining ten ticks. The former visual-state packet sent scale/translation and reset
the start, but inherited each display's different duration.

Scale, restoration and retracking now send complete TRS with one explicit duration for
the whole update. Highlight-only changes send flags and leave the pose interpolation alone.
The failing regression passes after the correction. Initial and repeated native collider
mount tracking are also checked; native collision implementation is unchanged here.

Validation: 160 licensed Host tests, no failures or skips, formatting and shadow build.
The paired collision-free addon remains a00653443dbd27030317f21ad74f1d6c32e3a5b43a4703ebc75dc8747a5e0427.
Real Java-client scaling during active animation still requires acceptance.

This change is separate from the unshipped viewer-cohort/metrics performance work.
