package com.hardening.blindspot;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class TabsAdapter extends FragmentStateAdapter {
    
    public TabsAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }
    
    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 0:
                return new RulesFragment();
            case 1:
                return new ProtectionFragment();
            case 2:
                return new SettingsFragment();
            case 3:
                return new LSPosedFragment();
            default:
                return new RulesFragment();
        }
    }
    
    @Override
    public int getItemCount() {
        return 4; // Four tabs
    }
}
