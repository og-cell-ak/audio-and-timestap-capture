# Timestamp Genius

Timestamp Genius follows a script while the phone plays audio or video. It captures device playback audio, reads visible script text, matches speech to script lines, records the finish time for every yellow line, and writes the result directly into a PDF.

## Included functionality

1. Kotlin and Jetpack Compose main UI.
2. Floating overlay with START, STOP, SAVE and SET LINES.
3. Large, tappable TG floating control with reliable touch handling and horizontal/vertical drag repositioning.
4. Default five yellow guide lines with equal spacing.
5. Fully interactive layout editor: move and resize the outer guide, select individual lines, unlock them, drag them left/right/up/down, resize width/height, change corner shape, adjust line count, set scroll speed, reset, save and close.
6. The saved per-line positions and sizes are used by both the visible guide and OCR row detection.
7. Scroll speed 0 through 9.
8. Screen OCR using ML Kit, including Devanagari support.
9. Offline Vosk speech recognition using bundled English and Hindi models.
10. Playback capture through MediaProjection and AudioPlaybackCaptureConfiguration.
11. Fuzzy matching and forward resynchronization with skipped lines left as not detected.
12. A4 PDF output with timestamp in a fixed bold column beside the script text.
13. MediaStore output in Downloads/ScriptTimestamper.
14. Persistent layout and session state.
15. Accessibility service support for auto scrolling.
16. Custom launcher and foreground-notification icons.

## Runtime

The app does not upload audio, screenshots or script text. The speech models are bundled into the APK by the build workflow, so recognition does not require internet access after installation. Android playback capture can still be denied by the source app, in which case the app reports the condition rather than inventing timestamps.

## Use

Upload a PDF with horizontal yellow separators, or leave the PDF empty for live screen OCR. Press START on the main screen and approve the required Android permissions. Open SET LINES on the floating control, configure the outer guide and each individual yellow line, then press SAVE. Press START on the floating control to record. STOP preserves timestamps. SAVE creates the final PDF. NEW SESSION clears the current script and timestamp state without deleting saved PDFs.

For auto scrolling and text access, enable Timestamp Genius under Android Accessibility settings.
