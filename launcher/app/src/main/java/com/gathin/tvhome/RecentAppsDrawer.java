package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Slide-up dark frosted glass bottom drawer displaying recently opened apps.
 */
public final class RecentAppsDrawer {
    private final Activity activity;
    private final Runnable onDismissListener;
    private final PopupWindow popup;
    private final View drawerRoot;
    private final RecyclerView recentsList;
    private final TextView recentsEmpty;
    private final List<RecentAppsManager.RecentApp> apps = new ArrayList<>();
    private RecentsAdapter adapter;
    private boolean isDismissing = false;
    private PopupWindow optionsPopup;

    public RecentAppsDrawer(Activity activity, Runnable onDismissListener) {
        this.activity = activity;
        this.onDismissListener = onDismissListener;

        drawerRoot = LayoutInflater.from(activity).inflate(R.layout.drawer_recent_apps, null);
        recentsList = drawerRoot.findViewById(R.id.recentsList);
        recentsEmpty = drawerRoot.findViewById(R.id.recentsEmpty);

        recentsList.setLayoutManager(new LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false));
        adapter = new RecentsAdapter();
        recentsList.setAdapter(adapter);

        popup = new PopupWindow(drawerRoot, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setOnDismissListener(() -> {
            dismissOptions();
            if (onDismissListener != null) onDismissListener.run();
        });

        drawerRoot.setFocusable(true);
        drawerRoot.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_BACK) {
                dismiss();
                return true;
            }
            return false;
        });
    }

    public void show() {
        // Load recent apps off UI thread to avoid any hitch
        Executors.newSingleThreadExecutor().execute(() -> {
            List<RecentAppsManager.RecentApp> loaded = RecentAppsManager.getRecentApps(activity);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                apps.clear();
                apps.addAll(loaded);
                adapter.notifyDataSetChanged();

                if (apps.isEmpty()) {
                    recentsEmpty.setVisibility(View.VISIBLE);
                    recentsList.setVisibility(View.GONE);
                } else {
                    recentsEmpty.setVisibility(View.GONE);
                    recentsList.setVisibility(View.VISIBLE);
                }

                reveal();
            });
        });
    }

    private void reveal() {
        popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.BOTTOM, 0, 0);

        View container = drawerRoot.getRootView();
        if (container.getLayoutParams() instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) container.getLayoutParams();
            params.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            params.dimAmount = 0.45f;
            activity.getWindowManager().updateViewLayout(container, params);
        }

        drawerRoot.post(() -> {
            int height = drawerRoot.getHeight() > 0 ? drawerRoot.getHeight() : (int) (240 * activity.getResources().getDisplayMetrics().density);
            drawerRoot.setTranslationY(height);
            drawerRoot.animate()
                    .translationY(0f)
                    .setInterpolator(new DecelerateInterpolator(1.4f))
                    .setDuration(240)
                    .withEndAction(() -> {
                        if (!apps.isEmpty()) {
                            recentsList.post(() -> {
                                View first = recentsList.getChildAt(0);
                                if (first != null) first.requestFocus();
                            });
                        } else {
                            drawerRoot.requestFocus();
                        }
                    })
                    .start();
        });
    }

    public void dismiss() {
        if (isDismissing) return;
        isDismissing = true;
        dismissOptions();
        int height = drawerRoot.getHeight() > 0 ? drawerRoot.getHeight() : 400;
        drawerRoot.animate()
                .translationY(height)
                .alpha(0.6f)
                .setInterpolator(new AccelerateInterpolator(1.4f))
                .setDuration(180)
                .withEndAction(() -> {
                    try {
                        popup.dismiss();
                    } catch (Exception ignored) {}
                })
                .start();
    }

    private void dismissOptions() {
        if (optionsPopup != null && optionsPopup.isShowing()) {
            try { optionsPopup.dismiss(); } catch (Exception ignored) {}
            optionsPopup = null;
        }
    }

    private void showOptionsDialog(RecentAppsManager.RecentApp app, View anchor, int position) {
        dismissOptions();

        View optionsView = LayoutInflater.from(activity).inflate(R.layout.dialog_recent_app_options, null);
        ImageView ivIcon = optionsView.findViewById(R.id.optionAppIcon);
        TextView tvTitle = optionsView.findViewById(R.id.optionAppTitle);
        TextView tvPkg = optionsView.findViewById(R.id.optionAppPkg);
        Button btnClose = optionsView.findViewById(R.id.btnCloseApp);
        Button btnInfo = optionsView.findViewById(R.id.btnAppInfo);
        Button btnCancel = optionsView.findViewById(R.id.btnCancelOption);

        ivIcon.setImageDrawable(app.icon);
        tvTitle.setText(app.label);
        tvPkg.setText(app.pkg);

        optionsPopup = new PopupWindow(optionsView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        optionsPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        optionsPopup.setOutsideTouchable(true);

        btnClose.setOnClickListener(v -> {
            RecentAppsManager.closeApp(activity, app.pkg);
            Toast.makeText(activity, activity.getString(R.string.app_closed, app.label), Toast.LENGTH_SHORT).show();

            int currentPos = apps.indexOf(app);
            if (currentPos >= 0) {
                apps.remove(currentPos);
                adapter.notifyItemRemoved(currentPos);
            }
            v.post(() -> {
                dismissOptions();
                if (apps.isEmpty()) {
                    dismiss();
                } else {
                    int nextPos = Math.min(Math.max(0, currentPos), apps.size() - 1);
                    recentsList.post(() -> {
                        RecyclerView.ViewHolder vh = recentsList.findViewHolderForAdapterPosition(nextPos);
                        if (vh != null) vh.itemView.requestFocus();
                    });
                }
            });
        });

        btnInfo.setOnClickListener(v -> {
            dismissOptions();
            dismiss();
            try {
                Intent info = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg));
                info.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(info);
            } catch (Exception e) {
                Toast.makeText(activity, activity.getString(R.string.cant_open, app.label), Toast.LENGTH_SHORT).show();
            }
        });

        btnCancel.setOnClickListener(v -> {
            dismissOptions();
            anchor.requestFocus();
        });

        optionsPopup.showAtLocation(activity.getWindow().getDecorView(), Gravity.CENTER, 0, 0);

        View container = optionsView.getRootView();
        if (container.getLayoutParams() instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) container.getLayoutParams();
            params.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            params.dimAmount = 0.55f;
            activity.getWindowManager().updateViewLayout(container, params);
        }

        View.OnKeyListener dismissOnBack = (v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                dismissOptions();
                anchor.requestFocus();
                return true;
            }
            return false;
        };
        btnClose.setOnKeyListener(dismissOnBack);
        btnInfo.setOnKeyListener(dismissOnBack);
        btnCancel.setOnKeyListener(dismissOnBack);

        optionsView.post(btnClose::requestFocus);
    }

    private long lastOptionsOpened = 0;

    private class RecentsAdapter extends RecyclerView.Adapter<RecentsAdapter.ViewHolder> {
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recent_app, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            RecentAppsManager.RecentApp app = apps.get(position);
            holder.ivIcon.setImageDrawable(app.icon);
            holder.tvLabel.setText(app.label);
            holder.tvTime.setText(RecentAppsManager.formatRelativeTime(app.lastUsed));

            holder.itemView.setOnClickListener(v -> {
                if (System.currentTimeMillis() - lastOptionsOpened < 600) return;
                dismiss();
                RecentAppsManager.recordAppOpened(activity, app.pkg);
                try {
                    activity.startActivity(app.launchIntent);
                } catch (Exception e) {
                    Toast.makeText(activity, activity.getString(R.string.cant_open, app.label), Toast.LENGTH_SHORT).show();
                }
            });

            holder.itemView.setOnLongClickListener(v -> {
                lastOptionsOpened = System.currentTimeMillis();
                showOptionsDialog(app, holder.itemView, holder.getBindingAdapterPosition());
                return true;
            });

            holder.itemView.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) dismiss();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_MENU) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        lastOptionsOpened = System.currentTimeMillis();
                        showOptionsDialog(app, holder.itemView, holder.getBindingAdapterPosition());
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && (event.isLongPress() || event.getRepeatCount() >= 1)) {
                        lastOptionsOpened = System.currentTimeMillis();
                        showOptionsDialog(app, holder.itemView, holder.getBindingAdapterPosition());
                        return true;
                    }
                }
                return false;
            });

            holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(160).start();
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(140).start();
                }
            });
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView ivIcon;
            final TextView tvLabel;
            final TextView tvTime;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                ivIcon = itemView.findViewById(R.id.recentIcon);
                tvLabel = itemView.findViewById(R.id.recentLabel);
                tvTime = itemView.findViewById(R.id.recentTime);
            }
        }
    }
}
