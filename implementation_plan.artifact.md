# Implementation Plan - Initial Business Setup Screen

Add a one-time business setup step immediately after onboarding to help users configure their company details (Business Name, Phone, Address).

## User Review Required

> [!IMPORTANT]
> The `InitialSetupScreen` will be shown only once after onboarding completes. Subsequent app launches will skip this screen.

## Proposed Changes

### [Core]

#### [MODIFY] [MainActivity.kt](file:///C:/Users/SivaK/AndroidStudioProjects/VehicleTimer/app/src/main/java/com/example/vehicletimer/MainActivity.kt)
- Add `object InitialSetup : Screen()` to the `Screen` sealed class.
- In `AppRoot`, update the `onTimeout` of `Screen.Splash` and `onFinish` of `Screen.Onboarding` to check for `initial_setup_completed`.
- Implement `InitialSetupScreen` composable:
    - Reuse logic from `CompanyDetailsScreen` for the form fields.
    - Use `RuralFieldsBackground` for consistency with onboarding.
    - Add "Skip for now" and "Continue" buttons.
    - Save data to `CompanySettingsEntity` if "Continue" is clicked.
    - Mark `initial_setup_completed` as true in both cases.

### [Resources]

#### [MODIFY] [strings.xml (en)](file:///C:/Users/SivaK/AndroidStudioProjects/VehicleTimer/app/src/main/res/values/strings.xml)
- Add English strings for title, subtext, buttons, and reassurance line.

#### [MODIFY] [strings.xml (hi, ta, te, mr, ml)](file:///C:/Users/SivaK/AndroidStudioProjects/VehicleTimer/app/src/main/res/values-hi/strings.xml)
- Add localized strings for the same keys.

## Verification Plan

### Manual Verification
- Deploy the app to a device/emulator.
- Complete or skip onboarding.
- Verify that the "Set up your business" screen appears.
- Test "Skip for now": ensure it goes to the main list and the setup screen doesn't reappear on restart.
- Test "Continue": enter data, ensure it goes to the main list, verify data is saved in Settings, and the setup screen doesn't reappear on restart.
- Verify localization by changing the app language.
