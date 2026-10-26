# AI collaboration guide — VisionClaw

This repository is the shared source of truth for collaboration between the owner and AI coding assistants (ChatGPT, Muse, Grok, and other tools the owner chooses).

## Owner's requirements
- Target: a REAL working Android application on Xiaomi 14 with Ray-Ban Meta Gen 2.
- Prefer improving the existing app at `samples/CameraAccessAndroid/`, NOT creating separate replacements without asking the owner.
- No simulators or phone-camera substitutions when claiming a Ray-Ban camera test.
- Prioritize minimal mobile-only steps for the owner; avoid paid services, subscriptions or charges without explicit informed approval.
- Preserve existing features and other projects in this monorepo; the `morethantiles-inspector/` project is unrelated.

## Known owner-reported problems (NOT verified resolved)
1. App reportedly closes on launch on Xiaomi.
2. Ray-Ban Meta connection and camera capture reportedly fail.
3. OpenClaw / WebRTC and voice integration are not confirmed working end-to-end.
Do not present a successful build, code edit, Bluetooth pairing, or simulated camera input as proof these problems are solved.

## Source-of-truth checks
- Android code: `samples/CameraAccessAndroid/`.
- Real-device test guide: `samples/CameraAccessAndroid/MUSE_RAYBAN_PRUEBA.md`.
- CI APK workflow: `.github/workflows/android-apk.yml`.
- The workflow requires the GitHub Actions secret `META_PACKAGES_TOKEN`; if unavailable, report the blocker without requesting anyone to commit a secret.
- README documents gateway and agent/LiveKit dependencies; verify which architecture the current Android app actually uses before changing it.

## Working protocol (for ANY AI, including Muse or Grok)
1. Read this file, the relevant app files, and recent open issues/PRs before starting.
2. Record the intended change and the observed error in a GitHub issue or PR; label findings as observed, reproduced, or unverified. Do not claim the other agents have seen messages unless their action confirms it.
3. For code edits use a dedicated branch named `ai/<assistant>-<task>` and a pull request into `main`. Do not push competing code edits straight to `main`; avoid modifying the same files concurrently. Existing code and owner instructions take precedence.
4. In the PR include: aim, changed paths, build/test evidence (logs or links), physical-device checks still required, blockers, privacy/cost impact, and next action for another assistant.
5. Never commit passwords, access tokens, keys, user photos, footage, private data, or credentials; use GitHub Actions secrets only where necessary.
6. Run tests/builds if possible; when unable, say explicitly what was NOT tested. Do not merge until reviewed or authorized according to the owner's instructions.
7. Keep replies to the owner in Spanish when possible, with actionable short updates. The owner chooses features and priorities; assistants recommend implementations.

## Initial joint task
Produce a diagnostic baseline, not a redesign:
- Check Android app initialization/crash paths and compile logs.
- Confirm Meta DAT SDK version, actual hardware camera stream, permissions, and model/gateway configuration.
- Distinguish Bluetooth pairing from actual DAT device registration and received video frames.
- Repair startup first, then hardware connectivity, then real camera frames, then microphone, speech output and AI conversation.
- Preserve a no-cost path wherever technically possible; do not promise external model APIs are free.

This file is a collaboration protocol, not an automated agent-to-agent connection. Every assistant needs separately configured repository access and must be invoked through its own tool or workflow.
