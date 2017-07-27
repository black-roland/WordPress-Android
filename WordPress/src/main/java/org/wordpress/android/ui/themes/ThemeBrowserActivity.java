package org.wordpress.android.ui.themes;

import android.app.AlertDialog;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;

import com.android.volley.AuthFailureError;
import com.android.volley.VolleyError;
import com.wordpress.rest.RestRequest.ErrorListener;
import com.wordpress.rest.RestRequest.Listener;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.analytics.AnalyticsTracker;
import org.wordpress.android.datasets.ThemeTable;
import org.wordpress.android.fluxc.model.SiteModel;
import org.wordpress.android.models.Theme;
import org.wordpress.android.ui.ActivityId;
import org.wordpress.android.ui.themes.ThemeBrowserFragment.ThemeBrowserFragmentCallback;
import org.wordpress.android.util.AnalyticsUtils;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;
import org.wordpress.android.util.NetworkUtils;
import org.wordpress.android.util.SiteUtils;
import org.wordpress.android.util.ToastUtils;
import org.wordpress.android.widgets.WPAlertDialogFragment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * The theme browser.
 */
public class ThemeBrowserActivity extends AppCompatActivity implements ThemeBrowserFragmentCallback {
    public static final int THEME_FETCH_MAX = 100;
    public static final int ACTIVATE_THEME = 1;
    public static final String THEME_ID = "theme_id";
    private static final String IS_IN_SEARCH_MODE = "is_in_search_mode";
    private static final String ALERT_TAB = "alert";

    private boolean mFetchingThemes = false;
    private boolean mIsRunning;
    private ThemeBrowserFragment mThemeBrowserFragment;
    private ThemeSearchFragment mThemeSearchFragment;
    private Theme mCurrentTheme;
    private boolean mIsInSearchMode;

    private SiteModel mSite;

    public static boolean isAccessible(SiteModel site) {
        // themes are only accessible to WP.com REST consuming sites as a user with appropriate capabilities
        return site != null && SiteUtils.isAccessedViaWPComRest(site) && site.getHasCapabilityEditThemeOptions();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState == null) {
            mSite = (SiteModel) getIntent().getSerializableExtra(WordPress.SITE);
        } else {
            mSite = (SiteModel) savedInstanceState.getSerializable(WordPress.SITE);
        }

        if (mSite == null) {
            ToastUtils.showToast(this, R.string.blog_not_found, ToastUtils.Duration.SHORT);
            finish();
            return;
        }

        setContentView(R.layout.theme_browser_activity);

        if (savedInstanceState == null) {
            AnalyticsUtils.trackWithSiteDetails(AnalyticsTracker.Stat.THEMES_ACCESSED_THEMES_BROWSER, mSite);
            mThemeBrowserFragment = ThemeBrowserFragment.newInstance(mSite);
            mThemeSearchFragment = ThemeSearchFragment.newInstance(mSite);
            addBrowserFragment();
        }

        setCurrentThemeFromDB();
        showToolbar();
    }

    @Override
    protected void onResume() {
        super.onResume();
        showCorrectToolbar();
        mIsRunning = true;
        ActivityId.trackLastActivity(ActivityId.THEMES);

        fetchThemesIfNoneAvailable();
        fetchPurchasedThemes();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mIsRunning = false;
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        mIsInSearchMode = savedInstanceState.getBoolean(IS_IN_SEARCH_MODE);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(IS_IN_SEARCH_MODE, mIsInSearchMode);
        outState.putSerializable(WordPress.SITE, mSite);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int i = item.getItemId();
        if (i == android.R.id.home) {
            onBackPressed();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        FragmentManager fm = getFragmentManager();
        if (fm.getBackStackEntryCount() > 0) {
            fm.popBackStack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == ACTIVATE_THEME && resultCode == RESULT_OK && data != null) {
            String themeId = data.getStringExtra(THEME_ID);
            if (!TextUtils.isEmpty(themeId)) {
                activateTheme(themeId);
            }
        }
    }

    @Override
    public void onActivateSelected(String themeId) {
        activateTheme(themeId);
    }

    @Override
    public void onTryAndCustomizeSelected(String themeId) {
        startWebActivity(themeId, ThemeWebActivity.ThemeWebActivityType.PREVIEW);
    }

    @Override
    public void onViewSelected(String themeId) {
        startWebActivity(themeId, ThemeWebActivity.ThemeWebActivityType.DEMO);
    }

    @Override
    public void onDetailsSelected(String themeId) {
        startWebActivity(themeId, ThemeWebActivity.ThemeWebActivityType.DETAILS);
    }

    @Override
    public void onSupportSelected(String themeId) {
        startWebActivity(themeId, ThemeWebActivity.ThemeWebActivityType.SUPPORT);
    }

    @Override
    public void onSearchClicked() {
        mIsInSearchMode = true;
        AnalyticsUtils.trackWithSiteDetails(AnalyticsTracker.Stat.THEMES_ACCESSED_SEARCH, mSite);
        addSearchFragment();
    }

    public void setIsInSearchMode(boolean isInSearchMode) {
        mIsInSearchMode = isInSearchMode;
    }

    public void fetchThemes() {
        if (mFetchingThemes) {
            return;
        }
        mFetchingThemes = true;
        int page = 1;
        if (mThemeBrowserFragment != null) {
            page = mThemeBrowserFragment.getPage();
        }
        WordPress.getRestClientUtilsV1_2().getFreeThemes(mSite.getSiteId(), THEME_FETCH_MAX, page, new Listener() {
                    @Override
                    public void onResponse(JSONObject response) {
                        new DeserializeThemesRestResponse().execute(response);
                    }
                }, new ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError response) {
                        if (response.toString().equals(AuthFailureError.class.getName())) {
                            handleAuthError();
                        } else {
                            ToastUtils.showToast(ThemeBrowserActivity.this, R.string.theme_fetch_failed,
                                    ToastUtils.Duration.LONG);
                            AppLog.d(T.THEMES, getString(R.string.theme_fetch_failed) + ": " + response.toString());
                        }
                        mFetchingThemes = false;
                    }
                }
        );
    }

    public void searchThemes(String searchTerm) {
        mFetchingThemes = true;
        int page = 1;
        if (mThemeSearchFragment != null) {
            page = mThemeSearchFragment.getPage();
        }

        WordPress.getRestClientUtilsV1_2().getFreeSearchThemes(mSite.getSiteId(), THEME_FETCH_MAX, page, searchTerm,
                new Listener() {
                    @Override
                    public void onResponse(JSONObject response) {
                        new DeserializeThemesRestResponse().execute(response);
                    }
                }, new ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError response) {
                        if (response.toString().equals(AuthFailureError.class.getName())) {
                            handleAuthError();
                        }
                        mFetchingThemes = false;
                    }
                }
        );
    }

    public void fetchCurrentTheme() {
        WordPress.getRestClientUtilsV1_1().getCurrentTheme(mSite.getSiteId(), new Listener() {
                    @Override
                    public void onResponse(JSONObject response) {
                        try {
                            mCurrentTheme = Theme.fromJSONV1_1(response, mSite);
                            if (mCurrentTheme == null) {
                                return;
                            }
                            mCurrentTheme.setIsCurrent(true);
                            ThemeTable.saveTheme(WordPress.wpDB.getDatabase(), mCurrentTheme);
                            ThemeTable.setCurrentTheme(WordPress.wpDB.getDatabase(), String.valueOf(mSite.getSiteId()),
                                    mCurrentTheme.getId());
                            if (mThemeBrowserFragment != null) {
                                mThemeBrowserFragment.setRefreshing(false);
                                if (mThemeBrowserFragment.getCurrentThemeTextView() != null) {
                                    mThemeBrowserFragment.getCurrentThemeTextView().setText(mCurrentTheme.getName());
                                    mThemeBrowserFragment.setCurrentThemeId(mCurrentTheme.getId());
                                }
                            }
                            if (mThemeSearchFragment != null && mThemeSearchFragment.isVisible()) {
                                mThemeSearchFragment.setRefreshing(false);
                            }
                        } catch (JSONException e) {
                            AppLog.e(T.THEMES, e);
                        }
                    }
                }, new ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError response) {
                        String themeId = ThemeTable.getCurrentThemeId(WordPress.wpDB.getDatabase(),
                                String.valueOf(mSite.getSiteId()));
                        mCurrentTheme = ThemeTable.getTheme(WordPress.wpDB.getDatabase(),
                                String.valueOf(mSite.getSiteId()), themeId);
                        if (mCurrentTheme != null && mThemeBrowserFragment != null) {
                            if (mThemeBrowserFragment.getCurrentThemeTextView() != null) {
                                mThemeBrowserFragment.getCurrentThemeTextView().setText(mCurrentTheme.getName());
                                mThemeBrowserFragment.setCurrentThemeId(mCurrentTheme.getId());
                            }
                        }
                    }
                }
        );
    }

    protected Theme getCurrentTheme() {
        return mCurrentTheme;
    }

    protected void setThemeBrowserFragment(ThemeBrowserFragment themeBrowserFragment) {
        mThemeBrowserFragment = themeBrowserFragment;
    }

    protected void setThemeSearchFragment(ThemeSearchFragment themeSearchFragment) {
        mThemeSearchFragment = themeSearchFragment;
    }

    protected void showToolbar() {
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayShowTitleEnabled(true);
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.themes);
            findViewById(R.id.toolbar).setVisibility(View.VISIBLE);
            findViewById(R.id.toolbar_search).setVisibility(View.GONE);
        }
    }

    private void setCurrentThemeFromDB() {
        mCurrentTheme = ThemeTable.getCurrentTheme(WordPress.wpDB.getDatabase(), String.valueOf(mSite.getSiteId()));
    }

    private void fetchThemesIfNoneAvailable() {
        if (NetworkUtils.isNetworkAvailable(this)
                && ThemeTable.getThemeCount(WordPress.wpDB.getDatabase(), String.valueOf(mSite.getSiteId())) == 0) {
            fetchThemes();
            //do not interact with theme browser fragment if we are in search mode
            if (!mIsInSearchMode) {
                mThemeBrowserFragment.setRefreshing(true);
            }
        }
    }

    private void fetchPurchasedThemes() {
        if (NetworkUtils.isNetworkAvailable(this)) {
            WordPress.getRestClientUtilsV1_1().getPurchasedThemes(mSite.getSiteId(), new Listener() {
                @Override
                public void onResponse(JSONObject response) {
                    new DeserializeThemesRestResponse().execute(response);
                }
            }, new ErrorListener() {
                @Override
                public void onErrorResponse(VolleyError error) {
                    AppLog.d(T.THEMES, error.getMessage());
                }
            });

            //do not interact with theme browser fragment if we are in search mode
            if (!mIsInSearchMode) {
                mThemeBrowserFragment.setRefreshing(true);
            }
        }
    }

    private void showCorrectToolbar() {
        if (mIsInSearchMode) {
            showSearchToolbar();
        } else {
            hideSearchToolbar();
        }
    }

    private void showSearchToolbar() {
        Toolbar toolbarSearch = (Toolbar) findViewById(R.id.toolbar_search);
        setSupportActionBar(toolbarSearch);
        toolbarSearch.setTitle("");
        findViewById(R.id.toolbar).setVisibility(View.GONE);
        findViewById(R.id.toolbar_search).setVisibility(View.VISIBLE);
    }

    private void hideSearchToolbar() {
        findViewById(R.id.toolbar).setVisibility(View.VISIBLE);
        findViewById(R.id.toolbar_search).setVisibility(View.GONE);
    }

    private void addBrowserFragment() {
        if (mThemeBrowserFragment == null) {
            mThemeBrowserFragment = new ThemeBrowserFragment();
        }
        showToolbar();
        FragmentTransaction fragmentTransaction = getFragmentManager().beginTransaction();
        fragmentTransaction.add(R.id.theme_browser_container, mThemeBrowserFragment);
        fragmentTransaction.commit();
    }

    private void addSearchFragment() {
        if (mThemeSearchFragment == null) {
            mThemeSearchFragment = ThemeSearchFragment.newInstance(mSite);
        }
        showSearchToolbar();
        FragmentTransaction fragmentTransaction = getFragmentManager().beginTransaction();
        fragmentTransaction.replace(R.id.theme_browser_container, mThemeSearchFragment);
        fragmentTransaction.addToBackStack(null);
        fragmentTransaction.commit();
    }

    private void activateTheme(final String themeId) {
        WordPress.getRestClientUtils().setTheme(mSite.getSiteId(), themeId, new Listener() {
            @Override
            public void onResponse(JSONObject response) {
                ThemeTable.setCurrentTheme(WordPress.wpDB.getDatabase(), String.valueOf(mSite.getSiteId()), themeId);
                Theme newTheme = ThemeTable.getTheme(WordPress.wpDB.getDatabase(),
                        String.valueOf(mSite.getSiteId()), themeId);

                Map<String, Object> themeProperties = new HashMap<>();
                themeProperties.put(THEME_ID, themeId);
                AnalyticsUtils.trackWithSiteDetails(AnalyticsTracker.Stat.THEMES_CHANGED_THEME, mSite, themeProperties);

                if (!isFinishing()) {
                    showAlertDialogOnNewSettingNewTheme(newTheme);
                    fetchCurrentTheme();
                }
            }
        }, new ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                ToastUtils.showToast(ThemeBrowserActivity.this, R.string.theme_activation_error,
                        ToastUtils.Duration.SHORT);
            }
        });
    }

    private void showAlertDialogOnNewSettingNewTheme(Theme newTheme) {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this);

        String thanksMessage = String.format(getString(R.string.theme_prompt), newTheme.getName());
        if (!newTheme.getAuthor().isEmpty()) {
            String append = String.format(getString(R.string.theme_by_author_prompt_append), newTheme.getAuthor());
            thanksMessage = thanksMessage + " " + append;
        }

        dialogBuilder.setMessage(thanksMessage);
        dialogBuilder.setNegativeButton(R.string.theme_done, null);
        dialogBuilder.setPositiveButton(R.string.theme_manage_site, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                finish();
            }
        });

        AlertDialog alertDialog = dialogBuilder.create();
        alertDialog.show();
    }

    private void startWebActivity(String themeId, ThemeWebActivity.ThemeWebActivityType type) {
        String toastText = getString(R.string.no_network_message);

        if (NetworkUtils.isNetworkAvailable(this)) {
            if (mCurrentTheme != null && !TextUtils.isEmpty(themeId)) {
                boolean isCurrentTheme = mCurrentTheme.getId().equals(themeId);
                Map<String, Object> themeProperties = new HashMap<>();
                themeProperties.put(THEME_ID, themeId);

                switch (type) {
                    case PREVIEW:
                        AnalyticsUtils.trackWithSiteDetails(AnalyticsTracker.Stat.THEMES_PREVIEWED_SITE,
                                mSite, themeProperties);
                        break;
                    case DEMO:
                        AnalyticsUtils.trackWithSiteDetails(AnalyticsTracker.Stat.THEMES_DEMO_ACCESSED,
                                mSite, themeProperties);
                        break;
                    case DETAILS:
                        AnalyticsUtils.trackWithSiteDetails(AnalyticsTracker.Stat.THEMES_DETAILS_ACCESSED,
                                mSite, themeProperties);
                        break;
                    case SUPPORT:
                        AnalyticsUtils.trackWithSiteDetails(AnalyticsTracker.Stat.THEMES_SUPPORT_ACCESSED,
                                mSite, themeProperties);
                        break;
                }
                ThemeWebActivity.openTheme(this, mSite, themeId, type, isCurrentTheme);
                return;
            } else {
                toastText = getString(R.string.could_not_load_theme);
            }
        }

        ToastUtils.showToast(this, toastText, ToastUtils.Duration.SHORT);
    }

    private void handleAuthError() {
        String errorTitle = getString(R.string.theme_auth_error_title);
        String errorMsg = getString(R.string.theme_auth_error_message);

        if (mIsRunning) {
            FragmentTransaction ft = getFragmentManager().beginTransaction();
            WPAlertDialogFragment fragment = WPAlertDialogFragment.newAlertDialog(errorMsg,
                    errorTitle);
            ft.add(fragment, ALERT_TAB);
            ft.commitAllowingStateLoss();
        }
        AppLog.d(T.THEMES, getString(R.string.theme_auth_error_authenticate));
    }

    /**
     * Converts themes from a JSON REST response object to a list of {@link Theme}'s and saves them to local database.
     * A {@link JSONObject} containing a {@link JSONArray} with key "themes" is expected.
     */
    private class DeserializeThemesRestResponse extends AsyncTask<JSONObject, Void, ArrayList<Theme>> {
        @Override
        protected ArrayList<Theme> doInBackground(JSONObject... args) {
            if (args[0] == null) {
                return null;
            }

            // JSONArray of themes is required
            final JSONArray array = args[0].optJSONArray("themes");
            if (array == null) {
                return null;
            }

            // convert JSON themes to Theme models and store them in database
            final ArrayList<Theme> themes = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                try {
                    JSONObject object = array.getJSONObject(i);
                    Theme theme = Theme.fromJSONV1_2(object, mSite);
                    if (theme != null) {
                        ThemeTable.saveTheme(WordPress.wpDB.getDatabase(), theme);
                        themes.add(theme);
                    }
                } catch (JSONException e) {
                    AppLog.e(T.THEMES, e);
                }
            }

            fetchCurrentTheme();

            return themes.size() > 0 ? themes : null;
        }

        @Override
        protected void onPostExecute(final ArrayList<Theme> result) {
            mFetchingThemes = false;
            if (mThemeBrowserFragment != null && mThemeBrowserFragment.isVisible()) {
                mThemeBrowserFragment.getEmptyTextView().setText(R.string.theme_no_search_result_found);
                mThemeBrowserFragment.setRefreshing(false);
            } else if (mThemeSearchFragment != null && mThemeSearchFragment.isVisible()) {
                mThemeSearchFragment.getEmptyTextView().setText(R.string.theme_no_search_result_found);
                mThemeSearchFragment.setRefreshing(false);
            }
        }
    }
}
