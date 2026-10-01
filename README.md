# Audio and Timestamp Capture

Android app for creating timestamped panel scripts from a screen and its audio.

Workflow:
1. Capture the visible screen with Android MediaProjection.
2. OCR the screen and detect numbered panel references in reading order.
3. Capture playback audio when Android permits playback capture.
4. Transcribe the spoken audio and recover timing from the actual audio.
5. Associate spoken script segments with panel references.
6. Remove reference numbers from extracted script.
7. Export a PDF in timestamp then script order.
8. Flag uncertain matches instead of silently inventing timing.
