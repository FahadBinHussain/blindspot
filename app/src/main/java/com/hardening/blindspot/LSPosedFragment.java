package com.hardening.blindspot;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.fragment.app.Fragment;

public class LSPosedFragment extends Fragment {

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.tab_lsposed, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Load app icons
        PackageManager pm = getActivity().getPackageManager();
        
        // Morphe icon
        loadAppIcon(view, R.id.icon_morphe, "app.morphe.android.youtube", pm);
        
        // Messenger icon
        loadAppIcon(view, R.id.icon_messenger, "com.facebook.orca", pm);
        
        // Facebook icon
        loadAppIcon(view, R.id.icon_facebook, "com.facebook.katana", pm);
    }
    
    private void loadAppIcon(View view, int imageViewId, String packageName, PackageManager pm) {
        ImageView iconView = view.findViewById(imageViewId);
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            Drawable icon = pm.getApplicationIcon(appInfo);
            iconView.setImageDrawable(icon);
        } catch (PackageManager.NameNotFoundException e) {
            // App not installed, keep default icon
        }
    }
}
