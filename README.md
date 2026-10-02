# Timestamp Genius

Timestamp Genius follows a script while the phone plays audio or video. It captures device playback audio, reads visible script text, matches speech to script lines, records the finish time for every yellow line, and writes the result directly into a PDF.

The implementation follows the supplied specification:
1. Kotlin and Jetpack Compose main UI.
2. Floating overlay with START, STOP, SAVE and SET LINES.
3. Default five line guide with equal spacing.
4. Box move and resize plus per line unlock, width, height and corner controls.
5. Scroll speed 0 through 9.
6. Screen OCR using ML Kit.
7. Offline Vosk speech recognition using bundled English and Hindi models.
8. Playback capture through MediaProjection and AudioPlaybackCaptureConfiguration.
9. Fuzzy matching and forward resynchronization with skipped lines left as not detected.
10. A4 PDF output with timestamp in a fixed bold column beside the script text.
11. MediaStore output in Downloads/ScriptTimestamper.
12. Persistent layout and session state.
13. Accessibility service support for visible text and auto scrolling.

## Runtime

The app does not upload audio, screenshots or script text. The speech models are bundled into the APK by the build workflow, so recognition does not require internet access after installation. Android playback capture can still be denied by the source app, in which case the app reports the condition rather than inventing timestamps.

## Use

Upload a PDF with horizontal yellow separators, or leave the PDF empty for live screen OCR. Press START on the main screen and approve the required Android permissions. Open SET LINES on the floating control, configure the guide, then press SAVE. Press START on the floating control to record. STOP preserves timestamps. SAVE creates the final PDF. NEW SESSION clears the current script and timestamp state without deleting saved PDFs.

For auto scrolling and text access, enable Timestamp Genius under Android Accessibility settings.
