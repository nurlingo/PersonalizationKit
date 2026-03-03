# PersonalizationKit

Cross-platform analytics and learner tracking library for iOS and Android.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                         Your App                                │
├─────────────────────────────────────────────────────────────────┤
│  Analytics.logActivity()    Analytics.setUserProperty()         │
│  Analytics.getActivity()    Analytics.getUserProperty()         │
└──────────────────────────────┬──────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────┐
│                    PersonalizationKit                           │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐    ┌─────────────────┐                     │
│  │ ActivityService │    │ LearnerService  │                     │
│  │ - logActivity   │    │ - properties    │                     │
│  │ - getActivity   │    │ - learnerId     │                     │
│  │ - getSummary    │    │ - remote sync   │                     │
│  └────────┬────────┘    └────────┬────────┘                     │
│           │                      │                              │
│           └──────────┬───────────┘                              │
│                      ▼                                          │
│           ┌─────────────────────┐                               │
│           │   Local Storage     │                               │
│           │ (iOS: UserDefaults) │                               │
│           │ (Android: SharedPref)│                              │
│           └─────────────────────┘                               │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │   Remote Backend    │
                    │  /learner/{id}      │
                    │  /analytics/bulk    │
                    └─────────────────────┘
```

## Platform Parity

### Unified API

| Method | iOS | Android | Description |
|--------|-----|---------|-------------|
| `logActivity(activityId, type, value?, startDate?, completionDate?)` | ✅ | ✅ | Log user activity |
| `setUserProperty(property, value)` | ✅ | ✅ | Set learner property |
| `getUserProperty(property)` | ✅ | ✅ | Get learner property |
| `getActivity(activityId, type?, value?, logic?)` | ✅ | ✅ | Query activity history |
| `getActivities(types)` | ✅ | ✅ | Get activities by types |
| `getSummary()` | ✅ | ✅ | Get max values per activityId |
| `incrementLaunchCount()` | ✅ | ✅ | Track app launches |
| `launchCount` | ✅ | ✅ | Current launch count |
| `learnerId` | ✅ | ✅ | Unique learner UUID |

### Activity Log Types

Use consistent type strings across platforms:

| Type | Description | Example activityId |
|------|-------------|-------------------|
| `event` | One-time events | `launch`, `background`, `onboarding` |
| `quiz` | Quiz interactions | `quiz_{itemId}` |
| `lesson` | Lesson progress | `lesson_{itemId}` |
| `subscription` | Purchase events | `{productId}` |
| `audio_recording` | Audio features | `recording_{itemId}` |

### Learner Properties

Standard property keys:

| Property | Description |
|----------|-------------|
| `gender` | User gender preference |
| `language` | App language |
| `premium` | Subscription product ID |
| `textSize` | Text size preference |
| `playSpeed` | Audio playback speed |
| `launch_count` | Number of app launches |
| `bundleVersionAtInstall` | App version at first install |
| `platform` | "iOS {version}" or "Android {version}" |
| `city` | User's city |
| `country_code` | User's country |

---

## iOS Integration

### Installation

Add as a git submodule:
```bash
git submodule add git@github.com:iOSerler/PersonalizationKit.git
```

Add to your Podfile:
```ruby
pod 'PersonalizationKit', :path => "./PersonalizationKit"
```

Run `pod install`.

### Setup

#### 1. Implement LearnerStorage Protocol

```swift
import PersonalizationKit

class LocalStorage: NSObject, LearnerStorage {
    static let shared = LocalStorage()

    // Required configuration
    var serverUrl: String = "https://your-backend.com"
    var learnerCollectionName: String = "app_learner"
    var activtyLogCollectionName: String = "app_log"
    var currentAppVersion: String? { Bundle.main.infoDictionary?["CFBundleVersion"] as? String }
    var currentSessionNumber: Int? { Analytics.shared.launchCount }

    // Storage implementation using UserDefaults
    func store(_ anyObject: Any, forKey key: String) {
        UserDefaults.standard.set(anyObject, forKey: key)
    }

    func retrieve(forKey key: String) -> Any? {
        UserDefaults.standard.object(forKey: key)
    }

    func remove(forKey key: String) {
        UserDefaults.standard.removeObject(forKey: key)
    }

    func getAllItemKeys(withPrefix: String) -> [String] {
        UserDefaults.standard.dictionaryRepresentation().keys.filter { $0.contains(withPrefix) }
    }

    func localizedString(forKey key: String) -> String {
        NSLocalizedString(key, comment: "")
    }
}
```

#### 2. Initialize in AppDelegate

```swift
import PersonalizationKit

func application(_ application: UIApplication, didFinishLaunchingWithOptions...) -> Bool {
    // Set storage delegate
    StorageDelegate.learnerStorage = LocalStorage.shared

    // Initialize services
    LearnerService.shared.kickstartLocalLearner()
    ActivityService.shared.kickstartActivityService()

    // Track launch
    Analytics.shared.incrementLaunchCount()

    // Start remote sync (after a few launches)
    if Analytics.shared.launchCount > 2 {
        LearnerService.shared.kickstartRemoteLearner()
    }

    return true
}
```

#### 3. Usage

```swift
// Log activity
Analytics.shared.logActivity("lesson_123", type: "lesson", value: "0.75")

// Set property
Analytics.shared.setUserProperty("textSize", value: 18)

// Get property
let textSize = Analytics.shared.getUserProperty("textSize")

// Query activities
if let activity = ActivityService.shared.getActivity(activityId: "lesson_123", logic: .max) {
    print("Best score: \(activity.value ?? "none")")
}

// Check completion
let isCompleted = ActivityService.shared.getActivity(
    activityId: "quiz_123",
    type: "quiz"
)?.value == "finish"
```

---

## Android Integration

### Installation

Add the PersonalizationKit source files to your project, or include as a module.

### Setup

#### Initialize in Application.onCreate()

```kotlin
import com.personalizationkit.PersonalizationKit

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        PersonalizationKit.initialize(
            context = this,
            buildVersion = BuildConfig.VERSION_CODE.toString(),
            serverUrl = "https://your-backend.com",
            learnerCollectionName = "app_learner",
            analyticsCollectionName = "app_log",
            apiKey = "your-api-key"
        )

        PersonalizationKit.analytics.incrementLaunchCount()
    }
}
```

#### Usage

```kotlin
// Log activity
PersonalizationKit.analytics.logActivity("lesson_123", "lesson", "0.75")

// Set property
PersonalizationKit.analytics.setUserProperty("textSize", 18)

// Get property
val textSize = PersonalizationKit.analytics.getUserProperty("textSize")

// Query activities
val activity = PersonalizationKit.analytics.getActivity("lesson_123", logic = ValueLogic.MAX)
activity?.let {
    Log.d("Progress", "Best score: ${it.value}")
}

// Check completion
val isCompleted = PersonalizationKit.analytics.getActivity(
    activityId = "quiz_123",
    type = "quiz"
)?.value == "finish"
```

---

## Remote Sync

Both platforms sync data to a backend server:

### Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `POST /learner/{collection}` | Create learner |
| `GET /learner/{collection}/{id}` | Get learner |
| `PUT /learner/{collection}` | Update learner |
| `POST /analytics/{collection}` | Log single activity |
| `POST /analytics/{collection}/bulk` | Bulk upload activities |

### Sync Behavior

- **Activities**: Synced immediately on log, with bulk upload fallback
- **Learner properties**: Synced on change, throttled to 1 update/minute
- **Server overrides**: Backend can set `serverOverrides[property] = true` to force values

---

## Data Models

### ActivityLog

```
{
  "id": "uuid",
  "learner_id": "uuid",
  "activity_id": "lesson_123",
  "type": "lesson",
  "value": "0.75",
  "start_date": "2024-01-15T10:30:00.000Z",
  "completion_date": "2024-01-15T10:35:00.000Z",
  "build_version": "260",
  "session_number": 15
}
```

### Learner

```
{
  "id": "uuid",
  "properties": {
    "gender": "male",
    "language": "en",
    "launch_count": "42",
    "platform": "iOS 17.2"
  },
  "serverOverrides": {
    "experimentGroup": true
  }
}
```

---

## Best Practices

1. **Use consistent activityIds** - Same IDs across platforms for the same content
2. **Log meaningful values** - Progress (0.0-1.0), scores, or status strings
3. **Track prerequisites** - Use `getActivity()` to check completion before unlocking content
4. **Batch property updates** - Group related `setUserProperty()` calls
5. **Handle offline gracefully** - Activities are stored locally and synced when possible

---

## Migration Notes

### iOS: From older PersonalizationKit versions

The `StorageDelegate` pattern replaced direct static storage assignments:

```swift
// Old (deprecated)
LocalLearner.staticStorage = LocalStorage.shared
ActivityService.staticStorage = LocalStorage.shared

// New
StorageDelegate.learnerStorage = LocalStorage.shared
```

### Android: Package name

If migrating from app-embedded code, update imports:

```kotlin
// Old
import com.yourapp.personalization.PersonalizationKit

// New
import com.personalizationkit.PersonalizationKit
```

---

## Activity Logging Patterns by Item Type

This section documents exactly when and how each item type logs progress. Both platforms should follow these patterns identically.

### Progress Value Conventions

| Value | Meaning |
|-------|---------|
| `0` or `"0"` | Started / In progress |
| `0.0-1.0` | Progress percentage (e.g., `0.75` = 75%) |
| `1` or `"1"` | Completed / Finished |
| `-1` or `"-1"` | Refused / Skipped |
| `{optionId}` | Selected option ID (for choices) |

### Item Type: `lesson`

**When logged:** On scroll/navigation progress through lesson content

| Platform | Location | Pattern |
|----------|----------|---------|
| iOS | `LessonView.swift` | `Analytics.shared.logActivity(lesson.id, type: "lesson", value: String(min(progress, 1.0)))` |
| Android | `LessonFragment.kt` | `PersonalizationKit.analytics.logActivity(lessonId, "lesson", progress.toString())` |

**Value:** Progress 0.0-1.0 based on scroll position or page viewed

### Item Type: `quiz`

**When logged:** On each answer selection

| Platform | Location | Pattern |
|----------|----------|---------|
| iOS | `DynamicQuiz.swift` | `Analytics.shared.logActivity(quizId, type: "quiz", value: String(progress))` |
| Android | `DynamicContentFragment.kt` | `PersonalizationKit.analytics.logActivity(itemId, "quiz", value)` |

**Value:**
- Progress 0.0-1.0 based on questions answered
- Or `"1"` / `"-1"` for single-question completion/refusal

### Item Type: `dynamic` / `article`

**When logged:** On scroll progress through content

| Platform | Location | Pattern |
|----------|----------|---------|
| iOS | `ItemView.swift` | `item.logInteraction(value: String(min(progress, 1)))` via scroll offset |
| Android | `DynamicContentFragment.kt` | Progress logged on page navigation |

**Value:** Progress 0.0-1.0 based on pages viewed or scroll position

### Item Type: `questionnaire`

**When logged:** Same as `dynamic` - on progress through pages

| Platform | Location | Pattern |
|----------|----------|---------|
| iOS | `ItemView.swift` | `item.logInteraction(value: String(progress))` |
| Android | `DynamicContentFragment.kt` | Handled same as dynamic content |

**Value:** Progress 0.0-1.0 based on pages completed

### Item Type: `switch`

**When logged:** On toggle change

| Platform | Location | Pattern |
|----------|----------|---------|
| iOS | `SwitchRow.swift` | `item.logInteraction(value: isOn ? "1" : "0")` |
| Android | `ItemActionHandler.kt` | `PersonalizationKit.analytics.setUserProperty(item.id, if (isOn) "1" else "0")` |

**Value:** `"1"` for on, `"0"` for off

### Item Type: `segmented`

**When logged:** On option selection

| Platform | Location | Pattern |
|----------|----------|---------|
| iOS | `SegmentedRow.swift` | `item.logInteraction(value: chosenOption.id)` |
| Android | `ItemActionHandler.kt` | `PersonalizationKit.analytics.setUserProperty(item.id, chosenOption.id)` |

**Value:** The selected option's `id`

### Item Type: `subscription`

**When logged:** On successful purchase

| Platform | Location | Pattern |
|----------|----------|---------|
| iOS | `PurchaseManager.swift` | `Analytics.shared.logActivity(product.id, type: "subscription", value: "1")` |
| Android | TODO | Not yet implemented |

**Value:** `"1"` for successful purchase

### Item Type: `audio_recording`

**When logged:** On recording completion

| Platform | Location | Pattern |
|----------|----------|---------|
| iOS | `AudioRecordingView.swift` | `Analytics.shared.logActivity("recording_\(itemId)", type: "audio_recording", value: duration)` |
| Android | TODO | Feature not implemented |

**activityId:** `recording_{itemId}`
**Value:** Recording duration in seconds

### System Events

| Event | activityId | type | value | iOS | Android |
|-------|------------|------|-------|-----|---------|
| App launch | `launch` | `event` | `{count}` | ✅ AppDelegate | ✅ NamazApp.kt |
| App background | `background` | `event` | - | ✅ SceneDelegate | ✅ NamazApp.kt |
| App foreground | `foreground` | `event` | - | ✅ SceneDelegate | ✅ NamazApp.kt |
| App terminated | `terminated` | `event` | - | ✅ SceneDelegate | ⚠️ N/A |
| Onboarding complete | `onboarding` | `event` | `"1"` | ✅ OnboardingView | ✅ FirstLaunchViewModel |
| Share app | `share` | `event` | `{itemId}` | ✅ ShareSheet | ✅ ItemActionHandler |

### Reading Progress for UI Display

To show progress indicators (checkmarks, percentages, blue dots):

```swift
// iOS - in list view
if let activity = ActivityService.shared.getActivity(activityId: item.id, type: item.type, logic: .max) {
    if let value = activity.value, let progress = Double(value) {
        if progress >= 1.0 {
            // Show checkmark ✓
        } else if progress > 0 {
            // Show percentage
        }
    }
} else {
    // Show blue dot (new/unvisited)
}
```

```kotlin
// Android - in GroupedListAdapter
val activity = PersonalizationKit.activityService.getActivity(item.id, item.type)
if (activity != null) {
    val progress = activity.value?.toDoubleOrNull()
    if (progress != null && progress >= 1.0 || activity.value == "1") {
        // Show checkmark ✓
    } else if (progress != null && progress > 0) {
        // Show percentage
    }
} else {
    // Show blue dot (new/unvisited)
}
```

---

## Android Parity Status

### Activity Logging

| Activity | Type | Status | Notes |
|----------|------|--------|-------|
| `background` | `event` | ✅ Done | NamazApp.kt via ProcessLifecycleOwner |
| `foreground` | `event` | ✅ Done | NamazApp.kt via ProcessLifecycleOwner |
| `onboarding` | `event` | ✅ Done | FirstLaunchViewModel.kt |
| `launch` | `event` | ✅ Done | NamazApp.kt via incrementLaunchCount() |
| `terminated` | `event` | ⚠️ N/A | Android doesn't reliably detect termination |
| `notification_tap_{id}` | `notification` | ❌ TODO | Add in notification handler |
| `notification_received_{id}` | `notification` | ❌ TODO | Add in FCM service |
| `recording_{itemId}` | `audio_recording` | ❌ TODO | Feature not in Android yet |
| `recitation_{trackId}` | `recitation_check` | ❌ TODO | Feature not in Android yet |
| `{itemId}` | `booking` | ❌ TODO | Feature not in Android yet |
| `{itemId}` | `cancel_booking` | ❌ TODO | Feature not in Android yet |
| `share` | `event` | ✅ Done | ItemActionHandler + WebViewFragment |
| `go_to_settings_enable_location` | `event` | ❌ TODO | Low priority |
| `recommended_item_selected` | `event` | ❌ TODO | Low priority |

### User Properties

| Property | Status | Notes |
|----------|--------|-------|
| `gender` | ✅ Done | FirstLaunchViewModel, SettingsViewModel |
| `language` | ✅ Done | FirstLaunchViewModel, SettingsViewModel |
| `launch_count` | ✅ Done | NamazApp.kt via incrementLaunchCount() |
| `premium` | ❌ TODO | Add after purchase |
| `fcm_token` | ❌ TODO | Add in FCM token refresh |
| `current_bundle_version` | ❌ TODO | Add on app launch |
| `country_code` | ❌ TODO | Add on location obtained |
| `city` | ❌ TODO | Add on location obtained |
| `text_size` | ❌ TODO | Add on preference change |
| `play_speed` | ❌ TODO | Add on preference change |

### Activity Reading

| Use Case | Status | Notes |
|----------|--------|-------|
| Check lesson completion | ✅ Done | GroupedListAdapter displays checkmark |
| Check prerequisite | ✅ Available | API exists |
| Check if item opened | ✅ Done | GroupedListAdapter shows blue dot for new |
| Get progress value | ✅ Done | GroupedListAdapter displays percentage |

### Item Interaction Logging

| Item Type | Status | Progress Tracking | Notes |
|-----------|--------|-------------------|-------|
| quiz | ✅ Done | Per-question | DynamicContentFragment.onQuizInteraction() |
| lesson | ✅ Done | Scroll-based 0.0-1.0 | LessonFragment with scroll listener |
| article | ✅ Done | Scroll-based 0.0-1.0 | WebViewFragment with scroll listener |
| dynamic | ✅ Done | Page-based 0.0-1.0 | DynamicContentFragment.logProgress() |
| questionnaire | ✅ Done | Page-based 0.0-1.0 | DynamicContentFragment.logProgress() |
| switch | ✅ Done | "1" / "0" | ItemActionHandler.onSwitchChanged() |
| segmented | ✅ Done | Selected option ID | ItemActionHandler.onSegmentChanged() |
| subscription | ❌ TODO | "1" on purchase | Add after billing implementation |
| audio_recording | ❌ TODO | Duration in seconds | Feature not implemented |

### Constants (Property/Log enums)

| Item | Status |
|------|--------|
| `Property` object | ✅ Done | android/.../Constants.kt |
| `LogType` object | ✅ Done | android/.../Constants.kt |
| `LogActivity` object | ✅ Done | android/.../Constants.kt |
| `LogValue` object | ✅ Done | android/.../Constants.kt |

### Implementation Files Reference

| Feature | iOS File | Android File |
|---------|----------|--------------|
| App lifecycle events | SceneDelegate.swift | NamazApp.kt |
| Launch count | AppDelegate.swift | NamazApp.kt |
| Lesson progress | LessonController.swift | LessonFragment.kt |
| Article progress | WebController.swift | WebViewFragment.kt |
| Dynamic/questionnaire | ItemView.swift | DynamicContentFragment.kt |
| Quiz interactions | DynamicQuiz.swift | DynamicContentFragment.kt |
| Switch/segment changes | UIViewController+Navigation.swift | ItemActionHandler.kt |
| Item interaction (initial) | Item.swift | ItemActionHandler.kt |
| Share activity | WebController.swift | ItemActionHandler.kt, WebViewFragment.kt |
| Onboarding | OnboardingView.swift | FirstLaunchViewModel.kt |
| Property constants | AppDelegate.swift (Property enum) | Constants.kt (Property object) |
| Log type constants | AppDelegate.swift (Log enum) | Constants.kt (LogType object) |
