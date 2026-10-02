# Timestamp Genius

Timestamp Genius is an Android app for following a spoken script and recording the moment each script line finishes.

## What the app does

The app captures device playback audio through Android playback capture, reads script text from the screen or from an uploaded PDF, recognizes speech locally, and stores one timestamp for each script line.

An uploaded PDF is treated exactly according to the yellow separators. Every yellow-line-separated segment is one script line. The app does not use ordinary PDF line breaks to create timestamps.

## Main screen

UPLOAD PDF opens the Android document picker for a script PDF. The file is rendered locally and each yellow separator is detected before the segment text is OCRed. A preview shows the number of detected script lines.

START requests the overlay, audio, notification and screen capture permissions needed for the session.

LAST PDF RECORDED opens an Open or Share menu for the latest saved PDF. When no file exists it shows No PDF recorded yet.

NEW SESSION asks for confirmation, clears only the active session data and keeps saved PDFs in phone storage.

## Floating controls

The floating Timestamp Genius icon stays above other apps after overlay permission is granted.

The icon can be dragged vertically. Tapping it opens four controls.

START begins screen OCR, playback audio recognition and the millisecond timer together.

STOP ends capture and keeps timestamps in memory.

SAVE creates the final A4 PDF in Downloads/ScriptTimestamper.

SET LINES opens the layout editor and must be used before recording.

## Line editor

The default layout contains five equally spaced yellow lines.

LINE minus and LINE plus change the number of lines from one through thirty.

SPEED minus and SPEED plus control auto scroll from zero through nine. Zero disables scrolling.

UNLOCK enables independent height, width and corner adjustments for the selected line. Four yellow corner handles resize and move the main yellow box.

SAVE LAYOUT persists the position, size, line count, individual line shape settings and scroll speed. The yellow layout stays visible afterward.

During recording, if OCR detects script text extending below the configured box, the visual box stretches down toward the bottom of the display. Accessibility based auto scroll can continue moving the script.

## Recognition

English and Hindi small Vosk models are embedded into the APK by GitHub Actions, so transcription itself does not need internet access after installation.

The active language is selected from the script content. English uses the English model. Devanagari heavy Hindi uses the Hindi model.

Fuzzy matching tolerates case, punctuation, small word mistakes and some omissions. When an upcoming line is recognized before the current one, skipped lines are marked not detected rather than receiving invented timestamps.

The screen reader uses ML Kit OCR for Latin and Devanagari text and groups words by the yellow line rectangles.

## PDF output

The output filename follows the pattern ScriptTimestamps_YYYY-MM-DD_HH-MM.pdf.

Every script line gets one row. The timestamp is in a fixed bold left column and the script is beside it. Long lines wrap under the script column. Missing timestamps are shown as --:--:--.---.

The output is an A4 white page with black text and no extra decoration.

## Permissions and limitations

Playback capture requires Android playback capture support plus the required recording and MediaProjection permissions. Some source apps intentionally block playback capture.

The foreground service keeps the overlay and capture pipeline alive while the phone screen remains on.

Optional Accessibility access enables automatic scrolling with real touch gestures. The app can still record without Accessibility if the script fits the current screen or the user scrolls manually.

No broad legacy storage permission is required for saving on modern Android because MediaStore writes directly into Downloads/ScriptTimestamper.

## Build

The workflow installs Java 17 and Gradle 8.11, downloads the small English and Hindi Vosk model archives, validates those ZIP files, then runs ten repeated review builds followed by three final verification builds.

The final APK is copied to app/build/outputs/apk/debug/TimestampGenius.apk.

The workflow also checks the APK ZIP structure, Android package metadata and non zero file size before uploading it.

## Project structure

MainActivity.kt contains the main screen and permission flow.

TimestampForegroundService.kt coordinates MediaProjection, OCR, recognition, timestamps, auto scroll and saving.

OverlayController.kt contains the floating icon, four button menu, yellow line display and editable layout.

AudioPlaybackRecognizer.kt captures device playback audio and runs Vosk locally.

ScreenAnalyzer.kt and OcrEngine.kt perform screenshot OCR and live word tracking.

PdfScriptReader.kt enforces yellow-separator PDF segmentation.

PdfTimestampWriter.kt writes the final A4 timestamped PDF.

SessionStore.kt persists the active script, timestamps, layout and last saved PDF reference.
