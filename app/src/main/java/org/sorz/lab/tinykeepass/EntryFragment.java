package org.sorz.lab.tinykeepass;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v4.content.LocalBroadcastManager;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import org.sorz.lab.tinykeepass.keepass.KeePassStorage;

import de.slackspace.openkeepass.domain.Entry;

import static org.sorz.lab.tinykeepass.keepass.KeePassHelper.notEmpty;


public class EntryFragment extends BaseEntryFragment {
    private static final long INACTIVE_AUTO_LOCK_MILLIS = 3 * 60 * 1000;

    private MainActivity activity;
    private ClipboardManager clipboardManager;
    private LocalBroadcastManager localBroadcastManager;
    private FloatingActionButton fab;
    private ActionMode actionMode;
    private long lastPauseTimeMillis;

    /**
     * Mandatory empty constructor for the fragment manager to instantiate the
     * fragment (e.g. upon screen orientation changes).
     */
    public EntryFragment() {
    }


    public static EntryFragment newInstance() {
        return new EntryFragment();
    }

    @Override
    protected int getFragmentLayout() {
        return R.layout.fragment_entry_list;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = super.onCreateView(inflater, container, savedInstanceState);

        if (view != null) {
            fab = view.findViewById(R.id.fab);
            fab.setOnClickListener(v -> {
                if (activity != null) {
                    fab.hide();
                    activity.doSyncDatabase();
                }
            });
        }
        return view;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        activity = (MainActivity) context;
        localBroadcastManager = LocalBroadcastManager.getInstance(activity);
        clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    @Override
    public void onDetach() {
        super.onDetach();
        activity = null;
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (KeePassStorage.get(getContext()) == null ||
                lastPauseTimeMillis > 0 &&
                        SystemClock.elapsedRealtime() - lastPauseTimeMillis >
                                INACTIVE_AUTO_LOCK_MILLIS) {
            // time outed, lock and exit to unlock dialog.
            activity.doLockDatabase();
            activity.doUnlockDatabase();
       } else {
            localBroadcastManager.registerReceiver(broadcastReceiver,
                    new IntentFilter(DatabaseSyncingService.BROADCAST_SYNC_FINISHED));
            // sync done event may have lost, check its state now
            if (!DatabaseSyncingService.isRunning())
                fab.show();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        localBroadcastManager.unregisterReceiver(broadcastReceiver);
        lastPauseTimeMillis = SystemClock.elapsedRealtime();

    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_main, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_lock_db:
                if (activity != null)
                    activity.doLockDatabase();
                return true;
            case R.id.action_exit:
                if (activity != null)
                    getActivity().finish();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /**
     * Immediately copy password & show count down notification.
     * @param entry to copy
     */
    private void copyPassword(Entry entry) {
        Intent intent = new Intent(getContext(), PasswordCopingService.class);
        intent.setAction(PasswordCopingService.ACTION_COPY_PASSWORD);
        intent.putExtra(PasswordCopingService.EXTRA_PASSWORD, entry.getPassword());
        getContext().startService(intent);
        if (getView() != null) {
            Snackbar.make(getView(), R.string.password_copied, Snackbar.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), R.string.password_copied, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Display password.
     * @param entry to show
     */
    private void showPassword(Entry entry) {
        if (getActivity() == null)
            return;
        if (actionMode != null) {
            actionMode.finish();
        }
        actionMode = getActivity().startActionMode(entryShowPasswordActionModeCallback);
        if (actionMode != null) {
            actionMode.setTag(entryShowPasswordActionModeCallback);
            actionMode.setTitle(getString(R.string.title_show_password));
            getEntryAdapter().showPassword(entry);
        }
    }

    private void copyEntry(Entry entry, boolean copyUsername, boolean copyPassword) {
        if (copyUsername && notEmpty(entry.getUsername())) {
            clipboardManager.setPrimaryClip(
                    ClipData.newPlainText(getString(R.string.username), entry.getUsername()));
            String message = getString(R.string.username_copied, entry.getUsername());
            if (getView() == null) {
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            } else {
                Snackbar snackbar = Snackbar.make(getView(), message, Snackbar.LENGTH_LONG);
                if (copyPassword)
                    snackbar.setAction(R.string.copy_password,v -> copyPassword(entry));
                snackbar.show();
            }
        }
        if (copyPassword && notEmpty(entry.getPassword())) {
            if (copyUsername && notEmpty(entry.getUsername())) {
                // username already copied, waiting for user's action before copy password.
                Intent intent = new Intent(getContext(), PasswordCopingService.class);
                intent.setAction(PasswordCopingService.ACTION_NEW_NOTIFICATION);
                intent.putExtra(PasswordCopingService.EXTRA_PASSWORD, entry.getPassword());
                if (entry.getUsername() != null)
                    intent.putExtra(PasswordCopingService.EXTRA_USERNAME, entry.getUsername());
                if (entry.getTitle() != null)
                    intent.putExtra(PasswordCopingService.EXTRA_ENTRY_TITLE, entry.getTitle());
                getContext().startService(intent);
            } else {
                // username not copied, copy password immediately.
                copyPassword(entry);
            }
        }
    }

    private void openEntryUrl(Entry entry) {
        if (entry.getUrl() == null || entry.getUrl().isEmpty())
            return;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(entry.getUrl()));
        startActivity(intent);
        copyEntry(entry, true, true);
    }

    @Override
    protected void onEntryClick(View view, Entry entry) {
        if (actionMode != null) {
            actionMode.finish();
        } else {
            copyEntry(entry, true, true);
        }
    }

    @Override
    protected boolean onEntryLongClick(View view, Entry entry) {
        if (getActivity() == null)
            return false;
        if (actionMode != null) {
            if (actionMode.getTag() == entryLongClickActionModeCallback) {
                actionMode.invalidate();
                return true;
            } else {
                // Finish show password mode
                actionMode.finish();
            }
        }
        actionMode = getActivity().startActionMode(entryLongClickActionModeCallback);
        if (actionMode != null) {
            actionMode.setTag(entryLongClickActionModeCallback);
            return true;
        }
        return false;
    }

    private ActionMode.Callback entryShowPasswordActionModeCallback = new ActionMode.Callback() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem menuItem) {
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            actionMode = null;
            getEntryAdapter().hidePassword();
        }
    };


    private ActionMode.Callback entryLongClickActionModeCallback = new ActionMode.Callback() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.getMenuInflater().inflate(R.menu.entry_context, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            Entry entry = getEntryAdapter().getSelectedItem();
            if (entry == null)
                return false;
            menu.findItem(R.id.action_copy_username).setVisible(notEmpty(entry.getUsername()));
            menu.findItem(R.id.action_copy_password).setVisible(notEmpty(entry.getPassword()));
            menu.findItem(R.id.action_copy_url).setVisible(notEmpty(entry.getUrl()));
            menu.findItem(R.id.action_open).setVisible(notEmpty(entry.getUrl()));
            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            Entry entry = getEntryAdapter().getSelectedItem();
            if (entry == null)
                return false;
            switch (item.getItemId()) {
                case R.id.action_copy_username:
                    copyEntry(entry, true, false);
                    break;
                case R.id.action_copy_password:
                    copyEntry(entry, false, true);
                    break;
                case R.id.action_show_password:
                    mode.finish();
                    showPassword(entry);
                    break;
                case R.id.action_copy_url:
                    if (notEmpty(entry.getUrl())) {
                        clipboardManager.setPrimaryClip(
                                ClipData.newPlainText("URL", entry.getUrl()));
                        if (getView() != null)
                            Snackbar.make(getView(), R.string.url_copied,
                                    Snackbar.LENGTH_SHORT).show();
                    }
                    break;
                case R.id.action_open:
                    openEntryUrl(entry);
                    break;
                default:
                    return false;
            }
            mode.finish();
            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            actionMode = null;
            getEntryAdapter().clearSelection();
        }
    };

    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DatabaseSyncingService.BROADCAST_SYNC_FINISHED.equals(intent.getAction())) {
                fab.show();
                String error = intent.getStringExtra(DatabaseSyncingService.EXTRA_SYNC_ERROR);

                if (error != null) {
                    if (getView() != null)
                        Snackbar.make(getView(), getString(R.string.fail_to_sync, error),
                                Snackbar.LENGTH_LONG).show();
                } else {
                    if (getView() != null)
                        Snackbar.make(getView(), R.string.sync_done, Snackbar.LENGTH_SHORT).show();
                    getEntryAdapter().reloadEntries(context);
                }
            }
        }
    };
}
