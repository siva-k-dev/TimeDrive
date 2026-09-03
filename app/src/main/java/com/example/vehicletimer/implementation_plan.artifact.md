# Implementation Plan - Fix Light Mode Contrast and Visibility Issues

This plan addresses UI visibility issues in light mode caused by the introduction of light gradient backgrounds. We will replace hardcoded colors with theme-aware colors and adjust contrast for key elements.

## Proposed Changes

### [Component: UI Theme & Visibility]

#### [MODIFY] [MainActivity.kt](file:///C:/Users/SivaK/AndroidStudioProjects/VehicleTimer/app/src/main/java/com/example/vehicletimer/MainActivity.kt)

1.  **Vehicle List Screen:**
    -   Update `Card` colors to use `MaterialTheme.colorScheme.surface` instead of hardcoded `Color.White`.
    -   Increase `Card` elevation and update border color for better separation from the background.
    -   Update vehicle name text to use `MaterialTheme.colorScheme.onSurface`.
2.  **Onboarding Screen:**
    -   Update inactive dot indicator color to `MaterialTheme.colorScheme.outlineVariant` for better contrast.
3.  **Totals Screen:**
    -   Ensure "Pending" amount text uses a color with sufficient contrast in light mode.
4.  **Rural Fields Background:**
    -   Adjust cloud opacity in light mode to ensure visibility against the light sky.
5.  **General Fixes:**
    -   Check and fix any remaining hardcoded `Color.White` on light backgrounds.

## Verification Plan

### Automated Tests
- Build the project using `./gradlew assembleDebug` to ensure no syntax errors.

### Manual Verification
1.  Deploy the app to the emulator.
2.  Switch to Light Mode using the theme toggle.
3.  Verify the following screens for visibility:
    -   **Vehicle List:** Ensure cards are distinct from the background.
    -   **Add Vehicle:** Ensure text fields are clearly visible.
    -   **Timer:** Ensure live updates are readable.
    -   **History/Totals:** Ensure cards and text have good contrast.
    -   **Onboarding:** Ensure dot indicators are visible.
4.  Switch back to Dark Mode and verify everything still looks correct.
