# Timestamp Genius

Timestamp Genius is an Android app that follows a spoken script and records the moment each script line finishes.

## Main workflow

1. UPLOAD PDF selects a script PDF. The app detects yellow separators and treats each separated segment as one script line.
2. START requests the required overlay, notification, and screen capture permissions, then opens the floating control.
3. SET LINES must be saved before recording. The editor starts with five equally spaced lines.
4. The floating START control begins device playback capture, screen OCR, speech recognition, and the timer together.
5. STOP stops capture and keeps timestamps in the active session.
6. SAVE creates the timestamped A4 PDF in Downloads/ScriptTimestamper.
7. LAST PDF RECORDED opens the most recently saved PDF.
8. NEW SESSION clears active session data and cached working data, but never deletes saved PDFs.

## Recognition

Playback audio uses Android AudioPlaybackCapture with MediaProjection. Speech recognition is on device using bundled Vosk English or Hindi models. Screen text is read with ML Kit Latin and Devanagari OCR.

## Auto scroll

Scroll Speed 0 disables scrolling. Speeds 1 through 9 increase the forward scroll interval while recording. Android accessibility service support is used when the target script view exposes a scroll action.

## Permissions

Overlay permission is required for the floating controls.
MediaProjection permission is required for screen and playback capture.
Notification permission is required on Android 13 and newer for the foreground service notification.
Accessibility permission is only needed for automatic scrolling of an exposed scrollable script view.

## Output

PDF files use the format:

00:00:00.000   Script line 1
00:00:03.578   Script line 2
--:--:--.---   Script line not detected

The timestamp appears once in a fixed left column. Wrapped script text stays in the right column. Pages are A4.

## Reliability behavior

The app rejects invalid PDFs and PDFs without yellow separators with a readable error.
Skipped script lines are marked not detected rather than receiving invented timestamps.
If playback capture is blocked by the source app, the service reports the capture error.
