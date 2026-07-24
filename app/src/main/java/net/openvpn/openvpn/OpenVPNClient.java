package net.openvpn.openvpn;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.text.method.PasswordTransformationMethod;
import android.text.method.SingleLineTransformationMethod;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import net.openvpn.openvpn.OpenVPNService.Challenge;
import net.openvpn.openvpn.OpenVPNService.ConnectionStats;
import net.openvpn.openvpn.OpenVPNService.EventMsg;
import net.openvpn.openvpn.OpenVPNService.Profile;
import net.openvpn.openvpn.OpenVPNService.ProfileList;

public class OpenVPNClient extends OpenVPNClientBase 
        implements ActivityCompat.OnRequestPermissionsResultCallback, 
                   View.OnClickListener, View.OnTouchListener, 
                   AdapterView.OnItemSelectedListener, TextView.OnEditorActionListener {

    private static final String TAG = "OpenVPNClient";
    private static final int REQUEST_VPN_ACTOR_RIGHTS = 1;
    private static final int REQUEST_IMPORT_PROFILE = 2;
    private static final int REQUEST_IMPORT_PKCS12 = 3;

    private static final int UIF_RESET = 65536;
    private static final int UIF_REFLECTED = 131072;
    private static final int UIF_PROFILE_SETTING_FROM_SPINNER = 262144;

    private static final int S_BIND_CALLED = 1;
    private static final int S_ONSTART_CALLED = 2;

    private enum FinishOnConnect { DISABLED, ENABLED, ENABLED_ACROSS_ONSTART, PENDING }
    private enum ProfileSource { UNDEF, SERVICE, PRIORITY, PREFERENCES, SPINNER, LIST0 }

    // UI Elements
    private ScrollView main_scroll_view;
    private View post_import_help_blurb, profile_group, proxy_group, server_group;
    private View username_group, password_group, pk_password_group, cr_group;
    private View conn_details_group, stats_group, stats_expansion_group, info_group, button_group;

    private Spinner profile_spin, proxy_spin, server_spin;
    private ImageButton profile_edit, proxy_edit;
    private TextView challenge_view, status_view, details_more_less;
    private TextView last_pkt_recv_view, duration_view, bytes_in_view, bytes_out_view;
    private EditText username_edit, password_edit, pk_password_edit, response_edit;
    private CheckBox password_save_checkbox, pk_password_save_checkbox;
    private ImageView status_icon_view;
    private ProgressBar progress_bar;
    private Button connect_button, disconnect_button;

    private View[] textgroups;
    private EditText[] textviews;

    // Controllers & Helpers
    private PrefUtil prefs;
    private PasswordUtil pwds;
    private String autostart_profile_name;
    private int startup_state = 0;
    private boolean last_active = false;
    private boolean stop_service_on_client_exit = false;

    private FinishOnConnect finish_on_connect = FinishOnConnect.DISABLED;
    private FinishOnConnect delayed_finish_on_connect = FinishOnConnect.DISABLED;

    private final Handler stats_timer_handler = new Handler();
    private final Runnable stats_timer_task = new Runnable() {
        @Override
        public void run() {
            show_stats();
            schedule_stats();
        }
    };

    private final Handler ui_reset_timer_handler = new Handler();
    private final Runnable ui_reset_timer_task = new Runnable() {
        @Override
        public void run() {
            if (!is_active()) {
                ui_setup(false, UIF_RESET, null);
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, String.format("CLI: onCreate intent=%s", getIntent()));

        this.prefs = new PrefUtil(PreferenceManager.getDefaultSharedPreferences(this));
        this.pwds = new PasswordUtil(PreferenceManager.getDefaultSharedPreferences(this));
        init_default_preferences(this.prefs);

        setTheme(this.prefs.get_boolean("ui_dark_theme", false) ? android.R.style.Theme_Holo : android.R.style.Theme_Holo_Light);
        setContentView(R.layout.form);

        load_ui_elements();
        doBindService();
        warn_app_expiration(this.prefs);
        new AppRate(this).setMinDaysUntilPrompt(14).setMinLaunchesUntilPrompt(10).init();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d(TAG, String.format("CLI: onNewIntent intent=%s", intent));
        setIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "CLI: onStart");
        this.startup_state |= S_ONSTART_CALLED;

        if (this.finish_on_connect == FinishOnConnect.ENABLED) {
            this.finish_on_connect = FinishOnConnect.ENABLED_ACROSS_ONSTART;
        }

        boolean active = is_active();
        if (active) schedule_stats();
        if (process_autostart_intent(active)) {
            ui_setup(active, UIF_RESET, null);
        }
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "CLI: onStop");
        cancel_stats();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stop();
        Log.d(TAG, "CLI: onDestroy called");
        super.onDestroy();
    }

    protected void post_bind() {
        Log.d(TAG, "CLI: post bind");
        this.startup_state |= S_BIND_CALLED;
        process_autostart_intent(is_active());
        render_last_event();
    }

    private void stop() {
        cancel_stats();
        doUnbindService();
        if (this.stop_service_on_client_exit) {
            Log.d(TAG, "CLI: stopping service");
            submitDisconnectIntent(true);
        }
    }

    // --- Options & Context Menus ---

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item == null) return false;
        int id = item.getItemId();

        if (id == R.id.about_menu) startActivityForResult(new Intent(this, OpenVPNAbout.class), 0);
        else if (id == R.id.help_menu) startActivityForResult(new Intent(this, OpenVPNHelp.class), 0);
        else if (id == R.id.import_private_tunnel_profile) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(getText(R.string.privatetunnel_import).toString())));
        else if (id == R.id.import_profile_remote) startActivityForResult(new Intent(this, OpenVPNImportProfile.class), 0);
        else if (id == R.id.import_profile) request_file_selection_dialog(REQUEST_IMPORT_PROFILE);
        else if (id == R.id.import_pkcs12) request_file_selection_dialog(REQUEST_IMPORT_PKCS12);
        else if (id == R.id.preferences) startActivityForResult(new Intent(this, OpenVPNPrefs.class), 0);
        else if (id == R.id.add_proxy) startActivityForResult(new Intent(this, OpenVPNAddProxy.class), 0);
        else if (id == R.id.add_shortcut_connect) startActivityForResult(new Intent(this, OpenVPNAddShortcut.class), 0);
        else if (id == R.id.add_shortcut_disconnect) createDisconnectShortcut(resString(R.string.disconnect_shortcut_title));
        else if (id == R.id.add_shortcut_app) createConnectShortcut(selected_profile_name(), resString(R.string.app_shortcut_title));
        else if (id == R.id.show_log) startActivityForResult(new Intent(this, OpenVPNLog.class), 0);
        else if (id == R.id.show_raw_stats) startActivityForResult(new Intent(this, OpenVPNStats.class), 0);
        else if (id == R.id.forget_creds) forget_creds_with_confirm();
        else if (id == R.id.exit_partial) finish();
        else if (id == R.id.exit_full) {
            this.stop_service_on_client_exit = true;
            finish();
        } else return super.onOptionsItemSelected(item);

        return true;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        if (v == null || menu == null || is_active()) return;
        super.onCreateContextMenu(menu, v, menuInfo);

        int viewId = v.getId();
        if (viewId == R.id.profile || viewId == R.id.profile_edit) {
            Profile prof = selected_profile();
            if (prof != null) {
                String name = prof.get_name();
                menu.setHeaderTitle(name);
                menu_add(menu, R.string.profile_context_menu_change_profile, SpinUtil.get_spinner_count(this.profile_spin) > 1, null);
                menu_add(menu, R.string.profile_context_menu_create_shortcut, true, name);
                menu_add(menu, R.string.profile_context_menu_delete, prof.is_deleteable(), name);
                menu_add(menu, R.string.profile_context_menu_rename, prof.is_renameable(), name);
                menu_add(menu, R.string.profile_context_forget_creds, true, name);
            } else {
                menu.setHeaderTitle(R.string.profile_context_none_selected);
            }
            menu_add(menu, R.string.profile_context_cancel, true, null);

        } else if (viewId == R.id.proxy || viewId == R.id.proxy_edit) {
            ProxyList proxyList = get_proxy_list();
            if (proxyList != null) {
                String proxyName = proxyList.get_enabled(true);
                boolean isNone = proxyList.is_none(proxyName);
                menu.setHeaderTitle(proxyName);
                menu_add(menu, R.string.proxy_context_change_proxy, SpinUtil.get_spinner_count(this.proxy_spin) > 1, null);
                menu_add(menu, R.string.proxy_context_edit, !isNone, proxyName);
                menu_add(menu, R.string.proxy_context_delete, !isNone, proxyName);
                menu_add(menu, R.string.proxy_context_forget_creds, proxyList.has_saved_creds(proxyName), proxyName);
            } else {
                menu.setHeaderTitle(R.string.proxy_context_none_selected);
            }
            menu_add(menu, R.string.proxy_context_cancel, true, null);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        if (item == null) return false;
        int itemId = item.getItemId();
        String key = get_menu_key(item);

        if (itemId == R.string.profile_context_forget_creds) {
            ProfileList list = profile_list();
            if (list != null) {
                Profile prof = list.get_profile_by_name(key);
                if (prof != null) {
                    this.pwds.remove("pk", prof.get_name());
                    this.pwds.remove("auth", prof.get_name());
                    prof.forget_cert();
                    ui_setup(is_active(), UIF_RESET, null);
                }
            }
        } else if (itemId == R.string.profile_context_menu_change_profile) {
            if (this.profile_spin != null) this.profile_spin.performClick();
        } else if (itemId == R.string.profile_context_menu_create_shortcut) {
            if (key != null) launch_create_profile_shortcut_dialog(key);
        } else if (itemId == R.string.profile_context_menu_delete) {
            if (key != null) submitDeleteProfileIntentWithConfirm(key);
        } else if (itemId == R.string.profile_context_menu_rename) {
            if (key != null) launch_rename_profile_dialog(key);
        } else if (itemId == R.string.proxy_context_change_proxy) {
            if (this.proxy_spin != null) this.proxy_spin.performClick();
        } else if (itemId == R.string.proxy_context_delete) {
            delete_proxy_with_confirm(key);
        } else if (itemId == R.string.proxy_context_edit) {
            if (key != null) startActivityForResult(new Intent(this, OpenVPNAddProxy.class).putExtra("net.openvpn.openvpn.PROXY_NAME", key), 0);
        } else if (itemId == R.string.proxy_context_forget_creds) {
            ProxyList proxyList = get_proxy_list();
            if (proxyList != null) {
                proxyList.forget_creds(key);
                proxyList.save();
            }
        } else return itemId == R.string.profile_context_cancel || itemId == R.string.proxy_context_cancel;

        return true;
    }

    // --- Connection & Event Methods ---

    public void event(EventMsg ev) {
        render_event(ev, false, is_active(), false);
    }

    private void render_last_event() {
        boolean active = is_active();
        EventMsg ev = get_last_event();
        if (ev != null) {
            render_event(ev, true, active, true);
        } else if (n_profiles_loaded() > 0) {
            render_event(EventMsg.disconnected(), true, active, true);
        } else {
            hide_status();
            ui_setup(active, UIF_RESET, null);
            show_progress(0, active);
        }
        EventMsg pev = get_last_event_prof_manage();
        if (pev != null) render_event(pev, true, active, true);
    }

    private void render_event(EventMsg ev, boolean reset, boolean active, boolean cached) {
        if (ev == null) return;
        int flags = ev.flags | (ev.is_reflected(this) ? UIF_REFLECTED : 0);

        if (reset || (flags & 8) != 0 || ev.profile_override != null) {
            ui_setup(active, UIF_RESET | flags, ev.profile_override);
        } else if (ev.res_id == R.string.core_thread_active) {
            ui_setup(true, flags, null);
            active = true;
        } else if (ev.res_id == R.string.core_thread_inactive) {
            ui_setup(false, flags, null);
            active = false;
        }

        switch (ev.res_id) {
            case R.string.connected:
                if (this.main_scroll_view != null) this.main_scroll_view.fullScroll(ScrollView.FOCUS_UP);
                break;
            case R.string.info_msg:
                if (ev.info != null && ev.info.startsWith("OPEN_URL:")) {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(ev.info.substring(9)));
                    intent.putExtra("com.android.browser.application_id", getPackageName());
                    if (intent.resolveActivity(getPackageManager()) != null) startActivity(intent);
                }
                break;
            case R.string.tap_not_supported:
                if (!cached) ok_dialog(resString(R.string.tap_unsupported_title), resString(R.string.tap_unsupported_error));
                break;
            case R.string.tun_iface_create:
                if (!cached) ok_dialog(resString(R.string.tun_ko_title), resString(R.string.tun_ko_error));
                break;
            case R.string.warn_msg:
                this.delayed_finish_on_connect = FinishOnConnect.PENDING;
                ok_dialog(resString(R.string.warning_title), ev.info, new Runnable() {
                    @Override
                    public void run() {
                        if (delayed_finish_on_connect != FinishOnConnect.PENDING && delayed_finish_on_connect != FinishOnConnect.DISABLED) {
                            finish();
                        }
                        delayed_finish_on_connect = FinishOnConnect.DISABLED;
                    }
                });
                break;
        }

        if (ev.priority >= 1) {
            if (ev.icon_res_id >= 0) show_status_icon(ev.icon_res_id);
            if (ev.res_id == R.string.connected) {
                show_status(ev.res_id);
                if (ev.conn_info != null) show_conn_info(ev.conn_info);
            } else if (ev.info != null && !ev.info.isEmpty()) {
                show_status(String.format("%s : %s", resString(ev.res_id), ev.info));
            } else {
                show_status(ev.res_id);
            }
        }

        show_progress(ev.progress, active);
        show_stats();

        if (ev.res_id == R.string.connected && this.finish_on_connect != FinishOnConnect.DISABLED) {
            if (this.prefs.get_boolean("autostart_finish_on_connect", false)) {
                if (this.delayed_finish_on_connect == FinishOnConnect.PENDING) {
                    this.delayed_finish_on_connect = this.finish_on_connect;
                    return;
                }
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (finish_on_connect != FinishOnConnect.DISABLED) finish();
                    }
                }, 1000);
                return;
            }
            this.finish_on_connect = FinishOnConnect.DISABLED;
        }
    }

    private void start_connect() {
        cancel_ui_reset();
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            try {
                Log.d(TAG, "CLI: requesting VPN actor rights");
                startActivityForResult(intent, REQUEST_VPN_ACTOR_RIGHTS);
                return;
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "CLI: requesting VPN actor rights failed", e);
                ok_dialog(resString(R.string.vpn_permission_dialog_missing_title), resString(R.string.vpn_permission_dialog_missing_text));
                return;
            }
        }
        Log.d(TAG, "CLI: app is already authorized as VPN actor");
        resolve_epki_alias_then_connect();
    }

    private void do_connect(String epki_alias) {
        String profile_name = selected_profile_name();
        String proxy_name = (this.proxy_group != null && this.proxy_group.getVisibility() == View.VISIBLE && get_proxy_list() != null) ? get_proxy_list().get_enabled(false) : null;
        String server = (this.server_group != null && this.server_group.getVisibility() == View.VISIBLE) ? SpinUtil.get_spinner_selected_item(this.server_spin) : null;
        
        String username = null, password = null, pk_password = null, response = null;
        boolean is_auth_pwd_save = false;

        if (this.username_group != null && this.username_group.getVisibility() == View.VISIBLE && this.username_edit != null) {
            username = this.username_edit.getText().toString();
            if (!username.isEmpty()) this.prefs.set_string_by_profile(profile_name, "username", username);
        }

        if (this.pk_password_group != null && this.pk_password_group.getVisibility() == View.VISIBLE && this.pk_password_edit != null && this.pk_password_save_checkbox != null) {
            pk_password = this.pk_password_edit.getText().toString();
            boolean save = this.pk_password_save_checkbox.isChecked();
            this.prefs.set_boolean_by_profile(profile_name, "pk_password_save", save);
            if (save) this.pwds.set("pk", profile_name, pk_password);
            else this.pwds.remove("pk", profile_name);
        }

        if (this.password_group != null && this.password_group.getVisibility() == View.VISIBLE && this.password_edit != null && this.password_save_checkbox != null) {
            password = this.password_edit.getText().toString();
            is_auth_pwd_save = this.password_save_checkbox.isChecked();
            this.prefs.set_boolean_by_profile(profile_name, "auth_password_save", is_auth_pwd_save);
            if (is_auth_pwd_save) this.pwds.set("auth", profile_name, password);
            else this.pwds.remove("auth", profile_name);
        }

        if (this.cr_group != null && this.cr_group.getVisibility() == View.VISIBLE && this.response_edit != null) {
            response = this.response_edit.getText().toString();
        }

        clear_auth();
        clear_stats();

        submitConnectIntent(profile_name, server, 
                this.prefs.get_string("vpn_proto"), 
                this.prefs.get_string("ipv6"), 
                this.prefs.get_string("conn_timeout"), 
                username, password, is_auth_pwd_save, pk_password, response, 
                epki_alias, this.prefs.get_string("compression_mode"), 
                proxy_name, null, null, true, get_gui_version("net.openvpn.connect.android"));
    }

    // --- UI Setup & Updates ---

    private void ui_setup(boolean active, int flags, String profile_override) {
        boolean orig_active = active;
        boolean autostart = false;
        cancel_ui_reset();

        if ((UIF_RESET & flags) != 0 || orig_active != this.last_active) {
            clear_auth();
            if (!active && this.autostart_profile_name != null) {
                autostart = true;
                profile_override = this.autostart_profile_name;
                this.autostart_profile_name = null;
            }

            ProfileList proflist = profile_list();
            Profile prof = null;

            if (proflist == null || proflist.size() <= 0) {
                if (this.profile_group != null) this.profile_group.setVisibility(View.GONE);
            } else {
                ProfileSource ps = ProfileSource.UNDEF;
                SpinUtil.show_spinner(this, this.profile_spin, proflist.profile_names());

                if (active) {
                    ps = ProfileSource.SERVICE;
                    prof = current_profile();
                }
                if (prof == null && profile_override != null) {
                    ps = ProfileSource.PRIORITY;
                    prof = proflist.get_profile_by_name(profile_override);
                    if (prof == null) autostart = false;
                }
                if (prof == null) {
                    if ((UIF_PROFILE_SETTING_FROM_SPINNER & flags) != 0) {
                        ps = ProfileSource.SPINNER;
                        prof = proflist.get_profile_by_name(SpinUtil.get_spinner_selected_item(this.profile_spin));
                    } else {
                        ps = ProfileSource.PREFERENCES;
                        prof = proflist.get_profile_by_name(this.prefs.get_string("profile"));
                    }
                }
                if (prof == null) {
                    ps = ProfileSource.LIST0;
                    prof = (Profile) proflist.get(0);
                }

                if (ps != ProfileSource.PREFERENCES && (UIF_REFLECTED & flags) == 0) {
                    this.prefs.set_string("profile", prof.get_name());
                    gen_ui_reset_event(true);
                }
                if (ps != ProfileSource.SPINNER) SpinUtil.set_spinner_selected_item(this.profile_spin, prof.get_name());
                if (this.profile_group != null) this.profile_group.setVisibility(View.VISIBLE);
                if (this.profile_spin != null) this.profile_spin.setEnabled(!active);
                if (this.profile_edit != null) this.profile_edit.setVisibility(active ? View.GONE : View.VISIBLE);
            }

            if (prof != null) {
                if ((UIF_RESET & flags) != 0) prof.reset_dynamic_challenge();
                EditText focus = setup_profile_fields(prof, active, flags);

                if (this.button_group != null) this.button_group.setVisibility(View.VISIBLE);
                if (orig_active) {
                    if (this.conn_details_group != null) this.conn_details_group.setVisibility(View.VISIBLE);
                    if (this.connect_button != null) this.connect_button.setVisibility(View.GONE);
                    if (this.disconnect_button != null) this.disconnect_button.setVisibility(View.VISIBLE);
                } else {
                    if (this.conn_details_group != null) this.conn_details_group.setVisibility(View.GONE);
                    if (this.connect_button != null) this.connect_button.setVisibility(View.VISIBLE);
                    if (this.disconnect_button != null) this.disconnect_button.setVisibility(View.GONE);
                }

                if (focus != null) autostart = false;
                req_focus(focus);
            } else {
                hide_all_input_groups();
                show_status_icon(R.drawable.info);
                show_status(R.string.no_profiles_loaded);
            }

            if (orig_active) schedule_stats();
            else cancel_stats();
        }

        this.last_active = orig_active;
        if (autostart && !this.last_active) {
            this.finish_on_connect = FinishOnConnect.ENABLED;
            start_connect();
        }
    }

    private EditText setup_profile_fields(Profile prof, boolean active, int flags) {
        EditText focus = null;
        if (this.post_import_help_blurb != null) {
            this.post_import_help_blurb.setVisibility(!active && (flags & 32) != 0 ? View.VISIBLE : View.GONE);
        }

        ProxyList proxyList = get_proxy_list();
        if (active || proxyList == null || proxyList.size() <= 0) {
            if (this.proxy_group != null) this.proxy_group.setVisibility(View.GONE);
        } else {
            SpinUtil.show_spinner(this, this.proxy_spin, proxyList.get_name_list(true));
            String name = proxyList.get_enabled(true);
            if (name != null) SpinUtil.set_spinner_selected_item(this.proxy_spin, name);
            if (this.proxy_group != null) this.proxy_group.setVisibility(View.VISIBLE);
        }

        if (active || !prof.server_list_defined()) {
            if (this.server_group != null) this.server_group.setVisibility(View.GONE);
        } else {
            SpinUtil.show_spinner(this, this.server_spin, prof.get_server_list().display_names());
            String server = this.prefs.get_string_by_profile(prof.get_name(), "server");
            if (server != null) SpinUtil.set_spinner_selected_item(this.server_spin, server);
            if (this.server_group != null) this.server_group.setVisibility(View.VISIBLE);
        }

        if (active) {
            if (this.username_group != null) this.username_group.setVisibility(View.GONE);
            if (this.pk_password_group != null) this.pk_password_group.setVisibility(View.GONE);
            if (this.password_group != null) this.password_group.setVisibility(View.GONE);
        } else {
            boolean udef = prof.userlocked_username_defined();
            boolean autologin = prof.get_autologin();
            boolean pk_pwd_req = prof.get_private_key_password_required();
            boolean dynamic_challenge = prof.is_dynamic_challenge();

            if ((!autologin || udef) && !dynamic_challenge) {
                if (udef) {
                    if (this.username_edit != null) {
                        this.username_edit.setText(prof.get_userlocked_username());
                        set_enabled(this.username_edit, false);
                    }
                } else if (this.username_edit != null) {
                    set_enabled(this.username_edit, true);
                    String pref_username = this.prefs.get_string_by_profile(prof.get_name(), "username");
                    if (pref_username != null) this.username_edit.setText(pref_username);
                    else focus = this.username_edit;
                }
                if (this.username_group != null) this.username_group.setVisibility(View.VISIBLE);
            } else if (this.username_group != null) this.username_group.setVisibility(View.GONE);

            if (pk_pwd_req) {
                boolean is_pwd_save = this.prefs.get_boolean_by_profile(prof.get_name(), "pk_password_save", false);
                if (this.pk_password_group != null) this.pk_password_group.setVisibility(View.VISIBLE);
                if (this.pk_password_save_checkbox != null) this.pk_password_save_checkbox.setChecked(is_pwd_save);

                String saved_pwd = is_pwd_save ? this.pwds.get("pk", prof.get_name()) : null;
                if (saved_pwd != null && this.pk_password_edit != null) this.pk_password_edit.setText(saved_pwd);
                else if (focus == null) focus = this.pk_password_edit;
            } else if (this.pk_password_group != null) this.pk_password_group.setVisibility(View.GONE);

            if (autologin || dynamic_challenge) {
                if (this.password_group != null) this.password_group.setVisibility(View.GONE);
            } else {
                boolean is_auth_pw_save = prof.get_allow_password_save();
                boolean is_pwd_save = is_auth_pw_save && this.prefs.get_boolean_by_profile(prof.get_name(), "auth_password_save", false);
                if (this.password_group != null) this.password_group.setVisibility(View.VISIBLE);
                if (this.password_save_checkbox != null) {
                    this.password_save_checkbox.setEnabled(is_auth_pw_save);
                    this.password_save_checkbox.setChecked(is_pwd_save);
                }
                String saved_pwd = is_pwd_save ? this.pwds.get("auth", prof.get_name()) : null;
                if (saved_pwd != null && this.password_edit != null) this.password_edit.setText(saved_pwd);
                else if (focus == null) focus = this.password_edit;
            }
        }

        if (active || prof.get_autologin() || !prof.challenge_defined()) {
            if (this.cr_group != null) this.cr_group.setVisibility(View.GONE);
        } else {
            if (this.cr_group != null) this.cr_group.setVisibility(View.VISIBLE);
            Challenge chal = prof.get_challenge();
            if (this.challenge_view != null && chal != null) {
                this.challenge_view.setText(chal.get_challenge());
                this.challenge_view.setVisibility(View.VISIBLE);
            }
            if (chal != null && chal.get_response_required()) {
                if (this.response_edit != null) {
                    this.response_edit.setTransformationMethod(chal.get_echo() ? SingleLineTransformationMethod.getInstance() : PasswordTransformationMethod.getInstance());
                    this.response_edit.setVisibility(View.VISIBLE);
                }
                if (focus == null) focus = this.response_edit;
            } else if (this.response_edit != null) this.response_edit.setVisibility(View.GONE);

            if (prof.is_dynamic_challenge()) schedule_ui_reset(prof.get_dynamic_challenge_expire_delay());
        }

        return focus;
    }

    private void hide_all_input_groups() {
        View[] groups = {post_import_help_blurb, proxy_group, server_group, username_group, pk_password_group, password_group, cr_group, conn_details_group, button_group};
        for (View g : groups) {
            if (g != null) g.setVisibility(View.GONE);
        }
    }

    // --- Stats & Helper Display Methods ---

    private void show_stats() {
        if (is_active()) {
            ConnectionStats stats = get_connection_stats();
            if (stats != null) {
                if (this.last_pkt_recv_view != null) this.last_pkt_recv_view.setText(render_last_pkt_recv(stats.last_packet_received));
                if (this.duration_view != null) this.duration_view.setText(OpenVPNClientBase.render_duration(stats.duration));
                if (this.bytes_in_view != null) this.bytes_in_view.setText(render_bandwidth(stats.bytes_in));
                if (this.bytes_out_view != null) this.bytes_out_view.setText(render_bandwidth(stats.bytes_out));
            }
        }
    }

    private void clear_stats() {
        if (this.last_pkt_recv_view != null) this.last_pkt_recv_view.setText("");
        if (this.duration_view != null) this.duration_view.setText("");
        if (this.bytes_in_view != null) this.bytes_in_view.setText("");
        if (this.bytes_out_view != null) this.bytes_out_view.setText("");
        reset_conn_info();
    }

    private static String render_bandwidth(long bw) {
        float bwf = (float) bw;
        if (bwf >= 1.0E12f) return String.format("%.2f TB", bwf / 1.0995116E12f);
        if (bwf >= 1.0E9f) return String.format("%.2f GB", bwf / 1.0737418E9f);
        if (bwf >= 1000000.0f) return String.format("%.2f MB", bwf / 1048576.0f);
        if (bwf >= 1000.0f) return String.format("%.2f KB", bwf / 1024.0f);
        return String.format("%.0f", bwf);
    }

    private String render_last_pkt_recv(int sec) {
        if (sec >= 3600) return resString(R.string.lpr_gt_1_hour_ago);
        if (sec >= 120) return String.format(resString(R.string.lpr_gt_n_min_ago), sec / 60);
        if (sec >= 2) return String.format(resString(R.string.lpr_n_sec_ago), sec);
        if (sec == 1) return resString(R.string.lpr_1_sec_ago);
        return sec == 0 ? resString(R.string.lpr_lt_1_sec_ago) : "";
    }

    private void show_conn_info(ClientAPI_ConnectionInfo ci) {
        if (this.info_group != null && ci != null) {
            boolean vis = show_conn_info_field(ci.getVpnIp4(), R.id.ipv4_addr, R.id.ipv4_addr_row) |
                          show_conn_info_field(ci.getVpnIp6(), R.id.ipv6_addr, R.id.ipv6_addr_row) |
                          show_conn_info_field(ci.getUser(), R.id.user, R.id.user_row) |
                          show_conn_info_field(ci.getClientIp(), R.id.client_ip, R.id.client_ip_row) |
                          show_conn_info_field(ci.getServerHost(), R.id.server_host, R.id.server_host_row) |
                          show_conn_info_field(ci.getServerIp(), R.id.server_ip, R.id.server_ip_row) |
                          show_conn_info_field(ci.getServerPort(), R.id.server_port, R.id.server_port_row) |
                          show_conn_info_field(ci.getServerProto(), R.id.server_proto, R.id.server_proto_row);
            this.info_group.setVisibility(vis ? View.VISIBLE : View.GONE);
            set_visibility_stats_expansion_group();
        }
    }

    private boolean show_conn_info_field(String text, int field_id, int row_id) {
        boolean vis = (text != null && !text.isEmpty());
        TextView tv = (TextView) findViewById(field_id);
        View row = findViewById(row_id);
        if (tv != null) tv.setText(text);
        if (row != null) row.setVisibility(vis ? View.VISIBLE : View.GONE);
        return vis;
    }

    private void set_visibility_stats_expansion_group() {
        boolean expand_stats = this.prefs.get_boolean("expand_stats", false);
        if (this.stats_expansion_group != null) {
            this.stats_expansion_group.setVisibility(expand_stats ? View.VISIBLE : View.GONE);
        }
        if (this.details_more_less != null) {
            this.details_more_less.setText(expand_stats ? R.string.touch_less : R.string.touch_more);
        }
    }

    // --- Click & Touch Handlers ---

    @Override
    public void onClick(View v) {
        if (v == null) return;
        cancel_ui_reset();
        this.autostart_profile_name = null;
        this.finish_on_connect = FinishOnConnect.DISABLED;

        int id = v.getId();
        if (id == R.id.connect) start_connect();
        else if (id == R.id.disconnect) submitDisconnectIntent(false);
        else if (id == R.id.profile_edit || id == R.id.proxy_edit) openContextMenu(v);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (v == null || event == null || v.getId() != R.id.conn_details_boxed || event.getAction() != MotionEvent.ACTION_DOWN) {
            return false;
        }
        boolean current = this.prefs.get_boolean("expand_stats", false);
        this.prefs.set_boolean("expand_stats", !current);
        set_visibility_stats_expansion_group();
        return true;
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
        if (parent == null) return;
        cancel_ui_reset();
        int viewid = parent.getId();

        if (viewid == R.id.profile) {
            ui_setup(is_active(), 327680, null);
        } else if (viewid == R.id.proxy) {
            ProxyList proxyList = get_proxy_list();
            if (proxyList != null) {
                proxyList.set_enabled(SpinUtil.get_spinner_list_item(this.proxy_spin, position));
                proxyList.save();
                gen_ui_reset_event(true);
            }
        } else if (viewid == R.id.server) {
            String server = SpinUtil.get_spinner_list_item(this.server_spin, position);
            this.prefs.set_string_by_profile(SpinUtil.get_spinner_selected_item(this.profile_spin), "server", server);
            gen_ui_reset_event(true);
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> adapterView) { }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        if (v != last_visible_edittext()) return false;
        if (action_enter(actionId, event) && this.connect_button != null && this.connect_button.getVisibility() == View.VISIBLE) {
            onClick(this.connect_button);
        }
        return true;
    }

    // --- Dialogs & Shortcuts Helpers ---

    private void ok_dialog(String title, String message, final Runnable onDismiss) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (onDismiss != null) onDismiss.run();
                    }
                })
                .show();
    }

    protected void ok_dialog(String title, String message) {
    ok_dialog(title, message, null);
}


    private void launch_create_profile_shortcut_dialog(final String prof_name) {
        View view = getLayoutInflater().inflate(R.layout.create_shortcut_dialog, null);
        final EditText name_field = (EditText) view.findViewById(R.id.shortcut_name);
        if (name_field != null) {
            name_field.setText(prof_name);
            name_field.selectAll();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.create_shortcut_title)
                .setView(view)
                .setPositiveButton(R.string.create_shortcut_yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (name_field != null) createConnectShortcut(prof_name, name_field.getText().toString());
                    }
                })
                .setNegativeButton(R.string.create_shortcut_cancel, null)
                .show();
    }

    private void launch_rename_profile_dialog(final String orig_prof_name) {
        View view = getLayoutInflater().inflate(R.layout.rename_profile_dialog, null);
        final EditText name_field = (EditText) view.findViewById(R.id.rename_profile_name);
        if (name_field != null) {
            name_field.setText(orig_prof_name);
            name_field.selectAll();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.rename_profile_title)
                .setView(view)
                .setPositiveButton(R.string.rename_profile_yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (name_field != null) submitRenameProfileIntent(orig_prof_name, name_field.getText().toString());
                    }
                })
                .setNegativeButton(R.string.rename_profile_cancel, null)
                .show();
    }

    private void delete_proxy_with_confirm(final String proxy_name) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.proxy_delete_confirm_title)
                .setMessage(proxy_name)
                .setPositiveButton(R.string.proxy_delete_confirm_yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ProxyList proxyList = get_proxy_list();
                        if (proxyList != null) {
                            proxyList.remove(proxy_name);
                            proxyList.save();
                            gen_ui_reset_event(false);
                        }
                    }
                })
                .setNegativeButton(R.string.proxy_delete_confirm_cancel, null)
                .show();
    }

    private void forget_creds_with_confirm() {
        final Context context = this;
        new AlertDialog.Builder(this)
                .setTitle(R.string.forget_creds_title)
                .setMessage(R.string.forget_creds_message)
                .setPositiveButton(R.string.forget_creds_yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        pwds.regenerate(true);
                        ProfileList proflist = profile_list();
                        if (proflist != null) proflist.forget_certs();
                        TrustMan.forget_certs(context);
                        OpenVPNImportProfile.forget_server_history(prefs);
                        ProxyList proxyList = get_proxy_list();
                        if (proxyList != null) {
                            proxyList.forget_creds();
                            proxyList.save();
                        }
                        ui_setup(is_active(), UIF_RESET, null);
                    }
                })
                .setNegativeButton(R.string.forget_creds_cancel, null)
                .show();
    }

    public void createConnectShortcut(String prof_name, String shortcut_name) {
        Intent shortcutIntent = new Intent(this, OpenVPNClient.class);
        shortcutIntent.setAction(Intent.ACTION_MAIN);
        shortcutIntent.putExtra("net.openvpn.openvpn.AUTOSTART_PROFILE_NAME", prof_name);

        Intent addIntent = new Intent();
        addIntent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent);
        addIntent.putExtra(Intent.EXTRA_SHORTCUT_NAME, shortcut_name);
        addIntent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, Intent.ShortcutIconResource.fromContext(this, R.drawable.icon));
        addIntent.setAction("com.android.launcher.action.INSTALL_SHORTCUT");
        sendBroadcast(addIntent);
    }

    private void createDisconnectShortcut(String title) {
        Intent shortcutIntent = new Intent(this, OpenVPNClient.class);
        shortcutIntent.setAction("net.openvpn.openvpn.DISCONNECT");

        Intent addIntent = new Intent();
        addIntent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent);
        addIntent.putExtra(Intent.EXTRA_SHORTCUT_NAME, title);
        addIntent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, Intent.ShortcutIconResource.fromContext(this, R.drawable.icon));
        addIntent.setAction("com.android.launcher.action.INSTALL_SHORTCUT");
        sendBroadcast(addIntent);
    }

    // --- Permissions & Activity Results ---

    private void request_file_selection_dialog(int requestCode) {
        if (ContextCompat.checkSelfPermission(this, "android.permission.READ_EXTERNAL_STORAGE") == 0) {
            raise_file_selection_dialog(requestCode);
            return;
        }
        ActivityCompat.requestPermissions(this, new String[]{"android.permission.READ_EXTERNAL_STORAGE"}, requestCode);
    }

    private void raise_file_selection_dialog(int requestCode) {
        if (requestCode == REQUEST_IMPORT_PROFILE) raise_file_selection_dialog(REQUEST_IMPORT_PROFILE, R.string.select_profile);
        else if (requestCode == REQUEST_IMPORT_PKCS12) raise_file_selection_dialog(REQUEST_IMPORT_PKCS12, R.string.select_pkcs12);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0) {
            for (int i = 0; i < grantResults.length; i++) {
                if ("android.permission.READ_EXTERNAL_STORAGE".equals(permissions[i]) && grantResults[i] == 0) {
                    raise_file_selection_dialog(requestCode);
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        Log.d(TAG, String.format("CLI: onActivityResult request=%d result=%d", request, result));
        if (request == REQUEST_VPN_ACTOR_RIGHTS) {
            if (result == RESULT_OK) resolve_epki_alias_then_connect();
            else if (finish_on_connect == FinishOnConnect.ENABLED) finish();
            else if (finish_on_connect == FinishOnConnect.ENABLED_ACROSS_ONSTART) {
                finish_on_connect = FinishOnConnect.ENABLED;
                start_connect();
            }
        } else if ((request == REQUEST_IMPORT_PROFILE || request == REQUEST_IMPORT_PKCS12) && result == RESULT_OK && data != null) {
            String path = data.getStringExtra(FileDialog.RESULT_PATH);
            if (request == REQUEST_IMPORT_PROFILE) submitImportProfileViaPathIntent(path);
            else import_pkcs12(path);
        } else {
            super.onActivityResult(request, result, data);
        }
    }

    // --- Misc Utility Methods ---

    private boolean process_autostart_intent(boolean active) {
        if ((this.startup_state & 3) == 3) {
            Intent intent = getIntent();
            if (intent == null) return false;
            String key = "net.openvpn.openvpn.AUTOSTART_PROFILE_NAME";
            String apn = intent.getStringExtra(key);

            if (apn != null) {
                this.autostart_profile_name = null;
                intent.removeExtra(key);
                if (!active) {
                    ProfileList proflist = profile_list();
                    if (proflist == null || proflist.get_profile_by_name(apn) == null) {
                        ok_dialog(resString(R.string.profile_not_found), apn);
                    } else {
                        this.autostart_profile_name = apn;
                        return true;
                    }
                } else if (!current_profile().get_name().equals(apn)) {
                    this.autostart_profile_name = apn;
                    submitDisconnectIntent(false);
                }
            }
        }
        return false;
    }

    private Profile selected_profile() {
        ProfileList proflist = profile_list();
        return proflist != null ? proflist.get_profile_by_name(selected_profile_name()) : null;
    }

    private String selected_profile_name() {
        ProfileList proflist = profile_list();
        if (proflist != null && proflist.size() > 0) {
            return proflist.size() == 1 ? ((Profile) proflist.get(0)).get_name() : SpinUtil.get_spinner_selected_item(this.profile_spin);
        }
        return "UNDEFINED_PROFILE";
    }

    private int n_profiles_loaded() {
        ProfileList proflist = profile_list();
        return proflist != null ? proflist.size() : 0;
    }

    private void clear_auth() {
        if (this.username_edit != null) this.username_edit.setText("");
        if (this.pk_password_edit != null) this.pk_password_edit.setText("");
        if (this.password_edit != null) this.password_edit.setText("");
        if (this.response_edit != null) this.response_edit.setText("");
    }

    private void reset_conn_info() {
        show_conn_info(new ClientAPI_ConnectionInfo());
    }

    private void hide_status() {
        if (this.status_view != null) this.status_view.setVisibility(View.GONE);
    }

    private void show_status(String text) {
        if (this.status_view != null) {
            this.status_view.setVisibility(View.VISIBLE);
            this.status_view.setText(text);
        }
    }

    private void show_status(int res_id) {
        if (this.status_view != null) {
            this.status_view.setVisibility(View.VISIBLE);
            this.status_view.setText(res_id);
        }
    }

    private void show_status_icon(int res_id) {
        if (this.status_icon_view != null) this.status_icon_view.setImageResource(res_id);
    }

    private void show_progress(int progress, boolean active) {
        if (this.progress_bar == null) return;
        if (progress <= 0 || progress >= 99) {
            this.progress_bar.setVisibility(View.GONE);
        } else {
            this.progress_bar.setVisibility(View.VISIBLE);
            this.progress_bar.setProgress(progress);
        }
    }

    private void cancel_stats() { this.stats_timer_handler.removeCallbacks(this.stats_timer_task); }
    private void schedule_stats() { cancel_stats(); this.stats_timer_handler.postDelayed(this.stats_timer_task, 1000); }
    private void cancel_ui_reset() { this.ui_reset_timer_handler.removeCallbacks(this.ui_reset_timer_task); }
    private void schedule_ui_reset(long delay) { cancel_ui_reset(); this.ui_reset_timer_handler.postDelayed(this.ui_reset_timer_task, delay); }

    private void resolve_epki_alias_then_connect() {
        resolveExternalPkiAlias(selected_profile(), new EpkiPost() {
            @Override
            public void post_dispatch(String alias) {
                do_connect(alias);
            }
        });
    }

    public PendingIntent get_configure_intent(int requestCode) {
        return PendingIntent.getActivity(this, requestCode, getIntent(), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void set_enabled(EditText editText, boolean state) {
        if (editText != null) {
            editText.setEnabled(state);
            editText.setFocusable(state);
            editText.setFocusableInTouchMode(state);
        }
    }

    private void req_focus(EditText editText) {
        boolean auto_keyboard = this.prefs.get_boolean("auto_keyboard", false);
        if (editText != null) {
            editText.requestFocus();
            if (auto_keyboard) raise_keyboard(editText);
        } else {
            if (this.main_scroll_view != null) this.main_scroll_view.requestFocus();
            if (auto_keyboard) dismiss_keyboard();
        }
    }

    private void raise_keyboard(EditText editText) {
        InputMethodManager mgr = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (mgr != null && editText != null) mgr.showSoftInput(editText, 1);
    }

    private void dismiss_keyboard() {
        InputMethodManager mgr = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (mgr != null && this.textviews != null) {
            for (EditText editText : this.textviews) {
                if (editText != null && editText.getWindowToken() != null) {
                    mgr.hideSoftInputFromWindow(editText.getWindowToken(), 0);
                }
            }
        }
    }

    private EditText last_visible_edittext() {
        if (this.textgroups == null || this.textviews == null) return null;
        for (int i = 0; i < this.textgroups.length; i++) {
            if (this.textgroups[i] != null && this.textgroups[i].getVisibility() == View.VISIBLE) {
                return this.textviews[i];
            }
        }
        return null;
    }

    private void menu_add(ContextMenu menu, int id, boolean enabled, String menu_key) {
        if (menu == null) return;
        MenuItem item = menu.add(0, id, 0, id).setEnabled(enabled);
        if (menu_key != null) {
            item.setIntent(new Intent().putExtra("net.openvpn.openvpn.MENU_KEY", menu_key));
        }
    }

    private String get_menu_key(MenuItem item) {
        if (item != null && item.getIntent() != null) {
            return item.getIntent().getStringExtra("net.openvpn.openvpn.MENU_KEY");
        }
        return null;
    }

    private void load_ui_elements() {
        this.main_scroll_view = (ScrollView) findViewById(R.id.main_scroll_view);
        this.post_import_help_blurb = findViewById(R.id.post_import_help_blurb);
        this.profile_group = findViewById(R.id.profile_group);
        this.proxy_group = findViewById(R.id.proxy_group);
        this.server_group = findViewById(R.id.server_group);
        this.username_group = findViewById(R.id.username_group);
        this.password_group = findViewById(R.id.password_group);
        this.pk_password_group = findViewById(R.id.pk_password_group);
        this.cr_group = findViewById(R.id.cr_group);
        this.conn_details_group = findViewById(R.id.conn_details_group);
        this.stats_group = findViewById(R.id.stats_group);
        this.stats_expansion_group = findViewById(R.id.stats_expansion_group);
        this.info_group = findViewById(R.id.info_group);
        this.button_group = findViewById(R.id.button_group);
        this.profile_spin = (Spinner) findViewById(R.id.profile);
        this.profile_edit = (ImageButton) findViewById(R.id.profile_edit);
        this.proxy_spin = (Spinner) findViewById(R.id.proxy);
        this.proxy_edit = (ImageButton) findViewById(R.id.proxy_edit);
        this.server_spin = (Spinner) findViewById(R.id.server);
        this.challenge_view = (TextView) findViewById(R.id.challenge);
        this.username_edit = (EditText) findViewById(R.id.username);
        this.password_edit = (EditText) findViewById(R.id.password);
        this.pk_password_edit = (EditText) findViewById(R.id.pk_password);
        this.response_edit = (EditText) findViewById(R.id.response);
        this.password_save_checkbox = (CheckBox) findViewById(R.id.password_save);
        this.pk_password_save_checkbox = (CheckBox) findViewById(R.id.pk_password_save);
        this.status_view = (TextView) findViewById(R.id.status);
        this.status_icon_view = (ImageView) findViewById(R.id.status_icon);
        this.progress_bar = (ProgressBar) findViewById(R.id.progress);
        this.connect_button = (Button) findViewById(R.id.connect);
        this.disconnect_button = (Button) findViewById(R.id.disconnect);
        this.details_more_less = (TextView) findViewById(R.id.details_more_less);
        this.last_pkt_recv_view = (TextView) findViewById(R.id.last_pkt_recv);
        this.duration_view = (TextView) findViewById(R.id.duration);
        this.bytes_in_view = (TextView) findViewById(R.id.bytes_in);
        this.bytes_out_view = (TextView) findViewById(R.id.bytes_out);

        if (this.connect_button != null) this.connect_button.setOnClickListener(this);
        if (this.disconnect_button != null) this.disconnect_button.setOnClickListener(this);
        if (this.profile_spin != null) {
            this.profile_spin.setOnItemSelectedListener(this);
            registerForContextMenu(this.profile_spin);
        }
        if (this.proxy_spin != null) {
            this.proxy_spin.setOnItemSelectedListener(this);
            registerForContextMenu(this.proxy_spin);
        }
        if (this.server_spin != null) this.server_spin.setOnItemSelectedListener(this);

        View connDetailsBoxed = findViewById(R.id.conn_details_boxed);
        if (connDetailsBoxed != null) connDetailsBoxed.setOnTouchListener(this);

        if (this.profile_edit != null) {
            this.profile_edit.setOnClickListener(this);
            registerForContextMenu(this.profile_edit);
        }
        if (this.proxy_edit != null) {
            this.proxy_edit.setOnClickListener(this);
            registerForContextMenu(this.proxy_edit);
        }

        if (this.username_edit != null) this.username_edit.setOnEditorActionListener(this);
        if (this.password_edit != null) this.password_edit.setOnEditorActionListener(this);
        if (this.pk_password_edit != null) this.pk_password_edit.setOnEditorActionListener(this);
        if (this.response_edit != null) this.response_edit.setOnEditorActionListener(this);

        this.textgroups = new View[]{this.cr_group, this.password_group, this.pk_password_group, this.username_group};
        this.textviews = new EditText[]{this.response_edit, this.password_edit, this.pk_password_edit, this.username_edit};
    }
}
