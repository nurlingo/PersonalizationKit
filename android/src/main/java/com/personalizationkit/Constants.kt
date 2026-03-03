package com.personalizationkit

/**
 * User property keys - matches iOS Property enum.
 * Use these for setUserProperty() and getUserProperty() calls.
 */
object Property {
    const val GENDER = "gender"
    const val LANGUAGE = "language"
    const val APNS_TOKEN = "apns_token"  // fcm_token on Android

    const val LAUNCH_COUNT = "launch_count"
    const val BUNDLE_VERSION_AT_INSTALL = "bundleVersionAtInstall"
    const val CURRENT_BUNDLE_VERSION = "current_bundle_version"

    const val PREMIUM = "premium"

    const val PRAYER_DISPLAY = "prayer_display"
    const val AUDIO_DISPLAY = "audio_display"
    const val TRANSLITERATED_ONLY = "transliterated_only"
    const val RECITER = "reciter"
    const val SELECTED_SHORT_SURAH = "selected_short_surah"
    const val SELECTED_SHORT_SURAH_SECOND_RAKAH = "selected_short_surah_second_rak3ah"
    const val TRANSLITERATION_ALPHABET = "transliteration_alphabet"
    const val TRANSLATION_LANGUAGE = "translation_language"
    const val APP_ICON = "app_icon"

    const val COUNTRY_CODE = "country_code"
    const val CITY = "city"

    const val ASR_METHOD = "asrMethod"
    const val FAJR_DEGREE = "fajrDegree"
    const val ISHA_DEGREE = "ishaDegree"
    const val FAJR_INC = "fajrInc"
    const val SUNRISE_INC = "sunriseInc"
    const val DHUHR_INC = "dhuhrInc"
    const val ASR_INC = "asrInc"
    const val MAGHRIB_INC = "maghribInc"
    const val ISHA_INC = "ishaInc"

    const val PLAY_SPEED = "play_speed"
    const val SHOW_ARABIC = "show_arabic"
    const val CINEMATIC_BACKGROUND = "cinematic_background"
    const val TEXT_SIZE = "text_size"
    const val ARABIC_SIZE = "arabic_size"
    const val MEANING_SIZE = "meaning_size"
    const val SUBTITLE_SIZE = "subtitle_size"

    const val MAIN_REPEAT_MODE = "repeat_mode"
    const val PLAYER_MODE = "player_mode"
    const val PLAYER_DISPLAY_MODE = "player_display_mode"

    const val DYNAMIC_FONT_SIZE = "dynamic_font_size"
    const val DYNAMIC_SPEED = "dynamic_speed"
    const val DYNAMIC_REPEAT = "dynamic_repeat"
    const val DYNAMIC_CLOCK = "dynamic_clock"
    const val DYNAMIC_DISPLAY_MODE = "dynamic_display_mode"

    const val CONTACT_DEVELOPER = "contact_developer"
    const val UNREAD_MESSAGES = "unread_messages"
    const val LAST_SELECTED_TAB_INDEX = "last_selected_tab_index"
    const val TAB_AT_LAUNCH = "tab_at_launch"
}

/**
 * Activity log types and values - matches iOS Log enum.
 * Use these for logActivity() type parameter.
 */
object LogType {
    // Activity types
    const val EVENT = "event"
    const val NOTIFICATION = "notification"
    const val SUBSCRIPTION = "subscription"
    const val SESSION = "session"
    const val LESSON = "lesson"
    const val QUIZ = "quiz"
    const val AUDIO_RECORDING = "audio_recording"
    const val RECITATION_CHECK = "recitation_check"
    const val BOOKING = "booking"
    const val CANCEL_BOOKING = "cancel_booking"
}

/**
 * Standard activity IDs - matches iOS Log enum activity cases.
 */
object LogActivity {
    const val BACKGROUND = "background"
    const val FOREGROUND = "foreground"
    const val TERMINATED = "terminated"
    const val ONBOARDING = "onboarding"
    const val SHARE = "share"
    const val LAUNCH = "launch"
}

/**
 * Standard activity values - matches iOS Log enum value cases.
 */
object LogValue {
    const val START = "0"
    const val FINISH = "1"
    const val REFUSE = "-1"
}
