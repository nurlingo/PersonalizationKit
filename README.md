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

## Android Parity TODO

The following iOS features need Android implementation:

### Activity Logging (Missing in Android)

| Activity | Type | When to Log | Priority |
|----------|------|-------------|----------|
| `background` | `event` | App goes to background | High |
| `foreground` | `event` | App comes to foreground | High |
| `terminated` | `event` | App terminated | Medium |
| `notification_tap_{id}` | `notification` | User taps notification | Medium |
| `notification_received_{id}` | `notification` | Notification delivered | Low |
| `recording_{itemId}` | `audio_recording` | Audio recording completed | Medium |
| `recitation_{trackId}` | `recitation_check` | Recitation check result | Medium |
| `{itemId}` | `booking` | Booking made | Low |
| `{itemId}` | `cancel_booking` | Booking cancelled | Low |
| `share` | `event` | Content shared | Low |
| `onboarding` | `event` | Onboarding completed | Medium |
| `go_to_settings_enable_location` | `event` | Location permission prompt | Low |
| `recommended_item_selected` | `event` | Recommendation clicked | Low |

### User Properties (Verify Android Sets These)

| Property | When Set | Priority |
|----------|----------|----------|
| `gender` | Initial setup / settings | High |
| `language` | Initial setup / settings | High |
| `premium` | After purchase | High |
| `apns_token` / `fcm_token` | On token refresh | High |
| `current_bundle_version` | On app launch | Medium |
| `country_code` | On location obtained | Medium |
| `city` | On location obtained | Medium |
| `text_size` | On preference change | Low |
| `play_speed` | On preference change | Low |

### Activity Reading (Verify Android Implements)

| Use Case | Query | Priority |
|----------|-------|----------|
| Check lesson completion | `getActivity(id, type)?.value == "1"` | High |
| Check prerequisite | `getActivity(prerequisiteId, logic: .max)` | High |
| Check if item opened | `getActivity(id, type) != nil` | Medium |
| Get progress value | `getActivity(id, type, logic: .max)?.value` | Medium |
