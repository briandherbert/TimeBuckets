package com.burningaltar.timebuckets;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.Html;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.burningaltar.timebuckets.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

public class TimeBucketsActivity extends AppCompatActivity {
    static final String TAG = TimeBucketsActivity.class.getSimpleName();

    static final String KEY_CURRENT_BUCKET_NAME = "KEY_LAST_BUCKET";
    static final String KEY_CURRENT_BUCKET_START_TIME = "KEY_LAST_TIME";
    static final String KEY_LAST_TICK = "KEY_LAST_TICK";
    static final String PREFIX_KEY_BUCKET_DURATION = "PREFIX_KEY_BUCKET_DURATION";
    static final String PREFIX_KEY_BUCKET_NAME = "PREFIX_KEY_BUCKET_NAME";

    static Bucket BUCKET_BREAK;

    static int HIGHLIGHT_COLOR;

    static final long UPDATE_TICK_MS = 1000;

    static class Bucket {
        public Bucket(@NonNull String name, long duration) {
            this.name = name;
            this.duration = duration;
        }

        public Bucket(@NonNull String name) {
            this(name, -1);
        }

        public void addTime(long duration) {
            this.duration += duration;
        }

        String name;
        long duration;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Bucket bucket = (Bucket) o;

            return name.equals(bucket.name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public String toString() {
            return "Bucket name " + name + " duration " + duration;
        }
    }

    SharedPreferences mSharedPrefs;

    static SimpleDateFormat mStartFormat = new SimpleDateFormat("hh:mm:ss a");
    static SimpleDateFormat mElapsedFormat = new SimpleDateFormat("HH:mm:ss");

    static {
        mElapsedFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    Bucket mCurrentBucket;
    long mCurrentStartTime = -1;
    long mLastTick = -1;


    TextView mLblInfo;
    ListView mListView;
    EditText mTxtNewCategory;

    final ArrayList<Bucket> mBuckets = new ArrayList<>();
    ArrayAdapter mAdapter = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        BUCKET_BREAK = new Bucket(getString(R.string.pause), 0);
        HIGHLIGHT_COLOR = getResources().getColor(R.color.highlight);

        setContentView(R.layout.activity_time_buckets);
        mListView = (ListView) findViewById(R.id.list);
        mLblInfo = (TextView) findViewById(R.id.lbl_info);
        mTxtNewCategory = (EditText) findViewById(R.id.txt_new);

        mSharedPrefs = this.getSharedPreferences(TAG, Context.MODE_PRIVATE);

        mAdapter = new TimerBucketsAdapter(this, android.R.layout.simple_list_item_2, mBuckets);

        mListView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        mListView.setAdapter(mAdapter);
        registerForContextMenu(mListView);

        mListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                setCurrentBucket(mBuckets.get(position));
            }
        });

        mTxtNewCategory.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    String name = mTxtNewCategory.getText().toString().trim();

                    if (name != null && !name.isEmpty()) {
                        addBucket(new Bucket(name));
                    }
                }

                return false;
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        restore();
    }

    @Override
    public void onPause() {
        super.onPause();
        mHandler.removeMessages(BucketsHandler.MESSAGE_UPDATE_CURRENT_DURATION);
        save();
    }

    void onTick() {
        long time = System.currentTimeMillis();

        if (mCurrentBucket != null) {
            mBuckets.get(mBuckets.indexOf(mCurrentBucket)).addTime(time - mLastTick);
        }

        mLastTick = time;
    }

    public void setCurrentBucket(Bucket bucket) {
        onTick();

        if (mCurrentBucket != bucket) {
            mCurrentBucket = bucket;
            mCurrentStartTime = mLastTick;
        }

        String str = getString(R.string.bucket_started_at, mCurrentBucket.name, mStartFormat.format(new Date(mCurrentStartTime)));
        CharSequence styledStr = Html.fromHtml(str);
        mLblInfo.setText(styledStr);

        mListView.setItemChecked(mAdapter.getPosition(bucket), true);
        mAdapter.notifyDataSetChanged();
    }

    public void save() {
        if (mSharedPrefs == null)
            mSharedPrefs = this.getSharedPreferences(TAG, Context.MODE_PRIVATE);

        SharedPreferences.Editor editor = mSharedPrefs.edit();

        onTick();

        int i = 0;
        for (Bucket bucket : mBuckets) {
            if (bucket.equals(BUCKET_BREAK)) continue;
            editor.putString(PREFIX_KEY_BUCKET_NAME + i++, bucket.name);
            editor.putLong(PREFIX_KEY_BUCKET_DURATION + bucket.name, bucket.duration);
            Log.v("blarg", "saving " + bucket.name);
        }

        editor.remove(PREFIX_KEY_BUCKET_NAME + i);

        if (mCurrentBucket != null) {
            editor.putString(KEY_CURRENT_BUCKET_NAME, mCurrentBucket.name);
            editor.putLong(KEY_CURRENT_BUCKET_START_TIME, mCurrentStartTime);
            editor.putLong(KEY_LAST_TICK, mLastTick);
        }

        editor.commit();
    }

    public void restore() {
        // DO NOT CALL #setCurrentBucket() FROM HERE!
        if (mSharedPrefs == null) {
            mSharedPrefs = this.getSharedPreferences(TAG, Context.MODE_PRIVATE);
        }

        String currentName = mSharedPrefs.getString(KEY_CURRENT_BUCKET_NAME, null);

        addBucket(BUCKET_BREAK);

        int i = 0;
        String name;
        while ((name = mSharedPrefs.getString(PREFIX_KEY_BUCKET_NAME + i++, null)) != null) {
            if (name == null) break;

            long duration = mSharedPrefs.getLong(PREFIX_KEY_BUCKET_DURATION + name, 0);
            Log.v("blarg", "restoring " + name);

            if (name.equals(currentName)) {
                mCurrentStartTime = mSharedPrefs.getLong(KEY_CURRENT_BUCKET_START_TIME, -1);
                mLastTick = mSharedPrefs.getLong(KEY_LAST_TICK, -1);
                mCurrentBucket = new Bucket(name, duration + System.currentTimeMillis() - mLastTick);
                addBucket(mCurrentBucket);
                setCurrentBucket(mCurrentBucket);
            } else {
                addBucket(new Bucket(name, duration));
            }
        }

        if (mCurrentBucket == null) {
            setCurrentBucket(BUCKET_BREAK);
        }
    }

    public void showNewInput() {
        if (mTxtNewCategory == null) return;

        mTxtNewCategory.setTranslationY(-mTxtNewCategory.getHeight());
        mTxtNewCategory.setVisibility(View.VISIBLE);

        mTxtNewCategory.animate().translationY(0).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                mTxtNewCategory.setVisibility(View.VISIBLE);
            }
        });
    }

    public void hideNewInput() {
        if (mTxtNewCategory == null) return;

        mTxtNewCategory.animate().translationY(-mTxtNewCategory.getHeight()).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                mTxtNewCategory.setVisibility(View.GONE);
            }
        });
    }

    void addBucket(@NonNull Bucket bucket) {
        if (!mBuckets.contains(bucket)) {
            mBuckets.add(bucket);

            if (bucket.equals(mCurrentBucket)) {
                mListView.setItemChecked(mBuckets.size() - 1, true);
            }

            mAdapter.notifyDataSetChanged();
        }

        mTxtNewCategory.setText("");
        mTxtNewCategory.clearFocus();

        View view = this.getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }

        //mTxtNewCategory.setVisibility(View.GONE);
        hideNewInput();
    }

    private HashMap<String, String> createPlanet(String key, String name) {
        HashMap<String, String> planet = new HashMap<String, String>();
        planet.put(key, name);

        return planet;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.buckets_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.menu_add:
                //mTxtNewCategory.setVisibility(View.VISIBLE);
                showNewInput();
                mTxtNewCategory.requestFocus();

                View view = this.getCurrentFocus();
                if (view != null) {
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.showSoftInput(view, 0);
                }
                return true;
            case R.id.menu_clear_times:
                clearAllTimes();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    public void clearAllTimes() {
        for (int i = 0; i < mBuckets.size(); i++) {
            mBuckets.get(i).duration = 0;
        }

        mCurrentBucket = null;
        mCurrentStartTime = -1;

        mLblInfo.setText("");
        mAdapter.notifyDataSetChanged();
        setCurrentBucket(mBuckets.get(0));
    }

    @Override
    public void onBackPressed() {
        if (mTxtNewCategory != null && mTxtNewCategory.getVisibility() == View.VISIBLE) {
            //mTxtNewCategory.setVisibility(View.GONE);
            hideNewInput();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        if (v.getId() == R.id.list && ((AdapterView.AdapterContextMenuInfo) menuInfo).position != 0) {
            MenuInflater inflater = getMenuInflater();
            inflater.inflate(R.menu.context_menu, menu);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        if (info.position == 0) return true;

        Bucket selectedBucket = mBuckets.get(info.position);
        boolean isCurrentBucket = selectedBucket.equals(mCurrentBucket);

        switch (item.getItemId()) {
            case R.id.context_delete:
                // add stuff here
                mBuckets.remove(selectedBucket);

                if (isCurrentBucket) {
                    mCurrentBucket = null;
                    setCurrentBucket(BUCKET_BREAK);
                }
                mAdapter.notifyDataSetChanged();
                return true;
            case R.id.context_reset:
                selectedBucket.duration = 0;
                mAdapter.notifyDataSetChanged();

                if (isCurrentBucket) {
                    mCurrentBucket = null;
                    setCurrentBucket(selectedBucket);
                }
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    private Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {

        }
    };

    private BucketsHandler mHandler = new BucketsHandler();

    class BucketsHandler extends Handler {
        static final int MESSAGE_UPDATE_CURRENT_DURATION = 0;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_UPDATE_CURRENT_DURATION:
                    if (mCurrentBucket == null || mCurrentBucket.name.equals(BUCKET_BREAK.name) || mAdapter == null || isFinishing())
                        return;

                    onTick();
                    mAdapter.notifyDataSetChanged();
                    mHandler.sendEmptyMessageDelayed(MESSAGE_UPDATE_CURRENT_DURATION, 1000);
            }
        }
    }


    public class TimerBucketsAdapter extends ArrayAdapter {
        public TimerBucketsAdapter(Context context, int resource, List objects) {
            super(context, resource, objects);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewGroup row;
            if (convertView == null) {
                LayoutInflater inflater = (LayoutInflater) getApplicationContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                row = (ViewGroup) inflater.inflate(R.layout.list_row, parent, false);
            } else {
                row = (ViewGroup) convertView;
            }

            TextView tv1 = (TextView) row.findViewById(R.id.lbl1);
            TextView tv2 = (TextView) row.findViewById(R.id.lbl2);

            tv1.setText(((Bucket) getItem(position)).name);
            long elapsed = ((Bucket) getItem(position)).duration;

            if (mListView.isItemChecked(position)) {
                row.setBackgroundColor(HIGHLIGHT_COLOR);

                if (position != 0 && !isFinishing()) {
                    mHandler.removeMessages(BucketsHandler.MESSAGE_UPDATE_CURRENT_DURATION);
                    mHandler.sendEmptyMessageDelayed(BucketsHandler.MESSAGE_UPDATE_CURRENT_DURATION, UPDATE_TICK_MS);
                }
            } else {
                row.setBackgroundColor(Color.TRANSPARENT);
            }

            String text = elapsed > 0 && position != 0 ?
                    getString(R.string.time_elapsed, mElapsedFormat.format(new Date(elapsed))) : "";
            tv2.setText(text);

            return row;
        }
    }
}
