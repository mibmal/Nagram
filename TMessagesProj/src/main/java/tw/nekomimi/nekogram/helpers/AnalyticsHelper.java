package tw.nekomimi.nekogram.helpers;

import android.app.Application;

import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.BuildVars;


import com.google.firebase.crashlytics.FirebaseCrashlytics;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import io.sentry.android.core.SentryAndroid;
import xyz.nextalone.nagram.NkmrConfig;

public class AnalyticsHelper {
    public static String DSN = "https://f7a6e4cc5c2b0a3aded76128a06d34e4@o416616.ingest.us.sentry.io/4507780440915968";
    public static boolean loaded = false;
    public static final boolean CRASHLYTICS_DEFAULT = false;

    public static void start(Application application) {
        applyCrashlyticsStatus();
        if (!getSentryStatus(application)) {
            return;
        }
        SentryAndroid.init(application, options -> {
            options.setDsn(DSN);
            options.setEnvironment(BuildVars.DEBUG_VERSION ? "debug" : "release");
            options.setEnableAutoSessionTracking(true);
            options.setTracesSampleRate(1.0);
            options.setAttachAnrThreadDump(true);
            options.setRelease(BuildConfig.APPLICATION_ID + "@" + BuildConfig.VERSION_NAME + "+" + BuildConfig.VERSION_CODE);
            options.setTag("buildTimestamp", BuildConfig.BUILD_TIMESTAMP + "");
            options.setTombstoneEnabled(true);
            options.setReportHistoricalTombstones(true);
            options.setBeforeScreenshotCaptureCallback((event, hint, debounce) -> {
                // always capture crashed events
                if (event.isCrashed()) {
                    return true;
                }

                // if debounce is active, skip capturing
                if (debounce) {
                    return false;
                } else {
                    // also capture fatal events
                    return event.getLevel() == SentryLevel.FATAL;
                }
            });
        });
        loaded = true;
    }

    public static void captureException(Throwable e) {
        if (loaded) {
            Sentry.captureException(e);
        }
    }

    // Crashlytics auto-collection is disabled in the manifest so nothing is sent
    // before the user's choice is known; apply that choice on every start.
    public static void applyCrashlyticsStatus() {
        try {
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(getCrashlyticsStatus());
        } catch (Throwable ignored) {
            // FirebaseApp not initialised (e.g. no google-services config)
        }
    }

    public static boolean getCrashlyticsStatus() {
        return NkmrConfig.preferences.getBoolean("FirebaseCrashlytics", CRASHLYTICS_DEFAULT);
    }

    public static boolean getSentryStatus(Application application) {
        return NkmrConfig.preferences.getBoolean("SentryAnalytics", false);
    }
}
