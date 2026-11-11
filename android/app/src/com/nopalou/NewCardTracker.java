package com.nopalou;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/** App-wide foreground visits, shared by todo, timetable, settings and other activities. */
final class NewCardTracker implements Application.ActivityLifecycleCallbacks {
    private static NewCardTracker instance;
    private final SharedPreferences prefs;
    private NewCardSession session;
    private int started;
    private boolean changingConfigurations;

    static NewCardTracker get(Context context) {
        if (instance == null) {
            Application app = (Application) context.getApplicationContext();
            instance = new NewCardTracker(app);
            app.registerActivityLifecycleCallbacks(instance);
        }
        return instance;
    }

    private NewCardTracker(Application app) {
        prefs = app.getSharedPreferences("new_cards", Context.MODE_PRIVATE);
    }

    NewCardSession observe(Engine engine) {
        if (session == null) session = new NewCardSession(prefs.getStringSet("seen_ids", null));
        List<String> ids = new ArrayList<>();
        for (Engine.Card card : engine.all()) ids.add(card.id);
        if (session.observe(ids)) prefs.edit().putStringSet("seen_ids", session.seenIds()).apply();
        return session;
    }

    @Override public void onActivityStarted(Activity activity) {
        if (started++ == 0 && !changingConfigurations) session = null;
        changingConfigurations = false;
    }

    @Override public void onActivityStopped(Activity activity) {
        if (--started == 0) changingConfigurations = activity.isChangingConfigurations();
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityResumed(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
