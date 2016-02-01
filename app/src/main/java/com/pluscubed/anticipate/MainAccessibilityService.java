package com.pluscubed.anticipate;

import android.accessibilityservice.AccessibilityService;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.customtabs.CustomTabsCallback;
import android.support.customtabs.CustomTabsService;
import android.support.v7.app.NotificationCompat;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.pluscubed.anticipate.customtabs.util.CustomTabConnectionHelper;
import com.pluscubed.anticipate.filter.AppInfo;
import com.pluscubed.anticipate.filter.DbUtil;
import com.pluscubed.anticipate.util.PrefUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rx.Observable;
import rx.SingleSubscriber;
import rx.functions.Func1;

public class MainAccessibilityService extends AccessibilityService {

    public static final String TAG = "Accesibility";
    public static final String LINK_REG_EX = "((?:[a-z][\\w-]+:(?:\\/{1,3}|[a-z0-9%])|www\\d{0,3}[.]|[a-z0-9.\\-]+[.][a-z]{2,4}/)(?:[^\\s()<>]+|\\(([^\\s()<>]+|(\\([^\\s()<>]+\\)))*\\))+(?:\\(([^\\s()<>]+|(\\([^\\s()<>]+\\)))*\\)|[^\\s`!()\\[\\]{};:'\".,<>?«»“”‘’]))";
    public static final int NOTIFICATION_CHANGELOG = 2;

    private static MainAccessibilityService sSharedService;
    List<String> mFilterList;
    boolean mBlacklistMode;
    /*boolean mSwitchTask;
    long mSwitchedTimestamp;
    LimitedQueue<AppUsageEntry> mAppsUsed;*/

    Map<String, ImageView> mQueuedWebsites;


    WindowManager mWindowManager;
    Handler mMainHandler;
    private CustomTabConnectionHelper mCustomTabActivityHelper;

    public static MainAccessibilityService get() {
        return sSharedService;
    }

    public static void updateFilterList() {
        if (MainAccessibilityService.get() != null) {
            MainAccessibilityService.get().updateBlackWhitelistInternal();
        }
    }

    private static void log(String info) {
        if (BuildConfig.DEBUG)
            Log.i(TAG, info);
    }

    public CustomTabConnectionHelper getCustomTabActivityHelper() {
        return mCustomTabActivityHelper;
    }

    public void addQueuedWebsite(String url) {
        if (!mQueuedWebsites.containsKey(url)) {
            addBubble(url);

            /*Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    AppUsageEntry startedEntry = startLastOpenApp();

                    if (startedEntry != null) {
                        Toast.makeText(MainAccessibilityService.this, "Started: " + startedEntry.packageName + "/" + startedEntry.activityName, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainAccessibilityService.this, "Attempt switching using recents", Toast.LENGTH_SHORT).show();

                        performGlobalAction(GLOBAL_ACTION_RECENTS);
                        mSwitchTask = true;
                    }

                    ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                    List<ActivityManager.RunningTaskInfo> recentTasks = activityManager.getRunningTasks(Integer.MAX_VALUE);
                    for (int i = 0; i < recentTasks.size(); i++) {
                        if (recentTasks.get(i).baseActivity.toShortString().contains(BuildConfig.APPLICATION_ID)) {
                            activityManager.moveTaskToFront(recentTasks.get(i).id, 1);
                        }
                    }

                }
            }, 500);*/
        } else {
            mWindowManager.removeView(mQueuedWebsites.get(url));
            mQueuedWebsites.remove(url);
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sSharedService = this;

        mMainHandler = new Handler(getMainLooper());

        /*mAppsUsed = new LimitedQueue<>(10);*/
        mQueuedWebsites = new HashMap<>();

        mCustomTabActivityHelper = new CustomTabConnectionHelper();
        mCustomTabActivityHelper.setCustomTabsCallback(new TabsNavigationCallback());
        boolean success = mCustomTabActivityHelper.bindCustomTabsService(this);
        log("onServiceConnected: " + success);

        DbUtil.initializeBlacklist(this);

        updateFilterList();

        if (BuildConfig.VERSION_CODE > PrefUtils.getVersionCode(this)) {
            Intent notificationIntent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

            Notification notification = new NotificationCompat.Builder(this)
                    .setContentTitle(getString(R.string.anticipate_update) + BuildConfig.VERSION_NAME)
                    .setContentText(getString(R.string.anticipate_update_desc))
                    .setSmallIcon(R.drawable.ic_trending_up_black_24dp)
                    .setContentIntent(pendingIntent)
                    .build();

            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.notify(NOTIFICATION_CHANGELOG, notification);
        }

        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

    }

    private void addBubble(final String url) {
        final ImageView imageView = new ImageView(this);
        imageView.setBackgroundResource(R.drawable.bubble);

        int padding = getResources().getDimensionPixelSize(R.dimen.floating_padding);
        imageView.setPadding(padding, padding, padding, padding);
        imageView.setOnTouchListener(new View.OnTouchListener() {
            float initialTouchX;
            float initialTouchY;
            int initialX;
            int initialY;

            long startClickTime;

            boolean isClick;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) v.getLayoutParams();

                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();

                        initialX = params.x;
                        initialY = params.y;

                        startClickTime = System.currentTimeMillis();

                        isClick = true;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dX = event.getRawX() - initialTouchX;
                        float dY = event.getRawY() - initialTouchY;
                        if ((isClick && (Math.abs(dX) > 10 || Math.abs(dY) > 10))
                                || System.currentTimeMillis() - startClickTime > ViewConfiguration.getLongPressTimeout()) {
                            isClick = false;
                        }

                        if (!isClick) {
                            params.x = (int) (dX + initialX);
                            params.y = (int) (dY + initialY);

                            mWindowManager.updateViewLayout(v, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (isClick && System.currentTimeMillis() - startClickTime <= ViewConfiguration.getLongPressTimeout()) {
                            Intent intent = new Intent(MainAccessibilityService.this, BrowserLauncherActivity.class);
                            intent.setData(Uri.parse(url));
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            intent.putExtra(BrowserLauncherActivity.EXTRA_ADD_QUEUE, false);
                            mQueuedWebsites.remove(url);
                            mWindowManager.removeView(imageView);
                            startActivity(intent);
                        } else {
                            animateViewToSideSlot((ImageView) v);
                        }
                        return true;
                }
                return false;
            }
        });
        final int bubbleWidth = getResources().getDimensionPixelSize(R.dimen.floating_size);
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                bubbleWidth,
                bubbleWidth,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);

        Glide.with(this)
                .load(Uri.parse("http://www.google.com/s2/favicons?domain_url=" + url))
                .placeholder(R.mipmap.ic_launcher)
                .error(R.mipmap.ic_launcher)
                .into(imageView);

        params.gravity = Gravity.LEFT | Gravity.TOP;

        final Point size = new Point();
        mWindowManager.getDefaultDisplay().getSize(size);
        params.x = size.x;
        params.y = size.y / 3;

        mWindowManager.addView(imageView, params);

        mQueuedWebsites.put(url, imageView);


        int endX = size.x - bubbleWidth * 4 / 5;
        ValueAnimator valueAnimator = ValueAnimator.ofInt(params.x, endX)
                .setDuration(300);
        valueAnimator.setInterpolator(new OvershootInterpolator());
        valueAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) imageView.getLayoutParams();
                params.x = (int) animation.getAnimatedValue();

                mWindowManager.updateViewLayout(imageView, params);
            }
        });
        valueAnimator.start();

    }

    void animateViewToSideSlot(final ImageView view) {
        int bubbleWidth = getResources().getDimensionPixelSize(R.dimen.floating_size);

        Point size = new Point();
        mWindowManager.getDefaultDisplay().getSize(size);

        WindowManager.LayoutParams params = (WindowManager.LayoutParams) view.getLayoutParams();
        int endX;
        if (params.x + bubbleWidth / 2 >= size.x / 2) {
            endX = size.x - bubbleWidth * 4 / 5;
        } else {
            endX = -bubbleWidth / 5;
        }

        int endY;
        endY = params.y;

        ValueAnimator valueAnimator = ValueAnimator.ofInt(params.x, endX)
                .setDuration(300);
        valueAnimator.setInterpolator(new OvershootInterpolator());
        valueAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) view.getLayoutParams();
                params.x = (int) animation.getAnimatedValue();
            }
        });

        ValueAnimator valueAnimator2 = ValueAnimator.ofInt(params.y, endY)
                .setDuration(300);
        valueAnimator2.setInterpolator(new OvershootInterpolator());
        valueAnimator2.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) view.getLayoutParams();
                params.y = (int) animation.getAnimatedValue();

                mWindowManager.updateViewLayout(view, params);
            }
        });

        AnimatorSet set = new AnimatorSet();
        set.playTogether(valueAnimator, valueAnimator2);
        set.start();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sSharedService = null;
        mCustomTabActivityHelper.unbindCustomTabsService(this);

        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        sSharedService = this;

        log("====onAccessibilityEvent===");
        log("=type: " + AccessibilityEvent.eventTypeToString(event.getEventType()));

        if (mFilterList == null) {
            return;
        }

        /*if (mSwitchTask && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 && getRootInActiveWindow() != null) {
            List<AccessibilityNodeInfo> accessibilityNodeInfosByViewId =
                    getRootInActiveWindow().findAccessibilityNodeInfosByViewId("com.android.systemui:id/activity_description");

            AccessibilityNodeInfo secondToLast;
            if(accessibilityNodeInfosByViewId.size()>0
                    && (secondToLast=accessibilityNodeInfosByViewId.get(accessibilityNodeInfosByViewId.size()-2))!=null){
                final String text = secondToLast.getText().toString();
                while(!secondToLast.isClickable()){
                    secondToLast = secondToLast.getParent();
                }
                final boolean performed = secondToLast.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if(performed){
                    mSwitchTask = false;
                    mSwitchedTimestamp = System.currentTimeMillis();
                }
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainAccessibilityService.this, text + ": recents switch - "+performed,Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }*/


        CharSequence packageName = event.getPackageName();

        if (packageName != null) {
            String appId = packageName.toString();
            log("=appId: " + appId);

            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                String activityName = event.getClassName().toString();

                /*boolean redundant = false;
                if (mAppsUsed.size() > 0) {
                    String lastPackageName = mAppsUsed.peekLast().packageName;
                    redundant = appId.equals(lastPackageName);
                }
                if (!redundant) {
                    AppUsageEntry entry = new AppUsageEntry();
                    entry.activityName = activityName;
                    entry.packageName = appId;
                    mAppsUsed.add(entry);
                }*/

                log("=activityName: " + activityName);
            }



            if ((mBlacklistMode && mFilterList.contains(appId)) ||
                    (!mBlacklistMode && !mFilterList.contains(appId))) {
                log("=excluded");
                return;
            }
        }


        String allText = getAllText(getRootInActiveWindow(), 0);

        Log.v(TAG, allText);

        Pattern pattern = Pattern.compile(LINK_REG_EX);
        Matcher matcher = pattern.matcher(allText);


        Uri firstUrl = null;
        String floatingWindowText = "";
        List<Bundle> possibleUrls = new ArrayList<>();

        while (matcher.find()) {
            String url = matcher.group(0);

            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://" + url;
            }

            Uri uri = Uri.parse(url);

            if (firstUrl == null) {
                firstUrl = uri;

                floatingWindowText += uri;
            } else {
                Bundle bundle = new Bundle();
                bundle.putParcelable(CustomTabsService.KEY_URL, uri);
                possibleUrls.add(bundle);

                floatingWindowText += "\n" + uri;
            }

            log("Preload URL: " + url);
        }


        if (firstUrl != null) {
            boolean success = mCustomTabActivityHelper.mayLaunchUrl(firstUrl, null, possibleUrls);

            if (FloatingWindowService.get() != null) {
                FloatingWindowService.get().setText(floatingWindowText);
            }

            log("Preload URL: " + success);
        }


    }

    void updateBlackWhitelistInternal() {
        DbUtil.getPerAppListApps(this).flatMapObservable(new Func1<List<AppInfo>, Observable<AppInfo>>() {
            @Override
            public Observable<AppInfo> call(List<AppInfo> appInfos) {
                return Observable.from(appInfos);
            }
        }).map(new Func1<AppInfo, String>() {
            @Override
            public String call(AppInfo appInfo) {
                return appInfo.packageName;
            }
        }).toList().toSingle()
                .subscribe(new SingleSubscriber<List<String>>() {

                    @Override
                    public void onError(Throwable e) {

                    }

                    @Override
                    public void onSuccess(List<String> strings) {
                        mFilterList = strings;
                        mBlacklistMode = PrefUtils.isBlacklistMode(MainAccessibilityService.this);
                    }
                });


    }

    private String getAllText(AccessibilityNodeInfo source, int depth) {
        if (depth > 50) {
            return "";
        }

        String string = "";

        if (source == null) {
            return string;
        }

        if (source.getText() != null) {
            String text = source.getText().toString();
            text = text.replace("\n", " ");
            string += text + " ";

            log("Text: " + text);
        }

        for (int i = 0; i < source.getChildCount(); i++) {
            string += getAllText(source.getChild(i), depth + 1);
        }

        source.recycle();

        return string;
    }

    @Override
    public void onInterrupt() {

    }

   /* @Nullable
    AppUsageEntry startLastOpenApp() {
        List<AppUsageEntry> viable = new ArrayList<>();
        String firstViablePackageName = null;
        for (Iterator<AppUsageEntry> iterator = mAppsUsed.descendingIterator(); iterator.hasNext(); ) {
            AppUsageEntry entry = iterator.next();

            if (!entry.packageName.equals(PrefUtils.getChromeApp(MainAccessibilityService.this)) &&
                    !entry.packageName.startsWith("com.android.systemui") &&
                    !entry.packageName.startsWith("android")) {
                if (viable.size() == 0) {
                    firstViablePackageName = entry.packageName;
                }
                if (firstViablePackageName != null && firstViablePackageName.equals(entry.packageName)) {
                    viable.add(entry);
                }
            }
        }

        AppUsageEntry startedEntry = null;
        for (AppUsageEntry entry : viable) {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(entry.packageName, entry.activityName));
            intent.setAction(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try {
                startActivity(intent*//*, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()*//*);
                startedEntry = entry;
                break;
            } catch (Exception e) {
                //ignore
            }
        }
        return startedEntry;
    }*/

    private class AppUsageEntry {
        String packageName;
        String activityName;

        AppUsageEntry() {
        }
    }

    private class TabsNavigationCallback extends CustomTabsCallback {
        TabsNavigationCallback() {
        }

        /*@Override
        public void onNavigationEvent(int navigationEvent, Bundle extras) {
            super.onNavigationEvent(navigationEvent, extras);

            switch(navigationEvent){
                case TAB_SHOWN:
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainAccessibilityService.this, "Tab shown", Toast.LENGTH_SHORT).show();
                        }
                    });
                    break;
                case TAB_HIDDEN:
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainAccessibilityService.this, "Tab hidden", Toast.LENGTH_SHORT).show();
                        }
                    });

                    startLastOpenApp();
                    break;
                case NAVIGATION_FINISHED:
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainAccessibilityService.this, "Page finished", Toast.LENGTH_SHORT).show();
                        }
                    });
                    break;
                case NAVIGATION_STARTED:
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainAccessibilityService.this, "Page started", Toast.LENGTH_SHORT).show();
                        }
                    });
                    break;
            }
        }*/
    }
}
