package com.example.uhf.navigation;

import androidx.fragment.app.Fragment;
import com.example.uhf.api.MiraApiClient;
import com.example.uhf.fragment.*;
import com.example.uhf.R;
import com.example.uhf.MiraRfid.sound.MiraSoundSettingsFragment;

public class FragmentFactory {

    public static Fragment createFragmentByTag(String tag) {
        if (tag == null) return new MiraDashboardFragment();

        switch (tag) {
            case "Dashboard": return new MiraDashboardFragment();
            case "Scan": return new MiraInventoryProFragment();
            case "POS": return new MiraPosFragment();
            case "Transfers": return new MiraTransferFragment();
            case "IdentityStudio": return new MiraIdentityStudioFragment();
            case "Radar": return new MiraUHFRadarLocationFragment();
            case "SecureGate": return new MiraSecureGateFragment();
            case "Reports": return new MiraReportsProFragment();
            case "Accounting": return new MiraAccountingFragment();
            case "AdminHub": return new MiraAdminHubFragment();
            case "SalesHistory": return new MiraSalesHistoryFragment();
            case "ServerConfig": return new MiraServerConfigFragment();
            case "GoldRates": return new MiraGoldCurrencyFragment();
            case "Profile": return new MiraProfileFragment();
            case "UsersManagement": return new UsersManagementFragment();
            case "RFIDWrite": return new MiraUHFReadWriteFragment();
            case "ReaderSetup": return new MiraReaderSetupFragment();
            case "GateSettings": return new MiraGateSettingsFragment();
            case "Gates": return new MiraSecureGateFragment();
            case "QuickAccessSettings": return new MiraQuickAccessSettingsFragment();
            case "Branches": return new MiraBranchesManagementFragment();
            case "RfidBindings": return new MiraRfidBindingsFragment();
            case "SoundSettings": return new MiraSoundSettingsFragment();
            case "Snapshots": return new MiraSnapshotsFragment();
            case "ScrapGoldBuy": return new MiraScrapGoldBuyFragment();   
            case NavigationManager.TAG_PROFIT_ANALYSIS: return new MiraProfitAnalysisFragment();
            // في switch داخل دالة createFragmentByTag
            case NavigationManager.TAG_VOUCHERS: return new MiraVouchersFragment();
            case NavigationManager.TAG_PARTIES: return new MiraPartiesListFragment();
            case NavigationManager.TAG_SCRAP_AGING: return new MiraScrapAgingFragment();
            
            // ✅ [جديد] إضافة شاشة أمر الصياغة
            case NavigationManager.TAG_GOLD_CONVERSION: return new MiraGoldConversionFragment();
            case NavigationManager.TAG_INVENTORY_REVALUATION: return new MiraInventoryRevaluationFragment();
            default: return new MiraDashboardFragment();
        }
    }
}
